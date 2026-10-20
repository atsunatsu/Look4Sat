package com.rtbishop.look4sat.core.domain.model

import org.junit.Assert.*
import org.junit.Test

class N76ControlOptionsTest {
    @Test fun defaultsDoNotChangePowerStartRecordingOrOverrideTones() {
        val options = N76ControlOptions()
        assertEquals(N76Power.UNCHANGED, options.power)
        assertFalse(options.recordOnTrack)
        assertFalse(options.recordRadio)
        assertFalse(options.recordMicrophone)
        assertFalse(options.forceRxCtcss)
        assertFalse(options.forceTxCtcss)
        assertEquals(1000L, options.pollMillis)
    }
    @Test fun firmwareOverrideAndNormalizationArePredictable() {
        assertFalse(N76ControlOptions().satelliteFirmware(136))
        assertTrue(N76ControlOptions().satelliteFirmware(137))
        assertTrue(N76ControlOptions(firmwareMode = N76FirmwareMode.SATELLITE).satelliteFirmware(1))
        assertFalse(N76ControlOptions(firmwareMode = N76FirmwareMode.EXACT).satelliteFirmware(200))
        val value = N76ControlOptions(pollMillis = 200_000, micGainDb = 30, rxCtcssHz = Double.NaN, txCtcssHz = 999.0).normalized()
        assertEquals(15000L, value.pollMillis)
        assertEquals(24, value.micGainDb)
        assertNull(value.rxCtcssHz); assertNull(value.txCtcssHz)
    }
}
