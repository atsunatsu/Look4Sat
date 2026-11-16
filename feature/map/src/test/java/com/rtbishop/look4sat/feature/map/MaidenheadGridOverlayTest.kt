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

    @Test
    fun workedGridShowsOnlyLocalFirstQso() {
        assertEquals("BA7OPF", gridFirstCallLabel(
            "OL62", setOf("OL62"), mapOf("OL62" to "BA7OPF"), emptyMap()
        ))
    }

    @Test
    fun missingLocalFirstCallNeverFallsBackToGridMaster() {
        assertEquals(null, gridFirstCallLabel(
            "OL62", setOf("OL62"), emptyMap(), emptyMap()
        ))
    }

    @Test
    fun markedUnworkedGridShowsPinnedLocalTarget() {
        val mark = com.rtbishop.look4sat.core.domain.model.MarkedStation("BH6RJD", 1L)
        assertEquals("BH6RJD", gridFirstCallLabel(
            "OL63", emptySet(), emptyMap(), mapOf("OL63" to listOf(mark))
        ))
    }

    @Test
    fun unworkedUnmarkedGridHasNoCallsignLabel() {
        assertEquals(null, gridFirstCallLabel(
            "OL61", emptySet(), mapOf("OL62" to "BA7OPF"), emptyMap()
        ))
    }
}
