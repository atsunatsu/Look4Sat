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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The map's VUCC view scopes QSO data to the operated-grid selector's choice
 * (台址 = station location): [stationGridSetKey] identifies a record's 台址 and
 * [scopedToStation] applies the choice — the grid-detail dialog relies on both.
 */
class GridQsoStationScopeTest {

    private fun qso(
        call: String = "BG7XYZ",
        myGrid: String? = null,
        myGrids: Set<String> = emptySet()
    ) = GridQso(
        call = call, epochMs = 0L, satName = "SO-50", mode = "FM",
        bandUp = "70CM", bandDown = "2M", myGrid = myGrid, myGrids = myGrids
    )

    @Test
    fun `multi-grid record keys by its full sorted grid set`() {
        assertEquals("OL62,PM01", qso(myGrids = setOf("PM01", "OL62")).stationGridSetKey())
    }

    @Test
    fun `legacy single-grid record falls back to myGrid`() {
        assertEquals("OL62", qso(myGrid = "OL62").stationGridSetKey())
    }

    @Test
    fun `myGrids wins over myGrid when both are present`() {
        val q = qso(myGrid = "OL62", myGrids = setOf("OM60", "PM01"))
        assertEquals("OM60,PM01", q.stationGridSetKey())
    }

    @Test
    fun `record without any MY grid carries no key`() {
        assertNull(qso().stationGridSetKey())
    }

    @Test
    fun `key does not depend on the grid set's iteration order`() {
        assertEquals(
            qso(myGrids = setOf("PM01", "OL62")).stationGridSetKey(),
            qso(myGrids = setOf("OL62", "PM01")).stationGridSetKey()
        )
    }

    @Test
    fun `null scope keeps every record`() {
        val a = qso("A", myGrid = "OL62")
        val b = qso("B", myGrid = "PM01")
        assertEquals(listOf(a, b), listOf(a, b).scopedToStation(null))
    }

    @Test
    fun `specific scope keeps covering station's records too`() {
        // 方案 A 覆盖语义: 选中单格台址 OL62 时，覆盖它的多格台址 (OL62,PM01)
        // 的通联也计入；反向不泄漏。
        val multi = qso("A", myGrids = setOf("OL62", "PM01"))
        val single = qso("B", myGrid = "OL62")
        val list = listOf(multi, single)
        assertEquals(listOf(multi), list.scopedToStation("OL62,PM01"))
        assertEquals(listOf(multi, single), list.scopedToStation("OL62"))
    }

    @Test
    fun `narrower station never leaks into a wider scope`() {
        val single = qso("B", myGrid = "OL62")
        val pair = qso("A", myGrids = setOf("OL62", "PM01"))
        // 宽台址 (OL62,PM01) 选中时，单格 OL62 的记录不出现
        assertEquals(emptyList<GridQso>(), listOf(single, pair).scopedToStation("OL62,PM01").filter { it === single })
        assertEquals(listOf(pair), listOf(single, pair).scopedToStation("OL62,PM01"))
    }

    @Test
    fun `covering is per-cell set containment not string equality`() {
        // 多格台址的 key 是排序拼接，覆盖判定必须按网格集合而非字符串
        val quad = qso("A", myGrids = setOf("OL62", "OL63", "OL64", "OL65"))
        assertEquals(listOf(quad), listOf(quad).scopedToStation("OL63"))
        assertEquals(listOf(quad), listOf(quad).scopedToStation("OL62,OL65"))
        assertEquals(emptyList<GridQso>(), listOf(quad).scopedToStation("OL62,PM01"))
    }

    @Test
    fun `records without a MY grid belong to no station scope`() {
        val orphan = qso("A")
        val mine = qso("B", myGrid = "OL62")
        assertEquals(listOf(mine), listOf(orphan, mine).scopedToStation("OL62"))
    }

    @Test
    fun `scoping to a station absent from the list yields empty`() {
        val list = listOf(qso("A", myGrid = "OL62"))
        assertEquals(emptyList<GridQso>(), list.scopedToStation("PM01"))
    }

    @Test
    fun `worked grids are derived only from selected station QSOs`() {
        val ol62 = qso("A", myGrid = "OL62")
        val ol63 = qso("B", myGrid = "OL63")
        val store = mapOf(
            "FN31" to listOf(ol62),
            "FN32" to listOf(ol63),
            GRIDLESS_QSO_BUCKET to listOf(ol62)
        )

        assertEquals(setOf("FN31"), store.workedGridsForStations(setOf("OL62")))
        assertEquals(setOf("FN32"), store.workedGridsForStations(setOf("OL63")))
    }

    // --- firstCallsByGrid ---

    @Test
    fun `first call per grid picks the earliest of the scoped records`() {
        val store = mapOf(
            "OL62" to listOf(
                qso("OTHER", myGrid = "PM01").copy(epochMs = 10),
                qso("MINE", myGrid = "OL62").copy(epochMs = 20)
            ),
            "PM95" to listOf(qso("MINE2", myGrid = "OL62").copy(epochMs = 30))
        )
        assertEquals(mapOf("OL62" to "MINE", "PM95" to "MINE2"), store.firstCallsByGrid("OL62"))
    }

    @Test
    fun `first call per grid with All uses every record`() {
        val store = mapOf(
            "OL62" to listOf(
                qso("OTHER", myGrid = "PM01").copy(epochMs = 10),
                qso("MINE", myGrid = "OL62").copy(epochMs = 20)
            )
        )
        assertEquals(mapOf("OL62" to "OTHER"), store.firstCallsByGrid(null))
    }

    @Test
    fun `grids without scoped records get no first call`() {
        val store = mapOf("OL62" to listOf(qso("A", myGrid = "PM01")))
        assertEquals(emptyMap<String, String>(), store.firstCallsByGrid("OL62"))
    }
}
