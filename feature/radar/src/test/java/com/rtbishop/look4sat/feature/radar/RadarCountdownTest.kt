package com.rtbishop.look4sat.feature.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarCountdownTest {
    @Test
    fun upcomingChangesToInProgressAtAos() {
        val aos = 120_000L
        val los = 660_000L
        assertEquals(RadarPassPhase.UPCOMING, radarCountdown(false, aos, los, 119_000L).phase)
        assertEquals("00:00:01", radarCountdown(false, aos, los, 119_000L).time)
        assertEquals(RadarPassPhase.IN_PROGRESS, radarCountdown(false, aos, los, aos).phase)
        assertEquals("00:09:00", radarCountdown(false, aos, los, aos).time)
    }

    @Test
    fun completedPassIsNeverReportedAsStillInProgress() {
        val aos = 120_000L
        val los = 660_000L
        assertEquals(RadarPassPhase.IN_PROGRESS, radarCountdown(false, aos, los, los - 1L).phase)
        val atLos = radarCountdown(false, aos, los, los)
        assertEquals(RadarPassPhase.ENDED, atLos.phase)
        assertEquals("00:00:00", atLos.time)
        assertTrue(atLos.isEnded)
        assertFalse(atLos.isAos)
        assertEquals(RadarPassPhase.ENDED, radarCountdown(false, aos, los, los + 60_000L).phase)
    }

    @Test
    fun matchedRadarTimerFollowsLocalVisibilityRatherThanMutualOverlap() {
        // The match begins after our AOS and ends before our LOS. Radar track
        // and timer must still agree that our pass is in progress at 500 s.
        val mutualStart = 240_000L
        val mutualEnd = 480_000L
        val (localAos, localLos) = expandMatchedRadarLocalVisibilityWindow(
            mutualStart, mutualEnd
        ) { it >= 120_000L && it < 660_000L }
        val timer = radarCountdown(false, localAos, localLos, 500_000L)
        assertEquals(RadarPassPhase.IN_PROGRESS, timer.phase)
        assertTrue(localAos < mutualStart)
        assertTrue(localLos > mutualEnd)
    }

    @Test
    fun deepSpaceDoesNotClaimAosOrEnded() {
        val timer = radarCountdown(true, 0L, 100L, 10_000L)
        assertEquals(RadarPassPhase.DEEP_SPACE, timer.phase)
        assertFalse(timer.isAos)
        assertFalse(timer.isEnded)
        assertEquals("00:00:00", timer.time)
    }
}
