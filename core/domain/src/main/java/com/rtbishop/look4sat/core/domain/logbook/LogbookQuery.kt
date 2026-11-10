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
package com.rtbishop.look4sat.core.domain.logbook

/** Confirmation state filters offered by the logbook query. */
enum class LogbookFilter { ALL, CONFIRMED, UNCONFIRMED, DRAFTS }

/** One own-station identity in the logbook: operator callsign plus its transmitting grid. */
data class OwnStationFilter(val callsign: String, val grid: String)

/**
 * Build the identity used by the logbook's own-station selector.
 *
 * A LoTW boundary/corner upload stamps all transmitting grids into [vuccGrids],
 * while the original [myGrid] can still contain only the first locator. Downloaded
 * LoTW contacts carry the same set through MY_VUCC_GRIDS. Group multi-grid
 * stations by their complete, sorted 4-character VUCC set; keep the exact
 * locator for single-grid stations (including its 6/8-character precision).
 */
fun QsoRecord.ownStationIdentity(): OwnStationFilter? {
    val call = myCallsign.trim().uppercase(java.util.Locale.US)
    if (call.isBlank()) return null

    val gridPattern = Regex("[A-R]{2}[0-9]{2}([A-X]{2}([0-9]{2})?)?")
    fun grids(items: List<String>): List<String> = items.flatMap { it.split(',') }
        .map { it.trim().uppercase(java.util.Locale.US) }
        .filter { it.matches(gridPattern) }
        .distinct()

    val stamped = grids(vuccGrids)
    val full = grids(myFullGrids)
    val primary = grids(listOf(myGrid))
    // An accepted upload stamps the actual transmitting squares. Older releases
    // updated this stamp but left MY_GRIDSQUARE/MY_FULL_GRIDS at the old station,
    // so prefer the stamp if it disagrees with the local locators.
    val stampedSquares = stamped.map { it.take(4) }.distinct().sorted()
    val grid = if (stampedSquares.isNotEmpty()) {
        if (stampedSquares.size > 1) {
            stampedSquares.joinToString(",")
        } else {
            val square = stampedSquares.single()
            // Keep precise 6/8-character locators when they agree with the signed
            // square; do not silently keep an old locator from another square.
            primary.firstOrNull { it.take(4) == square }
                ?: full.firstOrNull { it.take(4) == square }
                ?: stamped.first()
        }
    } else {
        // Legacy imports can have multiple grids without an upload stamp.
        val multi = listOf(full, primary).firstOrNull { candidates ->
            candidates.map { it.take(4) }.distinct().size > 1
        }
        multi?.map { it.take(4) }?.distinct()?.sorted()?.joinToString(",")
            ?: primary.firstOrNull() ?: full.firstOrNull() ?: ""
    }
    return OwnStationFilter(call, grid)
}

fun ownStationOptions(records: List<QsoRecord>): List<OwnStationFilter> =
    records.mapNotNull(QsoRecord::ownStationIdentity).distinct()
        .sortedWith(compareBy<OwnStationFilter> { it.callsign }.thenBy { it.grid })

/**
 * The logbook query shared by every logbook surface (the enhanced logbook page and the
 * settings logbook dialog's query sheet). Both used to / would otherwise carry their own
 * copy of this predicate, which is exactly how the two lists drift apart — so it lives
 * here, next to the record model, as the single definition of "matches the query".
 *
 * [query] is matched case-insensitively against the opposite callsign, the satellite
 * name, both primary grids, the counterpart's VUCC grids and the comment. [ownStationFilter]
 * independently limits results to one operator callsign and transmitting grid.
 * [modeFilter] is either blank (any mode)
 * or an exact [displayMode] — the modes offered are whatever the log actually contains, so
 * there is no sentinel for "worked via satellite". [confirmation] narrows to confirmed,
 * completed-but-unconfirmed, or not-completed records.
 */
fun filterRecords(
    records: List<QsoRecord>,
    query: String,
    modeFilter: String,
    confirmation: LogbookFilter,
    ownStationFilter: OwnStationFilter? = null
): List<QsoRecord> = records.filter { record ->
    val trimmed = query.trim()
    (trimmed.isEmpty() || (listOf(
        record.theirCallsign,
        record.satelliteName,
        record.theirGrid,
        record.myGrid,
        record.comment
    ) + record.theirVuccGrids).any { it.contains(trimmed, ignoreCase = true) }) &&
        (ownStationFilter == null ||
            record.ownStationIdentity() == ownStationFilter) &&
        when (modeFilter) {
            "" -> true
            else -> record.displayMode == modeFilter
        } &&
        when (confirmation) {
            LogbookFilter.ALL -> true
            LogbookFilter.CONFIRMED -> record.lotwConfirmed
            LogbookFilter.UNCONFIRMED -> record.status == QsoStatus.COMPLETE && !record.lotwConfirmed
            LogbookFilter.DRAFTS -> record.status != QsoStatus.COMPLETE
        }
}
