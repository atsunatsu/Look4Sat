package com.rtbishop.look4sat.feature.mutual

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdStrokeSegmentsTest {
    @Test fun crossingSwitchesFromDashedToSolidAtInterpolatedElevation() {
        val result = thresholdStrokeSegments(
            listOf(Offset(0f, 0f), Offset(10f, 10f)),
            listOf(5.0, 15.0), 10.0
        )
        assertEquals(2, result.size)
        assertFalse(result[0].solid)
        assertTrue(result[1].solid)
        assertEquals(Offset(5f, 5f), result[0].end)
        assertEquals(result[0].end, result[1].start)
    }

    @Test fun eachStationThresholdIsIndependent() {
        val points = listOf(Offset(0f, 0f), Offset(10f, 0f))
        val elevation = listOf(6.0, 8.0)
        assertTrue(thresholdStrokeSegments(points, elevation, 5.0).all { it.solid })
        assertTrue(thresholdStrokeSegments(points, elevation, 10.0).all { !it.solid })
    }

    @Test fun radarDoesNotDrawBelowHorizon() {
        val segments = thresholdStrokeSegments(
            listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(20f, 0f)),
            listOf(-1.0, 5.0, 15.0), 10.0, horizonOnly = true
        )
        assertEquals(2, segments.size)
        assertEquals(Offset(10f, 0f), segments[0].start)
    }
}
