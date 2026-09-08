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
import org.junit.Assert.assertTrue
import org.junit.Test

class AwardCalculatorTest {

    private fun qso(
        call: String,
        dxcc: Int? = null,
        cqz: Int? = null,
        state: String? = null,
        grid: String = "OL62"
    ) = GridQso(
        call = call, epochMs = 0L, satName = "SO-50", mode = "FM",
        bandUp = "70CM", bandDown = "2M", dxcc = dxcc, cqz = cqz, state = state
    )

    private fun byGrid(vararg qsos: GridQso) =
        qsos.groupBy { "OL62" } // all share one grid for award-math tests

    @Test
    fun `vucc counts distinct grid keys`() {
        val qsos = mapOf(
            "OL62" to listOf(qso("BG7XYZ")),
            "PM95" to listOf(qso("JH0ABC")),
            "OL62" to listOf(qso("BA7AAA"))
        )
        // map literal dedupes keys: OL62/PM95 -> 2 grids
        val progress = AwardCalculator.calculate(qsos).first { it.type == AwardType.VUCC }
        assertEquals(2, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("OL62", "PM95")))
    }

    @Test
    fun `dxcc counts distinct entities from dxcc field`() {
        val all = listOf(
            qso("BG7XYZ", dxcc = 318),
            qso("JH0ABC", dxcc = 339),
            qso("YD1XYZ", dxcc = 327),
            qso("BG7ABC", dxcc = 318)
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.DXCC }
        assertEquals(3, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("318", "339", "327")))
    }

    @Test
    fun `dxcc falls back to prefix when dxcc missing`() {
        val all = listOf(qso("JH0ABC"), qso("BG7XYZ"), qso("VR2XYZ"))
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.DXCC }
        assertEquals(3, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("339", "318", "321")))
    }

    @Test
    fun `wapc counts mainland provinces plus hk mo tw`() {
        val all = listOf(
            qso("BG7XYZ", dxcc = 318, state = "GD"),   // Guangdong
            qso("BD5ABC", dxcc = 318, state = "ZJ"),   // Zhejiang
            qso("BD5ABC", dxcc = 318, state = "ZJ"),   // dup
            qso("VR2X", dxcc = 321),                   // Hong Kong
            qso("XX9A", dxcc = 330),                   // Macao
            qso("BM4X", dxcc = 386)                    // Taiwan
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.WAPC }
        assertEquals(5, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("GD", "ZJ", "HK", "MO", "TW")))
    }

    @Test
    fun `wapc ignores non-china states and missing states`() {
        val all = listOf(
            qso("JH0ABC", dxcc = 339, state = "34"),   // Japan state ignored
            qso("BG7XYZ", dxcc = 318, state = null),   // missing province
            qso("K1ABC", dxcc = 291, state = "HI")     // Hawaii != Hainan
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.WAPC }
        assertEquals(0, progress.count)
        assertTrue(progress.workedKeys.isEmpty())
    }

    @Test
    fun `waja counts japanese prefectures only`() {
        val all = listOf(
            qso("JH0ABC", dxcc = 339, state = "10"),   // Tokyo
            qso("JH0ABC", dxcc = 339, state = "47"),   // Okinawa
            qso("JH0ABC", dxcc = 339, state = "10"),   // dup
            qso("BG7XYZ", dxcc = 318, state = "10"),   // Chinese call, state=10 must NOT count
            qso("JE2XYZ", dxcc = 339, state = null)    // missing
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.WAJA }
        assertEquals(2, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("10", "47")))
    }

    @Test
    fun `waz counts distinct cq zones in range`() {
        val all = listOf(
            qso("BG7XYZ", cqz = 24),
            qso("JH0ABC", cqz = 25),
            qso("YD1XYZ", cqz = 28),
            qso("BG7AAA", cqz = 24),
            qso("BG7BBB", cqz = null),
            qso("BG7CCC", cqz = 0)
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.WAZ }
        assertEquals(3, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("24", "25", "28")))
    }

    @Test
    fun `was counts us states only`() {
        val all = listOf(
            qso("K1ABC", dxcc = 291, state = "CA"),
            qso("W2XYZ", dxcc = 291, state = "NY"),
            qso("N3QRS", dxcc = 291, state = "ca"),    // lowercase normalized
            qso("BG7XYZ", dxcc = 318, state = "CA"),   // not US
            qso("K1AAA", dxcc = 291, state = "XX")     // invalid state
        )
        val progress = AwardCalculator.calculate(byGrid(*all.toTypedArray())).first { it.type == AwardType.WAS }
        assertEquals(2, progress.count)
        assertTrue(progress.workedKeys.containsAll(setOf("CA", "NY")))
    }

    @Test
    fun `all six awards present with correct targets`() {
        val progress = AwardCalculator.calculate(emptyMap())
        assertEquals(6, progress.size)
        assertEquals(100, progress.first { it.type == AwardType.DXCC }.target)
        assertEquals(100, progress.first { it.type == AwardType.VUCC }.target)
        assertEquals(34, progress.first { it.type == AwardType.WAPC }.target)
        assertEquals(47, progress.first { it.type == AwardType.WAJA }.target)
        assertEquals(40, progress.first { it.type == AwardType.WAZ }.target)
        assertEquals(50, progress.first { it.type == AwardType.WAS }.target)
    }
}
