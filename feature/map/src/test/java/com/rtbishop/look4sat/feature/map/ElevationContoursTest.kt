package com.rtbishop.look4sat.feature.map

import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

class ElevationContoursTest {
    private val position = OrbitalPos(
        latitude = Math.toRadians(24.0),
        longitude = Math.toRadians(113.0),
        altitude = 550.0
    )

    @Test
    fun zeroElevationMatchesExistingFootprint() {
        val contour = elevationContourPoints(position, 0.0)
        val footprint = position.getRangeCircle()
        assertEquals(361, contour.size)
        contour.forEachIndexed { index, point ->
            assertEquals(footprint[index].latitude, point.latitude, 1e-8)
            assertEquals(footprint[index].longitude, point.longitude, 1e-8)
        }
    }

    @Test
    fun increasingElevationProducesConcentricSmallerCircles() {
        val zero = elevationContourPoints(position, 0.0)
        val twenty = elevationContourPoints(position, 20.0)
        val fifty = elevationContourPoints(position, 50.0)
        for (index in listOf(0, 40, 120, 200, 300)) {
            val r0 = centralAngle(zero[index])
            val r20 = centralAngle(twenty[index])
            val r50 = centralAngle(fifty[index])
            assertTrue(r0 > r20 && r20 > r50)
            assertEquals(r20, centralAngle(twenty[(index + 180) % 360]), 1e-8)
        }
    }

    @Test
    fun crossingDateLineIsSplitIntoDrawableArcs() {
        val dateLinePosition = position.copy(longitude = Math.toRadians(179.0))
        val contour = elevationContourPoints(dateLinePosition, 10.0)
        val arcs = splitGroundTrackAtDateLine(contour)
        assertTrue(arcs.size >= 2)
        assertTrue(arcs.all { arc ->
            arc.zipWithNext().all { (a, b) -> abs(a.longitude - b.longitude) <= 180.0 }
        })
    }

    @Test
    fun invalidAnglesDoNotGenerateContours() {
        assertTrue(elevationContourPoints(position, -1.0).isEmpty())
        assertTrue(elevationContourPoints(position, 91.0).isEmpty())
        assertTrue(elevationContourPoints(position, Double.NaN).isEmpty())
    }

    private fun centralAngle(point: com.rtbishop.look4sat.core.domain.predict.GeoPos): Double {
        val lat = Math.toRadians(point.latitude)
        val deltaLon = Math.toRadians(point.longitude) - position.longitude
        return acos((sin(position.latitude) * sin(lat) +
            cos(position.latitude) * cos(lat) * cos(deltaLon)).coerceIn(-1.0, 1.0))
    }
}
