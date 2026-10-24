package com.rtbishop.look4sat.feature.mutual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadarMatchSelectionTest {
    @Test
    fun selectsOnlySimultaneousVisibilityInsideDisplayedRadarPass() {
        val window = radarMutualVisibilityWindow(100_000L, 300_000L) { time ->
            time in 180_000L..220_000L
        }
        assertEquals(180_000L to 220_000L, window)
    }

    @Test
    fun doesNotSwitchToUpcomingPassOnTheSameSatellite() {
        val window = radarMutualVisibilityWindow(100_000L, 200_000L) { time ->
            time in 250_000L..300_000L
        }
        assertNull(window)
    }

    @Test
    fun clipsCommonVisibilityToRadarPassBounds() {
        val window = radarMutualVisibilityWindow(100_000L, 150_000L) { time ->
            time >= 120_000L
        }
        assertEquals(120_000L to 150_000L, window)
    }

    @Test
    fun returnsNullWhenThereIsNoCommonVisibility() {
        assertNull(radarMutualVisibilityWindow(100_000L, 200_000L) { false })
        assertNull(radarMutualVisibilityWindow(200_000L, 100_000L) { true })
    }
}
