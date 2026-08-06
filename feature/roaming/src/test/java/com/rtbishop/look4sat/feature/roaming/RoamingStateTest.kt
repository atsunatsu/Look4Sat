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
package com.rtbishop.look4sat.feature.roaming

import org.junit.Assert.assertEquals
import org.junit.Test

class RoamingStateTest {

    // 用户实测坐标 (QTH定位器 2.0 成品截图): 22.314066 / 108.706575
    private fun stateOf(lat: Double, lon: Double) =
        roamingStateFromLocation(lat, lon, System.currentTimeMillis(), "21:")

    @Test
    fun `user coordinates produce OL42ih45`() {
        val state = stateOf(22.314066, 108.706575)
        assertEquals("OL42ih45", state.loc)
    }

    @Test
    fun `red marker from ih pair matches reference lookup`() {
        val state = stateOf(22.314066, 108.706575)
        // 成品查表: 经度第3对 'i' -> leftMargin 65dp, 纬度第3对 'h' -> topMargin 137dp
        assertEquals(65, state.markerLeft)
        assertEquals(137, state.markerTop)
    }

    @Test
    fun `grid nine from OL42 matches reference`() {
        val state = stateOf(22.314066, 108.706575)
        val expected = listOf(
            "OL33", "OL43", "OL53",
            "OL32", "OL42", "OL52",
            "OL31", "OL41", "OL51"
        )
        assertEquals(expected, state.grids)
    }

    @Test
    fun `latitude dms formatting matches reference`() {
        val state = stateOf(22.314066, 108.706575)
        assertEquals("纬度  22° 18' 50\" N", state.latLabel)
        assertEquals("22.314066° ", state.latValue)
        assertEquals("经度 108° 42' 23\" E", state.lonLabel)
        assertEquals("108.706575° ", state.lonValue)
    }

    @Test
    fun `time is hour prefix plus fix minutes`() {
        // fixTime = 2026-08-03 21:12:13 UTC+8 -> minutes 12
        val fix = 1785820333000L // 2026-08-03 21:12:13 +0800
        val state = roamingStateFromLocation(22.314066, 108.706575, fix, "21:")
        assertEquals("21:12", state.time)
    }

    @Test
    fun `west edge borrows lon letter`() {
        // 经度 0.5 纬度 22.314066: 数字对 02, 西边界 parseInt==0
        val state = stateOf(22.314066, 0.5)
        assertEquals(8, state.loc.length)
        // 左列经度 0->9, 经度字母 J-1=I: IL92
        assertEquals("IL92", state.grids[3])
    }

    @Test
    fun `north edge carries lat letter`() {
        // 纬度 29.5 经度 108.706575: 数字对 49? -> 纬度数字 9 -> 北边界
        val state = stateOf(29.5, 108.706575)
        assertEquals(8, state.loc.length)
        // 上排纬度 +1 -> 0, 纬度字母 +1 (M)
        assertEquals("OM40", state.grids[1])
    }

    @Test
    fun `east edge carries lon letter`() {
        // 经度 119.5 纬度 22.314066: 经度数字 9 -> 东边界
        val state = stateOf(22.314066, 119.5)
        assertEquals(8, state.loc.length)
        // 右上 经度 9->0, 经度字母 O+1=P, 纬度 2+1=3: PL03
        assertEquals("PL03", state.grids[2])
    }

    @Test
    fun `south edge borrows lat letter`() {
        // 纬度 0.5 经度 108.706575: 纬度数字 0 -> 南边界
        val state = stateOf(0.5, 108.706575)
        assertEquals(8, state.loc.length)
        // 下中 纬度 0->9, 纬度字母 J-1=I: OI49
        assertEquals("OI49", state.grids[7])
    }

    @Test
    fun `boundary values wrap like reference`() {
        // (90, 180) -> RR99xx99 (成品区间查表行为)
        val state = stateOf(90.0, 180.0)
        assertEquals("RR99xx99", state.loc)
    }

    @Test
    fun `gps state after fix matches showLocation`() {
        val state = stateOf(22.314066, 108.706575)
        assertEquals(true, state.gpsOn)
        assertEquals(false, state.gpsOff)
        assertEquals(false, state.showSettings)
        assertEquals(false, state.showProgress)
        assertEquals(false, state.showNotice)
        assertEquals(true, state.showDate)
    }
}
