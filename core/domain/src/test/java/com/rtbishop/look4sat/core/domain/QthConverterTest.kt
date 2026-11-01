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
package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.domain.utility.qthToPosition
import org.junit.Test

class QthConverterTest {
    @Test
    fun `Given valid QTH returns correct POS`() {
        // 8-char locator -> centre of the extended square (higher precision)
        var result = qthToPosition("io91VL39FX")
        assert(result?.latitude == 51.5188 && result.longitude == -0.1792)
        result = qthToPosition("gf15vc")
        assert(result?.latitude == -34.8958 && result.longitude == -56.2083)
        // 4-char locator -> centre of the 2deg x 1deg square
        result = qthToPosition("JN58")
        assert(result?.latitude == 48.5 && result.longitude == 11.0)
        // lowercase input is accepted and normalised
        result = qthToPosition("ol63pd")
        assert(result?.latitude == 23.1458 && result.longitude == 113.2917)
    }

    @Test
    fun `Given invalid QTH returns null`() {
        assert(qthToPosition("ZZ00zz") == null)
        assert(qthToPosition("JN5") == null) // odd length
        assert(qthToPosition("JN58Z") == null) // 5 chars
        assert(qthToPosition("JN58ZA") == null) // Z out of A-X range
        assert(qthToPosition("AS00") == null) // latitude fields stop at R
        assert(qthToPosition("SA00") == null) // longitude fields stop at R
        assert(qthToPosition("RS00AB") == null)
        assert(qthToPosition("AA00AB12") != null) // subsquares still use A-X
    }

    @Test
    fun `Given valid POS returns correct QTH`() {
        assert(positionToQth(51.4878, -0.2146) == "IO91VL")
        assert(positionToQth(48.1466, 11.6083) == "JN58TD")
        assert(positionToQth(23.13, 113.26) == "OL63PD")
    }

    @Test
    fun `Given invalid POS returns null`() {
        assert(positionToQth(91.0542, -170.1142) == null)
        assert(positionToQth(89.0542, -240.1142) == null)
        assert(positionToQth(90.0, 100.0) == null)
        assert(positionToQth(23.13, 180.0) == positionToQth(23.13, -180.0))
        assert(positionToQth(23.13, 360.0) == positionToQth(23.13, 0.0))
    }
}
