package com.rtbishop.look4sat.feature.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ElevationContourProjectionTest {
    private fun mapCenteredAt(longitude: Double): MapView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return MapView(context).apply {
            setHorizontalMapRepetitionEnabled(true)
            layout(0, 0, 1080, 1920)
            controller.setZoom(5.0)
            controller.setCenter(GeoPoint(0.0, longitude))
        }
    }

    @Test
    fun eastSideLabelsRemainPast180WhenSatelliteIsJustWestOfDateLine() {
        val map = mapCenteredAt(179.0)
        try {
            val satellite = OrbitalPos(
                latitude = 0.0,
                longitude = Math.toRadians(179.0),
                altitude = 550.0
            )
            for (angle in listOf(10.0, 20.0)) {
                val ring = elevationContour(satellite, angle)
                val anchor = ring[elevationContourLabelAnchorIndex(ring)!!]
                val longitude = elevationContourLongitudeInMapWorld(
                    anchor.longitude, map.mapCenter.longitude
                )
                assertTrue("elevation $angle: anchor $longitude should be beyond 180°", longitude > 180.0)

                val projection = map.projection
                val expected = elevationContourToPixels(
                    projection, GeoPoint(anchor.latitude, longitude)
                )
                val dateLine = elevationContourToPixels(
                    projection, GeoPoint(anchor.latitude, 180.0)
                )
                assertTrue(
                    "elevation $angle: label must be east of the dateline rather than stacked on it",
                    expected.x > dateLine.x
                )
                assertEquals(
                    projection.getLongPixelXFromLongitude(longitude).toInt(),
                    expected.x
                )
            }
        } finally {
            map.onDetach()
        }
    }

    @Test
    fun labelsRemainWestOfNegative180WhenMapCenterMovesAcrossDateLine() {
        val map = mapCenteredAt(-179.0)
        try {
            val labelLon = elevationContourLongitudeInMapWorld(179.0, map.mapCenter.longitude)
            assertEquals(-181.0, labelLon, 0.0)
            val projection = map.projection
            val label = elevationContourToPixels(projection, GeoPoint(0.0, labelLon))
            val dateLine = elevationContourToPixels(projection, GeoPoint(0.0, -180.0))
            assertTrue("label west of −180° must remain left of that meridian", label.x < dateLine.x)
        } finally {
            map.onDetach()
        }
    }
}
