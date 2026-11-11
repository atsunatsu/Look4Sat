package com.rtbishop.look4sat.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MaidenheadGridOverlayTest {

    @Test
    fun offlineGridLabelsGrowStartingAtZoomSixPointFive() {
        assertEquals(1f, gridLabelScale(6.0, 6.5, 7.0), 0.001f)
        assertEquals(1f, gridLabelScale(6.5, 6.5, 7.0), 0.001f)
        assertEquals(1.25f, gridLabelScale(6.75, 6.5, 7.0), 0.001f)
        assertEquals(1.5f, gridLabelScale(7.0, 6.5, 7.0), 0.001f)
    }

    @Test
    fun tiandituGridLabelsGrowStartingAtZoomSixPointFive() {
        assertEquals(1f, gridLabelScale(6.0, 6.5, 18.0), 0.001f)
        assertEquals(1f, gridLabelScale(6.5, 6.5, 18.0), 0.001f)
        assertEquals(1.25f, gridLabelScale(12.25, 6.5, 18.0), 0.001f)
        assertEquals(1.5f, gridLabelScale(18.0, 6.5, 18.0), 0.001f)
    }

    @Test
    fun gridLabelsNeverShrinkBelowBaseSizeAndHandleInvalidRange() {
        assertEquals(1f, gridLabelScale(5.0, 6.5, 7.0), 0.001f)
        assertEquals(1f, gridLabelScale(8.0, 6.5, 6.5), 0.001f)
    }
}
