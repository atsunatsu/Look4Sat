package com.rtbishop.look4sat.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PassModesSettingsTest {
    @Test fun unsetAndSavedEmptyModeSelectionsDoNotFilterAnySatellite() {
        assertEquals(emptyList<String>(), parsePassModesFromPreferences(null))
        assertEquals(emptyList<String>(), parsePassModesFromPreferences(""))
        assertEquals(emptyList<String>(), parsePassModesFromPreferences(" , , "))
    }

    @Test fun selectedModeFiltersRemainActive() {
        assertEquals(listOf("FM", "USB"), parsePassModesFromPreferences("USB,FM"))
        assertEquals(listOf("FM", "USB"), parsePassModesFromPreferences(" FM, , USB,FM"))
    }
}
