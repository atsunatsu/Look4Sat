package com.rtbishop.look4sat.feature.radar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPassWindowTest {
    @Test
    fun loggingRemainsAllowedAfterOppositeStationLosWhileLocalPassContinues() {
        val minute = 60_000L
        val localAos = 12L * 60L * minute
        val mutualAos = localAos + 2L * minute
        val mutualLos = localAos + 9L * minute
        val localLos = localAos + 12L * minute
        val now = localAos + 10L * minute

        val localWindow = localAos..localLos
        val mutualWindow = mutualAos..mutualLos

        assertTrue(now in localWindow)
        assertTrue(now !in mutualWindow)
        // The log page validates only against the local pass, not mutual visibility.
        assertFalse(isOutsideLogPassWindow(now, localWindow))
    }

    @Test
    fun loggingOutsideLocalPassStillRequiresConfirmation() {
        val localWindow = 12L * 60L * 60_000L..(12L * 60L * 60_000L + 12L * 60_000L)
        val afterLocalLos = localWindow.endInclusive + 1L
        assertTrue(isOutsideLogPassWindow(afterLocalLos, localWindow))
    }

    @Test
    fun missingPassWindowDoesNotBlockLogging() {
        assertFalse(isOutsideLogPassWindow(System.currentTimeMillis(), null))
    }
}
