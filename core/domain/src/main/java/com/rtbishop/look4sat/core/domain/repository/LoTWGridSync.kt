/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.GridQso
import com.rtbishop.look4sat.core.domain.model.myStationGrids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Shared LoTW sync orchestration used by BOTH the manual sync (settings screen)
 * and the automatic sync on app start (MainApplication), so the two paths can
 * never drift apart:
 *
 * - [LoTWSyncMode.Incremental] merges new and updated confirmations; a corrected
 *   station location replaces the old QSO without leaving stale grids behind.
 * - [LoTWSyncMode.Full] pulls everything and REPLACES the stored data.
 */

/**
 * UTC date ("yyyyMMdd") of the given instant — the "already synced today"
 * gate granularity.
 */
fun lotwSyncToday(now: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyyMMdd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(now))

/**
 * Full UTC timestamp cursor ("yyyy-MM-dd HH:mm:ss") written after a
 * successful sync. This is exactly the format LoTW's qso_qslsince accepts
 * (measured live 2026-09-17: the legacy "yyyyMMdd" cursor is silently
 * ignored by LoTW, which falls back to its own system-supplied default),
 * and it keeps enough precision to display the last-sync time like the
 * ephemeris update time.
 */
fun lotwSyncCursor(now: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(now))

/** Date part of a stored cursor as "yyyyMMdd", tolerating the legacy
 *  "yyyyMMdd" format and the current "yyyy-MM-dd HH:mm:ss" format. */
fun lotwCursorDate(cursor: String): String = when {
    cursor.length >= 10 && cursor[4] == '-' -> cursor.substring(0, 10).replace("-", "")
    else -> cursor.take(8)
}

/** qso_qslsince value for the API: passes the full timestamp through and
 *  normalizes a legacy "yyyyMMdd" cursor to the documented "yyyy-MM-dd". */
fun lotwCursorApi(cursor: String): String = when {
    cursor.isBlank() -> ""
    cursor.length >= 10 && cursor[4] == '-' -> cursor
    else -> "${cursor.take(4)}-${cursor.substring(4, 6)}-${cursor.substring(6, 8)}"
}

/** Query LoTW from its latest QSL timestamp (older installs fall back to their last sync). */
fun lotwIncrementalSince(settingsRepo: ISettingsRepo): String =
    lotwCursorApi(settingsRepo.getLastLotwQslCursor().ifBlank { settingsRepo.getLastLotwSyncDate() })

