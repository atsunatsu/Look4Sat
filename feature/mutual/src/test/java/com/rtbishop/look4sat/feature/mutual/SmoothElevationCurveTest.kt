package com.rtbishop.look4sat.feature.mutual

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothElevationCurveTest {
    @Test fun preservesEndpointsWhileRestoringSmoothInterpolation() {
        val pts = listOf(Offset(0f, 100f), Offset(10f, 0f), Offset(20f, 100f))
        val elev = listOf(0.0, 90.0, 0.0)
        val (points, elevations) = smoothElevationCurve(pts, elev)
        assertEquals(17, points.size)
        assertEquals(17, elevations.size)
        assertEquals(pts.first(), points.first())
        assertEquals(pts.last(), points.last())
        assertEquals(elev.first(), elevations.first(), 0.000001)
        assertEquals(elev.last(), elevations.last(), 0.000001)
        assertTrue(points[2].y != 75f)
        assertTrue(thresholdStrokeSegments(points, elevations, 10.0).any { it.solid })
        assertTrue(thresholdStrokeSegments(points, elevations, 10.0).any { !it.solid })
    }
}
