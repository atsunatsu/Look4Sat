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

/** Sentinel mode filter: only records worked through a satellite. */
const val SATELLITE_MODE_FILTER = "__SATELLITE__"

/**
 * The logbook query shared by every logbook surface (the enhanced logbook page and the
 * settings logbook dialog's query sheet). Both used to / would otherwise carry their own
 * copy of this predicate, which is exactly how the two lists drift apart — so it lives
 * here, next to the record model, as the single definition of "matches the query".
 *
 * [query] is matched case-insensitively against the opposite callsign, the own callsign,
 * the satellite name, both grids and the comment. [modeFilter] is either blank (any mode),
 * [SATELLITE_MODE_FILTER], or an exact [displayMode]. [confirmation] narrows to confirmed,
 * completed-but-unconfirmed, or not-completed records.
 */
fun filterRecords(
    records: List<QsoRecord>,
    query: String,
    modeFilter: String,
    confirmation: LogbookFilter
): List<QsoRecord> = records.filter { record ->
    val trimmed = query.trim()
    (trimmed.isEmpty() || listOf(
        record.theirCallsign,
        record.myCallsign,
        record.satelliteName,
        record.theirGrid,
        record.myGrid,
        record.comment
    ).any { it.contains(trimmed, ignoreCase = true) }) &&
        when (modeFilter) {
            "" -> true
            SATELLITE_MODE_FILTER -> record.isSatellite
            else -> record.displayMode == modeFilter
        } &&
        when (confirmation) {
            LogbookFilter.ALL -> true
            LogbookFilter.CONFIRMED -> record.lotwConfirmed
            LogbookFilter.UNCONFIRMED -> record.status == QsoStatus.COMPLETE && !record.lotwConfirmed
            LogbookFilter.DRAFTS -> record.status != QsoStatus.COMPLETE
        }
}
