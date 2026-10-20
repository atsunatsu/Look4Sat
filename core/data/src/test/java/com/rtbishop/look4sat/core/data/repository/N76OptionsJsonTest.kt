package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.N76ControlOptions
import com.rtbishop.look4sat.core.domain.model.N76FirmwareMode
import com.rtbishop.look4sat.core.domain.model.N76Power
import org.junit.Assert.*
import org.junit.Test

class N76OptionsJsonTest {
    @Test fun everyCatOptionSurvivesPreferenceRoundTrip() {
        val options = N76ControlOptions(firmwareMode = N76FirmwareMode.EXACT, sendSatelliteInfo = false,
            pollMillis = 750, power = N76Power.LOW, openSquelchOnTrack = true,
            forceRxCtcss = true, rxCtcssHz = 88.5, forceTxCtcss = true, txCtcssHz = 67.0,
            speakerMonitor = false, recordRadio = true, recordMicrophone = true, recordOnTrack = true,
            outputFolderUri = "content://recordings/tree/test", inputDeviceKey = "phone-mic", micGainDb = 18)
        assertEquals(options, n76OptionsFromJson(options.toPreferenceJson()))
    }
    @Test fun defaultsAndExplicitToneOffRemainDistinctFromFollowingTransponder() {
        assertEquals(N76ControlOptions(), n76OptionsFromJson(null))
        assertEquals(N76ControlOptions(), n76OptionsFromJson("invalid"))
        val options = N76ControlOptions(forceTxCtcss = true, txCtcssHz = null)
        val restored = n76OptionsFromJson(options.toPreferenceJson())
        assertTrue(restored.forceTxCtcss)
        assertNull(restored.txCtcssHz)
        assertEquals(N76Power.UNCHANGED, n76OptionsFromJson("{\"power\":\"future\"}").power)
    }
}
