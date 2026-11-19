/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
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

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.model.GridQso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Trimmed base form of a Wavelog server URL (".../wavelog/" -> ".../wavelog"). */
fun normalizeWavelogUrl(url: String): String = url.trim().trimEnd('/')

/**
 * Resolves the effective sync mode for a request:
 * - an explicit [WavelogSyncMode.Full] is always honored;
 * - an [WavelogSyncMode.Incremental] request is honored only when the stored sync URL
 *   matches the current one — a first sync or a server switch falls back to
 *   [WavelogSyncMode.Full], so cursors from another server never leak in.
 */
fun resolveWavelogSyncMode(storedUrl: String, currentUrl: String, requested: WavelogSyncMode?): WavelogSyncMode {
    if (requested == WavelogSyncMode.Full) return WavelogSyncMode.Full
    val sameServer = storedUrl.isNotBlank() && storedUrl == normalizeWavelogUrl(currentUrl)
    return if (sameServer) WavelogSyncMode.Incremental else WavelogSyncMode.Full
}

/** Grid data derived from Wavelog-synced records, in the same shape the LoTW sync produces. */
data class WavelogGridData(
    val grids: Set<String>,
    val gridQsos: Map<String, List<GridQso>>,
    val roamedGrids: Set<String>
)

internal fun isValidGrid4(grid: String): Boolean =
    grid.length == 4 && grid[0] in 'A'..'R' && grid[1] in 'A'..'R' &&
        grid[2] in '0'..'9' && grid[3] in '0'..'9'

/**
 * "Confirmed" in Wavelog's own sense. Its API's `qsl_filter` maps lotw / qsl / eqsl to
 * COL_LOTW_QSL_RCVD / COL_QSL_RCVD / COL_EQSL_QSL_RCVD = 'Y', and the ADIF export carries
 * those as LOTW_QSL_RCVD / QSL_RCVD / EQSL_QSL_RCVD.
 */
fun QsoRecord.isQslConfirmed(): Boolean = lotwConfirmed || qslConfirmed || eqslConfirmed

/**
 * How stale the last full pull may get before an incremental request is promoted to a full
 * one. Wavelog's pull API filters by row id only, so a confirmation that arrives for a row
 * pulled earlier (LoTW confirmations land on the same row, keeping its id) can never be seen
 * by the legacy incremental cursor. For installations without a v2 confirmation token,
 * a full pull re-reads every row with its current confirmation flags. It also rebuilds the
 * stored grid set from scratch, so a square that
 * only ever existed because an unconfirmed QSO carried a looked-up grid drops out on its own.
 */
const val WAVELOG_FULL_RESCAN_INTERVAL_MS: Long = 3L * 24 * 60 * 60 * 1000

fun shouldPromoteWavelogFullSync(
    lastFullSyncEpochMs: Long,
    now: Long = System.currentTimeMillis()
): Boolean = lastFullSyncEpochMs <= 0L || now - lastFullSyncEpochMs >= WAVELOG_FULL_RESCAN_INTERVAL_MS

/**
 * Derives the worked-grid set, the per-grid QSO detail and the roamed (own) grids from
 * records pulled by a Wavelog sync — mirroring what the LoTW report parser produces, so
 * both sync sources feed the map's highlight and its 台址 selector the same data shape.
 *
 * Only CONFIRMED contacts turn a square green: the LoTW side derives its grids from ARRL's
 * confirmation report, so a Wavelog record that merely carries the opposite station's grid
 * (Wavelog fills those in from a callsign lookup) must not count before it is confirmed.
 * The roamed set is the operator's own operating location, not the opposite station's
 * confirmation, so it keeps taking every satellite record with a valid own grid.
 */
