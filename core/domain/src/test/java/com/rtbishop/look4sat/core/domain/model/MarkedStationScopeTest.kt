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

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkedStationScopeTest {

    @Test
    fun allSelectionShowsMarksFromEveryStationIncludingLegacyMarks() {
        val global = MarkedStation(call = "BG7XYZ", epochMs = 1L)
        val ol62 = MarkedStation(call = "BA4ABC", epochMs = 2L, stationIds = setOf("OL62"))
        val ol63 = MarkedStation(call = "BD4DEF", epochMs = 3L, stationIds = setOf("OL63"))
        val marks = mapOf("FN31" to listOf(global, ol62, ol63))

        assertEquals(
            marks,
            marks.visibleInStationScope(emptySet())
        )
    }

    @Test
    fun allSelectionPrioritizesWorkedGreenButPreservesMarksForIndividualStations() {
        val ol62 = MarkedStation(call = "BA4ABC", epochMs = 1L, stationIds = setOf("OL62"))
        val ol63 = MarkedStation(call = "BD4DEF", epochMs = 2L, stationIds = setOf("OL63"))
        val global = MarkedStation(call = "BG7XYZ", epochMs = 3L)
        val marks = mapOf(
            "FN31" to listOf(ol62, ol63),
            "FN32" to listOf(ol63),
            "FN33" to listOf(global)
        )
        val globallyWorked = setOf("FN31", "FN33")

        assertEquals(
            mapOf("FN32" to listOf(ol63)),
            marks.visibleInStationScope(emptySet(), globallyWorked)
        )
        // FN31 was worked at some station, but the OL62 reminder stays scoped.
        assertEquals(
            mapOf("FN31" to listOf(ol62), "FN33" to listOf(global)),
            marks.visibleInStationScope(setOf("OL62"), globallyWorked)
        )
        assertEquals(
            mapOf("FN31" to listOf(ol63), "FN32" to listOf(ol63), "FN33" to listOf(global)),
            marks.visibleInStationScope(setOf("OL63"), globallyWorked)
        )
    }

    @Test
    fun stationSelectionShowsOnlyItsOwnMarksAndUnscopedLegacyMarks() {
        val global = MarkedStation(call = "BG7XYZ", epochMs = 1L)
        val ol62 = MarkedStation(call = "BA4ABC", epochMs = 2L, stationIds = setOf("OL62"))
        val ol63 = MarkedStation(call = "BD4DEF", epochMs = 3L, stationIds = setOf("OL63"))
        val marks = mapOf("FN31" to listOf(global, ol62, ol63))

        assertEquals(
            mapOf("FN31" to listOf(global, ol62)),
            marks.visibleInStationScope(setOf("OL62"))
        )
        assertEquals(
            mapOf("FN31" to listOf(global, ol63)),
            marks.visibleInStationScope(setOf("OL63"))
        )
    }

    @Test
    fun sameCallCanHaveIndependentMarksAtDifferentStations() {
        val ol62 = MarkedStation(call = "BA4ABC", epochMs = 2L, stationIds = setOf("OL62"))
        val ol63 = MarkedStation(call = "BA4ABC", epochMs = 3L, stationIds = setOf("OL63"))
        val marks = mapOf("FN31" to listOf(ol62, ol63))

        assertEquals(listOf(ol62), marks.visibleInStationScope(setOf("OL62"))["FN31"])
        assertEquals(listOf(ol63), marks.visibleInStationScope(setOf("OL63"))["FN31"])
    }
}
