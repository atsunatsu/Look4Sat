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
    fun recentSwitchOnShowsLatestForAllActivatedGrids() {
        val bulk = mapOf(
            "OL62" to "DF2ET",
            "OL63" to "DL1EGL",
            "OL61" to "BH6RJD"
        )
        val mark = com.rtbishop.look4sat.core.domain.model.MarkedStation("LOCAL", 1L)
        assertEquals("DF2ET", gridFirstCallLabel(
            "OL62", setOf("OL62"), mapOf("OL62" to "BA7OPF"), emptyMap(), bulk,
            showRecentActiveCalls = true, gridMasterActivityEnabled = true
        ))
        assertEquals("DL1EGL", gridFirstCallLabel(
            "OL63", emptySet(), emptyMap(), mapOf("OL63" to listOf(mark)), bulk,
            showRecentActiveCalls = true, gridMasterActivityEnabled = true
        ))
        assertEquals("BH6RJD", gridFirstCallLabel(
            "OL61", emptySet(), emptyMap(), emptyMap(), bulk,
            showRecentActiveCalls = true, gridMasterActivityEnabled = true
        ))
    }

    @Test
    fun recentSwitchOffRestoresWorkedCallButMarkedStillShowsRecent() {
        val bulk = mapOf("OL62" to "DF2ET", "OL63" to "DL1EGL", "OL61" to "BH6RJD")
        val mark = com.rtbishop.look4sat.core.domain.model.MarkedStation("LOCAL", 1L)
        assertEquals("BA7OPF", gridFirstCallLabel(
            "OL62", setOf("OL62"), mapOf("OL62" to "BA7OPF"), emptyMap(), bulk,
            showRecentActiveCalls = false, gridMasterActivityEnabled = true
        ))
        assertEquals("DL1EGL", gridFirstCallLabel(
            "OL63", emptySet(), emptyMap(), mapOf("OL63" to listOf(mark)), bulk,
            showRecentActiveCalls = false, gridMasterActivityEnabled = true
        ))
        assertEquals(null, gridFirstCallLabel(
            "OL61", emptySet(), emptyMap(), emptyMap(), bulk,
            showRecentActiveCalls = false, gridMasterActivityEnabled = true
        ))
    }

    @Test
    fun switchingOffMostWantedRestoresLocalWorkedAndMarkedLabels() {
        val bulk = mapOf("OL62" to "DF2ET", "OL63" to "DL1EGL")
        val mark = com.rtbishop.look4sat.core.domain.model.MarkedStation("LOCAL", 1L)
        assertEquals("BA7OPF", gridFirstCallLabel(
            "OL62", setOf("OL62"), mapOf("OL62" to "BA7OPF"), emptyMap(), bulk,
            gridMasterActivityEnabled = false
        ))
        assertEquals("LOCAL", gridFirstCallLabel(
            "OL63", emptySet(), emptyMap(), mapOf("OL63" to listOf(mark)), bulk,
            gridMasterActivityEnabled = false
        ))
    }

    @Test
    fun activityFilterHidesUnworkedRecentCallsButNotWorkedOrMarked() {
        val bulk = mapOf("OL62" to "DF2ET", "OL63" to "DL1EGL", "OL61" to "BH6RJD")
        val mark = com.rtbishop.look4sat.core.domain.model.MarkedStation("LOCAL", 1L)
        assertEquals(null, gridFirstCallLabel(
            "OL61", emptySet(), emptyMap(), emptyMap(), bulk,
            gridMasterActivityEnabled = true, unworkedInActivityRange = false
        ))
        assertEquals("DF2ET", gridFirstCallLabel(
            "OL62", setOf("OL62"), mapOf("OL62" to "BA7OPF"), emptyMap(), bulk,
            gridMasterActivityEnabled = true, unworkedInActivityRange = false
        ))
        assertEquals("DL1EGL", gridFirstCallLabel(
            "OL63", emptySet(), emptyMap(), mapOf("OL63" to listOf(mark)), bulk,
            gridMasterActivityEnabled = true, unworkedInActivityRange = false
        ))
    }

    @Test
    fun unavailableBulkDataKeepsLocalWorkedCallButNeverShowsMarkedTargetInActivityMode() {
        val mark = com.rtbishop.look4sat.core.domain.model.MarkedStation("LOCAL", 1L)
        assertEquals("BA7OPF", gridFirstCallLabel(
            "OL62", setOf("OL62"), mapOf("OL62" to "BA7OPF"), emptyMap(),
            gridMasterActivityEnabled = true
        ))
        assertEquals(null, gridFirstCallLabel(
            "OL63", emptySet(), emptyMap(), mapOf("OL63" to listOf(mark)),
            gridMasterActivityEnabled = true
        ))
    }

    @Test
    fun unworkedUnmarkedGridHasNoCallsignLabel() {
        assertEquals(null, gridFirstCallLabel(
            "OL61", emptySet(), mapOf("OL62" to "BA7OPF"), emptyMap()
        ))
    }
}
