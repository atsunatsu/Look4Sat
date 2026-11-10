package com.rtbishop.look4sat.feature.radar

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.MutualPassData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MatchedRadarTrackWindowTest {
    @Test
    fun extendsLocalRadarTrackBeforeAndAfterMutualVisibility() {
        val localAos = 120_000L
        val localLos = 660_000L
        val (start, end) = expandMatchedRadarLocalVisibilityWindow(
            mutualStart = 240_000L,
            mutualEnd = 480_000L
        ) { it in localAos until localLos }

        assertTrue("local AOS should be recovered", abs(start - localAos) <= 1_000L)
        assertTrue("local LOS should be recovered", abs(end - localLos) <= 1_000L)
    }

    @Test
    fun keepsAlreadyCompleteLocalPass() {
        val (start, end) = expandMatchedRadarLocalVisibilityWindow(
            mutualStart = 120_000L,
            mutualEnd = 660_000L
        ) { it in 120_000L..660_000L }

        assertEquals(120_000L, start)
        assertEquals(660_000L, end)
    }

    @Test
    fun preservesWindowIfStationPositionNoLongerMatches() {
        val mutualStart = 240_000L
        val mutualEnd = 480_000L
        assertEquals(
            mutualStart to mutualEnd,
            expandMatchedRadarLocalVisibilityWindow(mutualStart, mutualEnd) { false }
        )
    }

    @Test
    fun rejectsEmptyWindow() {
        assertEquals(
            240_000L to 120_000L,
            expandMatchedRadarLocalVisibilityWindow(240_000L, 120_000L) { true }
        )
    }

    @Test
    fun oppositeStationDotDisappearsAtItsOwnLosNotAtMutualEnd() {
        // The local station sets earlier, so mutual visibility ends at 480s,
        // but the opposite station can still see the satellite until 660s.
        val (remoteAos, remoteLos) = expandMatchedRadarLocalVisibilityWindow(
            mutualStart = 240_000L,
            mutualEnd = 480_000L
        ) { it in 120_000L until 660_000L }

        val remoteWindow = remoteAos to remoteLos
        assertTrue(isWithinMatchedRadarWindow(480_000L, remoteWindow))
        assertTrue(isWithinMatchedRadarWindow(600_000L, remoteWindow))
        assertTrue(!isWithinMatchedRadarWindow(660_000L, remoteWindow))
        assertTrue(!isWithinMatchedRadarWindow(720_000L, remoteWindow))
    }

    @Test
    fun matchedRadarUsesTheStationAThatWasSearched() {
        val actualStation = GeoPos(22.2, 113.5)
        val plannedStation = matchedRadarObserverPosition(
            MutualPassData(stationALatitude = 35.0, stationALongitude = 139.0), actualStation
        )
        assertEquals(35.0, plannedStation.latitude, 0.0)
        assertEquals(139.0, plannedStation.longitude, 0.0)

        val invalid = matchedRadarObserverPosition(
            MutualPassData(stationALatitude = 95.0, stationALongitude = 139.0), actualStation
        )
        assertEquals(actualStation, invalid)
        assertEquals(actualStation, matchedRadarObserverPosition(MutualPassData(), actualStation))
    }

    @Test
    fun oppositeMarkerNeverUsesUnknownOrStaleWindow() {
        assertTrue(!isWithinMatchedRadarWindow(480_000L, null))
        assertTrue(!isWithinMatchedRadarWindow(119_000L, 120_000L to 660_000L))
    }
}
