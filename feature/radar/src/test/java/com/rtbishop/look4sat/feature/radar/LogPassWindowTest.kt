package com.rtbishop.look4sat.feature.radar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPassWindowTest {
    @Test
    fun loggingRemainsInsideLocalPassAfterOppositeStationHasSet() {
        val minute = 60_000L
        val localAos = 12L * 60L * minute
        val mutualAos = 12L * 60L * minute + 2L * minute
        val mutualLos = 12L * 60L * minute + 9L * minute
        val localLos = 12L * 60L * minute + 12L * minute
        val now = 12L * 60L * minute + 10L * minute

        val localWindow = localAos..localLos
        val mutualWindow = mutualAos..mutualLos

        // Opposite station is already below the horizon, but our pass is active.
        assertTrue(now !in mutualWindow)
        assertTrue(now in localWindow)
        assertFalse(isOutsideLogPassWindow(now, localWindow))
    }

    @Test
    fun loggingOutsideOurOwnPassStillRequiresConfirmation() {
        val localWindow = 12L * 60L * 60_000L..(12L * 60L * 60_000L + 12L * 60_000L)
        val afterLocalLos = localWindow.endInclusive + 1L
        assertTrue(isOutsideLogPassWindow(afterLocalLos, localWindow))
    }

    @Test
    fun missingPassWindowDoesNotBlockLogging() {
        assertFalse(isOutsideLogPassWindow(System.currentTimeMillis(), null))
    }
}
