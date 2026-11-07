package com.rtbishop.look4sat.feature.radar

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
}
