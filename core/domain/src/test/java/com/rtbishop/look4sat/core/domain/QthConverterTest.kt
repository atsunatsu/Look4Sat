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
package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.domain.utility.qthNeighbors
import com.rtbishop.look4sat.core.domain.utility.qthToPosition
import com.rtbishop.look4sat.core.domain.utility.qthToSquare
import org.junit.Test

class QthConverterTest {

    @Test
    fun `Given valid QTH returns correct POS`() {
        var result = qthToPosition("io91VL39FX")
        assert(result?.latitude == 51.499913 && result.longitude == -0.22309)
        result = qthToPosition("gf15vc")
        assert(result?.latitude == -34.895833 && result.longitude == -56.208333)
        // 8-char locators: finer 30" x 15" cell center
        result = qthToPosition("io91vl47")
        assert(result?.latitude == 51.489583 && result.longitude == -0.2125)
        result = qthToPosition("jn58td25")
        assert(result?.latitude == 48.147917 && result.longitude == 11.604167)
    }

    @Test
    fun `Given invalid QTH returns null`() {
        assert(qthToPosition("ZZ00zz") == null)
        assert(qthToPosition("JN58") == null)
        assert(qthToPosition("io9") == null)
        assert(qthToPosition("IO91VL7") == null)
        assert(qthToPosition("IO91VL4X") == null)
    }

    @Test
    fun `Given valid POS returns correct QTH`() {
        // default precision is 8 chars
        assert(positionToQth(51.4878, -0.2146) == "IO91vl47")
        assert(positionToQth(48.1466, 11.6083) == "JN58td25")
        // 6-char precision still available for backwards compatibility
        assert(positionToQth(51.4878, -0.2146, 6) == "IO91vl")
        assert(positionToQth(48.1466, 11.6083, 6) == "JN58td")
        // 10-char precision
        assert(positionToQth(51.4878, -0.2146, 10) == "IO91vl47fb")
        assert(positionToQth(48.1466, 11.6083, 10) == "JN58td25xe")
    }

    @Test
    fun `Given invalid POS returns null`() {
        assert(positionToQth(91.0542, -170.1142) == null)
        assert(positionToQth(89.0542, -240.1142) == null)
    }

    @Test
    fun `Given boundary POS stays in valid grid`() {
        // antipodal / edge cases must not overflow the A-R / 0-9 / a-x alphabet
        assert(positionToQth(-90.0, -180.0, 8) == "AA00aa00")
        assert(positionToQth(90.0, 180.0, 8) == "RR00aa00")
        assert(positionToQth(0.0, 0.0, 8) == "JJ00aa00")
        // roundtrip stability: 8-char roundtrip is stable across a sample of positions
        val positions = listOf(
            Pair(51.4878, -0.2146), Pair(48.1466, 11.6083), Pair(-33.8688, 151.2093),
            Pair(39.9042, 116.4074), Pair(35.6895, 139.6917), Pair(41.714, -72.727)
        )
        positions.forEach { (lat, lon) ->
            val qth = positionToQth(lat, lon, 8)
            val pos = qthToPosition(qth!!)
            val qth2 = positionToQth(pos!!.latitude, pos.longitude, 8)
            assert(qth == qth2) { "Roundtrip failed for ($lat, $lon): $qth -> $qth2" }
        }
    }

    @Test
    fun `Given square returns correct 3x3 neighbors`() {
        // Reference grid from the QTH定位器 screenshot: OL42
        val neighbors = qthNeighbors("OL42")
        assert(neighbors == listOf(
            "OL33", "OL43", "OL53",
            "OL32", "OL42", "OL52",
            "OL31", "OL41", "OL51"
        )) { "OL42 grid mismatch: $neighbors" }
        // Center cell must be the input itself
        assert(neighbors[4] == "OL42")
        // 9 cells, all distinct
        assert(neighbors.size == 9 && neighbors.toSet().size == 9)
    }

    @Test
    fun `Given boundary square wraps fields correctly`() {
        // South-west corner: AA00 neighbors wrap to RR99 / RA90 etc.
        val sw = qthNeighbors("AA00")
        assert(sw.size == 9 && sw.toSet().size == 9)
        assert(sw[0] == "RA91" && sw[4] == "AA00" && sw[6] == "RR99" && sw[8] == "AR19")
        // North-east corner: RR99 wraps to AA00
        val ne = qthNeighbors("RR99")
        assert(ne.size == 9 && ne.toSet().size == 9)
        assert(ne[0] == "RA80" && ne[4] == "RR99" && ne[8] == "AR08")
        // Field boundary: IO91's east neighbors cross into J field
        val london = qthNeighbors("IO91")
        assert(london[2] == "JO02" && london[5] == "JO01")
    }

    @Test
    fun `Given full locator returns square part`() {
        assert(qthToSquare("OL42ih45") == "OL42")
        assert(qthToSquare("io91VL39FX") == "IO91")
        assert(qthToSquare("JN58") == "JN58")
        assert(qthToSquare("garbage!!") == "----")
    }
}
