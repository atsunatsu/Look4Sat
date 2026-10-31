package com.rtbishop.look4sat.core.data.repository

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SatelliteTypeSettingsMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("satellite-type-migration-tests", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    private fun repo() = SettingsRepo(
        context.getSystemService(LocationManager::class.java), prefs, "test"
    )

    @Test
    fun newInstallDefaultsToAllWithoutSelectingEverySatellite() {
        val settings = repo()
        assertEquals(listOf("All"), settings.selectedTypes.value)
        assertEquals(emptyList<Int>(), settings.selectedIds.value)
        assertEquals("All", prefs.getString("selectedTypes", null))
    }

    @Test
    fun legacyAmateurSelectionBecomesAllButKeepsSelectedSatelliteIds() {
        prefs.edit()
            .putString("selectedTypes", "Amateur")
            .putString("typeAmateur", "25544,40931")
            .putString("selectedIds", "25544,40931")
            .commit()

        val settings = repo()
        assertEquals(listOf("All"), settings.selectedTypes.value)
        assertEquals("All", prefs.getString("selectedTypes", null))
        assertFalse(prefs.contains("typeAmateur"))
        assertEquals(listOf(25544, 40931), settings.selectedIds.value)
        assertEquals("25544,40931", prefs.getString("selectedIds", null))
    }

    @Test
    fun mixedLegacyAndSupportedSourceTypesKeepSupportedTypes() {
        prefs.edit().putString("selectedTypes", "Amateur,Amsat,SatNOGS").commit()
        val settings = repo()
        assertEquals(listOf("Amsat", "SatNOGS"), settings.selectedTypes.value)
        assertEquals("Amsat,SatNOGS", prefs.getString("selectedTypes", null))
    }

    @Test
    fun allOverridesNarrowerFiltersDuringMigrationAndFutureSaves() {
        prefs.edit().putString("selectedTypes", "Amateur,All,Amsat").commit()
        val settings = repo()
        assertEquals(listOf("All"), settings.selectedTypes.value)
        settings.setSelectedTypes(listOf("All", "AMSAT Live FM"))
        assertEquals(listOf("All"), settings.selectedTypes.value)
    }

    @Test
    fun explicitlyClearedTypesRemainEmptyAcrossRestarts() {
        val settings = repo()
        settings.setSelectedTypes(emptyList())
        assertEquals(emptyList<String>(), repo().selectedTypes.value)
        assertEquals("", prefs.getString("selectedTypes", null))
    }

    @Test
    fun invalidTypeCannotBeWrittenBackFromOldSelectionDialog() {
        val settings = repo()
        settings.setSelectedTypes(listOf("Amateur"))
        assertEquals(listOf("All"), settings.selectedTypes.value)
        assertEquals("All", prefs.getString("selectedTypes", null))
    }
}
