package com.rtbishop.look4sat.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MaidenheadGridOverlayTest {

    @Test
    fun gridLabelsMatchMapZoomScaleFromSixPointFiveToSeven() {
        assertEquals(1f, gridLabelScale(6.0), 0.001f)
        assertEquals(1f, gridLabelScale(6.5), 0.001f)
        assertEquals(1.0718f, gridLabelScale(6.6), 0.001f)
        assertEquals(1.1892f, gridLabelScale(6.75), 0.001f)
        assertEquals(1.4142f, gridLabelScale(7.0), 0.001f)
    }

    @Test
    fun gridLabelScaleRemainsCappedAboveZoomSeven() {
        assertEquals(1.4142f, gridLabelScale(7.5), 0.001f)
        assertEquals(1.4142f, gridLabelScale(12.0), 0.001f)
        assertEquals(1.4142f, gridLabelScale(18.0), 0.001f)
    }

    @Test
    fun gridLabelScaleNeverShrinksBelowBaseSize() {
        assertEquals(1f, gridLabelScale(5.0), 0.001f)
    }
}
