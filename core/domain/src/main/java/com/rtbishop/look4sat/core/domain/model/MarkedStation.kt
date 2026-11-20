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
package com.rtbishop.look4sat.core.domain.model

/**
 * A station the user marked as a contact target in a 4-char gridsquare.
 *
 * @param call       the callsign the user wants to work (uppercase)
 * @param epochMs    the moment the mark was created, UTC milliseconds
 * @param stationIds operated-station grid-set keys selected when the mark was
 *                   created. Empty means an unscoped legacy/All mark.
 */
data class MarkedStation(
    val call: String,
    val epochMs: Long,
    val stationIds: Set<String> = emptySet()
)

/**
 * Keep station-specific reminders inside their station scope when a specific
 * operated station is selected. All (empty selection) aggregates reminders from
 * every station, but never paints a red reminder over a globally worked (green)
 * grid. Those hidden reminders are still kept for their individual stations.
 */
fun Map<String, List<MarkedStation>>.visibleInStationScope(
    selectedStationIds: Set<String>,
    workedGrids: Set<String> = emptySet()
): Map<String, List<MarkedStation>> =
    mapNotNull { (grid, marks) ->
        if (selectedStationIds.isEmpty() && grid in workedGrids) return@mapNotNull null
        val visible = if (selectedStationIds.isEmpty()) marks else marks.filter { mark ->
            mark.stationIds.isEmpty() || mark.stationIds == selectedStationIds
        }
        if (visible.isEmpty()) null else grid to visible
    }.toMap()