fun buildWavelogGridData(records: List<QsoRecord>): WavelogGridData {
    val grids = linkedSetOf<String>()
    val roamed = linkedSetOf<String>()
    val gridQsos = linkedMapOf<String, MutableList<GridQso>>()
    records.forEach { record ->
        val isSatellite = record.satelliteName.isNotBlank() || record.propagationMode.equals("SAT", true)
        if (!isSatellite) return@forEach
        val theirLocators = record.theirFullGrids.ifEmpty {
            listOf(record.theirGrid) + record.theirVuccGrids
        }.map { it.trim().uppercase(Locale.US) }
            .filter { it.length >= 4 && isValidGrid4(it.take(4)) }
        val theirGrids = theirLocators.map { it.take(4) }.distinct()
        val theirFineGrids = theirLocators.filter { it.length >= 6 }.distinct()
        if (theirGrids.isEmpty()) return@forEach
        val myLocators = record.myFullGrids.ifEmpty {
            listOf(record.myGrid) + record.vuccGrids
        }.map { it.trim().uppercase(Locale.US) }
        val myGrids = myLocators.map { it.take(4) }
            .filter(::isValidGrid4)
            .toSet()
        roamed += myGrids
        if (!record.isQslConfirmed()) return@forEach
        val qso = GridQso(
            call = record.theirCallsign,
            epochMs = record.startUtcMillis,
            satName = record.satelliteName,
            mode = record.mode,
            bandUp = record.band,
            bandDown = record.rxBand,
            dxcc = record.dxcc,
            country = record.country.ifBlank { null },
            cqz = record.cqZone,
            state = record.region.ifBlank { null },
            iota = record.iota.ifBlank { null },
            myGrid = myGrids.sorted().firstOrNull(),
            myGrids = myGrids,
            myFineGrids = myLocators.filter { it.length >= 6 }.distinct(),
            myIota = record.myIota.ifBlank { null },
            myCallsign = record.myCallsign.ifBlank { null },
            theirGrids = theirGrids,
            theirFineGrids = theirFineGrids
        )
        theirGrids.forEach { grid ->
            grids += grid
            gridQsos.getOrPut(grid) { mutableListOf() }.add(qso)
        }
    }
    return WavelogGridData(grids, gridQsos, roamed)
}

/**
 * Applies a successful Wavelog sync to the stored map data and sync bookkeeping.
 *
 * An incremental pull MERGES into the stored sets (union: a late confirmation only ever
 * adds a square). A full pull REPLACES them — same contract as the LoTW full sync — so the
 * squares left behind by unconfirmed records that used to be counted disappear, and so do
 * rows the log no longer has. A full pull that lost a station is partial: it degrades to
 * the merge instead of wiping the squares that station contributed.
 *
 * Cursors are replaced wholesale ([cursors] covers every station just fetched), and the
 * sync URL/epoch advance so the next run resumes incrementally. Persistence runs on the IO
 * dispatcher — building the per-grid QSO JSON blocks the caller, and for large accounts that
 * froze the main thread after a manual sync.
 *
 * @return the resulting number of worked grids.
 */
suspend fun applyWavelogSyncResult(
    settingsRepo: ISettingsRepo,
    records: List<QsoRecord>,
    cursors: Map<String, Long>,
    url: String,
    mode: WavelogSyncMode,
    failedStations: Set<String> = emptySet(),
    now: Long = System.currentTimeMillis()
): Int {
    val data = buildWavelogGridData(records)
    val replace = mode == WavelogSyncMode.Full && failedStations.isEmpty()
    val existingGrids = settingsRepo.getWorkedGrids()
    val existingQsos = settingsRepo.getWorkedGridQsos()
    val existingRoamed = settingsRepo.getRoamedGrids()
    val mergedGrids = if (replace) data.grids else existingGrids + data.grids
    val mergedQsos = if (replace) data.gridQsos else mergeGridQsos(existingQsos, data.gridQsos)
    val mergedRoamed = if (replace) data.roamedGrids else existingRoamed + data.roamedGrids
    withContext(Dispatchers.IO) {
        settingsRepo.setWorkedGrids(mergedGrids)
        settingsRepo.setWorkedGridQsos(mergedQsos)
        settingsRepo.setRoamedGrids(mergedRoamed)
        settingsRepo.setWavelogSyncCursors(cursors)
        settingsRepo.setWavelogSyncUrl(normalizeWavelogUrl(url))
        settingsRepo.setLastWavelogSyncEpochMs(now)
        if (replace) settingsRepo.setLastWavelogFullSyncEpochMs(now)
    }
    return mergedGrids.size
}
