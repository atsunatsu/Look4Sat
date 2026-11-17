package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WavelogConnectionTest {
    private val home = WavelogStationInfo("1", "Home", "BA7OPF", "OL62", true)
    private val rover = WavelogStationInfo("2", "Rover", "BA7OPF/P", "OL61", true)

    @Test
    fun successfulProbeSavesNormalizedCredentialsAndAutoSelectsOnlyStation() {
        val result = WavelogUploadSettings().withVerifiedWavelogConnection(
            " https://wave.example/app/ ", " secret ", listOf(home)
        )
        assertTrue(result.isConfigured)
        assertTrue(result.isReady)
        assertEquals("https://wave.example/app", result.url)
        assertEquals("secret", result.apiKey)
        assertEquals(home.id, result.stationId)
    }

    @Test
    fun multipleStationsSaveCredentialsButRequireSelection() {
        val result = WavelogUploadSettings().withVerifiedWavelogConnection(
            "https://wave.example", "key", listOf(home, rover)
        )
        assertTrue(result.isConfigured)
        assertFalse(result.isReady)
        assertEquals("", result.stationId)
    }

    @Test
    fun refreshKeepsVerifiedSelectionAndUpdatesMetadata() {
        val old = WavelogUploadSettings(url = "https://wave.example/", apiKey = "old-key", stationId = "2")
        val result = old.withVerifiedWavelogConnection(
            "https://wave.example", "new-key", listOf(home, rover.copy(grid = "OL63"))
        )
        assertEquals("2", result.stationId)
        assertEquals("OL63", result.stationGrid)
        assertEquals("new-key", result.apiKey)
    }

    @Test
    fun aDifferentServerNeverReusesTheOldStationId() {
        val old = WavelogUploadSettings(url = "https://old.example", apiKey = "key", stationId = "2")
        val result = old.withVerifiedWavelogConnection(
            "https://new.example", "key", listOf(home, rover)
        )
        assertEquals("", result.stationId)
        assertEquals("", result.stationCallsign)
    }

    @Test
    fun unverifiedCredentialsDropOldStationAndRequireNewProbe() {
        val old = WavelogUploadSettings(
            url = "https://old.example", apiKey = "key",
            stationId = "1", stationName = "Home", stationCallsign = "BA7OPF", stationGrid = "OL62"
        )
        val result = old.withUnverifiedWavelogConnection("https://new.example", "another-key")
        assertTrue(result.isConfigured)
        assertFalse(result.isReady)
        assertEquals("", result.stationId)
        assertEquals("", result.stationGrid)
        assertTrue(old.hasWavelogConnection("https://old.example/", " key "))
    }
}
