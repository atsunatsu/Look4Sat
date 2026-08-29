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
    }
}
