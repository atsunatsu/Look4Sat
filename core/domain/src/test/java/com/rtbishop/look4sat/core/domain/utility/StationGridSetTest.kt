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
package com.rtbishop.look4sat.core.domain.utility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Upload-side gate for the own-station grid set serialized into the TQ8
 * station record's comma-separated GRIDSQUARE field.
 *
 * LoTW validates grid syntax but not the relationship between a comma-separated set of grids,
 * so a mistyped field letter (PM00AX -> OM00AX) can be stored as a distant "roamed" square. The
 * real-world value that triggered this is the last case.
 */
class StationGridSetTest {

    @Test
    fun `one grid is always signable`() {
        assertNull(stationGridSetIssue(listOf("OM60")))
    }

    @Test
    fun `one six-character grid folds onto its square`() {
        assertNull(stationGridSetIssue(listOf("OM60IK")))
    }

    @Test
    fun `repeated entries of the same square count as one grid`() {
        assertNull(stationGridSetIssue(listOf("OM60", "OM60IK")))
    }

    @Test
    fun `boundary pair sharing a latitude edge is signable`() {
        // OM60 (112-114E, 30-31N) sits just south of OM61 (112-114E, 31-32N).
        assertNull(stationGridSetIssue(listOf("OM60", "OM61")))
    }

    @Test
    fun `boundary pair sharing a longitude edge is signable`() {
        // OM50 (110-112E) sits just west of OM60 (112-114E).
        assertNull(stationGridSetIssue(listOf("OM60", "OM50")))
    }

    @Test
    fun `boundary pair across the antimeridian is signable`() {
        // RL99 ends at 180E, AL09 starts at 180W — one step apart, not 179.
        assertNull(stationGridSetIssue(listOf("RL99", "AL09")))
    }

    @Test
    fun `two grids that only touch at a corner are refused`() {
        assertEquals(
            StationGridSetIssue.NOT_ADJACENT,
            stationGridSetIssue(listOf("OM60", "PM01"))
        )
    }

    @Test
    fun `four grids meeting at one corner are signable`() {
        // The Zhejiang four-way intersection: 118-120E / 120-122E against 30-31N / 31-32N.
        assertNull(stationGridSetIssue(listOf("OM90", "OM91", "PM00", "PM01")))
    }

    @Test
    fun `four grids meeting at one corner across the antimeridian are signable`() {
        assertNull(stationGridSetIssue(listOf("RL99", "AL09", "RM90", "AM00")))
    }

    @Test
    fun `three grids are refused - a station location is 1, 2 or 4`() {
        assertEquals(
            StationGridSetIssue.COUNT,
            stationGridSetIssue(listOf("OM60", "OM61", "OM50"))
        )
    }

    @Test
    fun `four adjacent grids that do not share a single corner are refused`() {
        // A four-cell column: every neighbour touches, but there is no common corner.
        assertEquals(
            StationGridSetIssue.NOT_ADJACENT,
            stationGridSetIssue(listOf("OM60", "OM61", "OM62", "OM63"))
        )
    }

    @Test
    fun `the stored BH6RJD-P location is refused - one field letter is wrong`() {
        // Stored by LoTW as OM90XX,PM01AA,OM00AX,OM91XA: the third grid should be PM00AX. OM00
        // decodes to 100-102E, 1716 km west of the other three, which is what put a phantom
        // roamed square on the map.
        assertEquals(
            StationGridSetIssue.NOT_ADJACENT,
            stationGridSetIssue(listOf("OM90XX", "PM01AA", "OM00AX", "OM91XA"))
        )
        // The corrected location passes.
        assertNull(stationGridSetIssue(listOf("OM90XX", "PM01AA", "PM00AX", "OM91XA")))
    }
}
