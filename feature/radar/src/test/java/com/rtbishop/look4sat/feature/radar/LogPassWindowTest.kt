package com.rtbishop.look4sat.feature.radar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPassWindowTest {
    @Test
    fun qsoTimeAfterOppositeLosIsOutsideMutualWindowEvenWhileLocalPassContinues() {
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
        // Logging is checked against the mutual QSO window, not the local radar window.
        assertTrue(isOutsideLogPassWindow(now, mutualWindow))
        assertFalse(isOutsideLogPassWindow(localAos + 5L * minute, mutualWindow))
    }

    @Test
    fun missingPassWindowDoesNotBlockLogging() {
        assertFalse(isOutsideLogPassWindow(System.currentTimeMillis(), null))
    }
}