/** Stored cursor parsed to UTC epoch ms for display; 0 when unparseable. */
fun lotwCursorEpochMs(cursor: String): Long = try {
    val pattern = if (cursor.length >= 10 && cursor[4] == '-') "yyyy-MM-dd HH:mm:ss" else "yyyyMMdd"
    SimpleDateFormat(pattern, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .parse(cursor)?.time ?: 0L
} catch (_: Exception) {
    0L
}

/**
 * Resolves the effective sync mode for a given request:
 * - an explicit [LoTWSyncMode.Full] request is always honored;
 * - an [LoTWSyncMode.Incremental] request is honored only when the callsign
 *   matches the last sync — a first sync, an unknown/empty record or a
 *   callsign change falls back to [LoTWSyncMode.Full] so no stale grids from
 *   another account linger.
 * With [requested] == null (automatic sync) it picks incremental whenever
 * possible and full otherwise.
 */
fun resolveLoTWSyncMode(
    lastSyncCallsign: String,
    callsign: String,
    requested: LoTWSyncMode?
): LoTWSyncMode {
    if (requested == LoTWSyncMode.Full) return LoTWSyncMode.Full
    val sameCall = lastSyncCallsign.isNotBlank() && lastSyncCallsign == callsign
    return if (sameCall) LoTWSyncMode.Incremental else LoTWSyncMode.Full
}

/**
 * Whether the automatic LoTW sync should run now — mirrors the ephemeris
 * auto-update check: credentials configured, the LoTW auto-sync toggle on, at
 * least one prior manual sync (the first sync stays manual/full), and not
 * already synced today (ARRL limits report pulls, ~once a day per account).
 */
fun shouldAutoSyncLoTW(
    isConfigured: Boolean,
    autoLotwSyncEnabled: Boolean,
    lastSyncDate: String,
    today: String
): Boolean {
    val lastDate = lotwCursorDate(lastSyncDate)
    return isConfigured && autoLotwSyncEnabled && lastDate.isNotBlank() && lastDate != today
}

/**
 * Applies a successful LoTW report to the stored grid data and advances the
 * sync cursor so the next incremental pull asks LoTW for only the
 * confirmations since today. Persistence runs on the IO dispatcher — building
 * the per-grid QSO JSON and writing SharedPreferences blocks the calling
 * thread, and for large accounts (multi-MB JSON) that froze the main thread
 * after a manual sync ('bar done but app stuck').
 *
 * @return the resulting number of worked grids.
 */
suspend fun applyLoTWGridResult(
    settingsRepo: ISettingsRepo,
    result: LoTWResult.Success,
    mode: LoTWSyncMode,
    callsign: String,
    now: Long = System.currentTimeMillis()
): Int {
    val existingGrids = settingsRepo.getWorkedGrids()
    val existingQsos = settingsRepo.getWorkedGridQsos()
    val existingRoamed = settingsRepo.getRoamedGrids()
    val mergedQsos = if (mode == LoTWSyncMode.Incremental) mergeGridQsos(existingQsos, result.qsos) else result.qsos

    // Old/new set union is not valid for QSL corrections: obsolete QSO grids must
    // disappear unless another surviving QSO still covers them. Keep standalone
    // legacy grids without QSO detail, since their origin cannot be reconstructed.
    val mergedGrids = if (mode == LoTWSyncMode.Incremental) {
        (existingGrids - existingQsos.keys) + mergedQsos.keys + result.grids
    } else result.grids
    val mergedRoamed = if (mode == LoTWSyncMode.Incremental) {
        val previouslyDetailed = existingQsos.values.asSequence().flatten()
            .flatMap { it.myStationGrids().asSequence() }.toSet()
        val currentlyDetailed = mergedQsos.values.asSequence().flatten()
            .flatMap { it.myStationGrids().asSequence() }.toSet()
        (existingRoamed - previouslyDetailed) + currentlyDetailed + result.roamedGrids
    } else result.roamedGrids

    // The device clock is not a safe LoTW update watermark: prefer the last
    // QSL-update time returned in APP_LoTW_LASTQSL.
    val previousCursor = lotwIncrementalSince(settingsRepo)
    val reportedCursor = result.lastQslCursor.orEmpty()
    val nextCursor = when (mode) {
        LoTWSyncMode.Full -> reportedCursor.ifBlank { lotwSyncCursor(now) }
        LoTWSyncMode.Incremental -> maxOf(previousCursor, reportedCursor).ifBlank { lotwSyncCursor(now) }
    }
    withContext(Dispatchers.IO) {
        settingsRepo.setWorkedGrids(mergedGrids)
        settingsRepo.setWorkedGridQsos(mergedQsos)
        settingsRepo.setRoamedGrids(mergedRoamed)
        // Advance the watermark only after the map data is saved.
        settingsRepo.setLastLotwQslCursor(nextCursor)
        settingsRepo.setLastLotwSyncCallsign(callsign)
        settingsRepo.setLastLotwSyncDate(lotwSyncCursor(now))
    }
    return mergedGrids.size
}

/** Mutable MY_* station fields are deliberately excluded from contact identity. */
private data class ConfirmedQsoKey(
    val call: String,
    val epochMs: Long,
    val satellite: String,
    val mode: String,
    val band: String
)

private fun GridQso.confirmedKey(): ConfirmedQsoKey = ConfirmedQsoKey(
    call.trim().uppercase(Locale.US),
    epochMs,
    satName.trim().uppercase(Locale.US),
    mode.trim().uppercase(Locale.US),
    bandUp.trim().uppercase(Locale.US)
)

/** Replace any updated contact across every old worked-grid key, then insert its
 * new record under every fresh grid. Other QSOs and multi-grid contacts survive. */
fun mergeGridQsos(
    existing: Map<String, List<GridQso>>,
    fresh: Map<String, List<GridQso>>
): Map<String, List<GridQso>> {
    val changedKeys = fresh.values.asSequence().flatten().map { it.confirmedKey() }.toSet()
    if (changedKeys.isEmpty()) return existing
    val merged = existing.mapValues { (_, list) ->
        list.filterNot { it.confirmedKey() in changedKeys }.toMutableList()
    }.filterValues { it.isNotEmpty() }.toMutableMap()
    fresh.forEach { (grid, list) ->
        val updated = list.distinctBy { it.confirmedKey() }
        if (updated.isNotEmpty()) merged.getOrPut(grid) { mutableListOf() }.addAll(updated)
    }
    return merged
}
