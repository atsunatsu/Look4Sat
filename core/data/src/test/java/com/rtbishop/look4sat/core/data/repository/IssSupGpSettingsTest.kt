/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 Look4Sat contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.core.data.repository

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.source.Sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IssSupGpSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("iss-supgp-settings-test", Context.MODE_PRIVATE)

    @Before
    fun clearPrefs() {
        prefs.edit().clear().commit()
    }

    private fun repo() = SettingsRepo(
        context.getSystemService(LocationManager::class.java), prefs, "test"
    )

    @Test
    fun `fresh install shows SupGP in satellite sources`() {
        val settings = repo().dataSourcesSettings.value
        val index = settings.satelliteUrls.indexOf(Sources.ISS_SUPGP_URL)
        assertTrue(index >= 0)
        assertTrue(settings.isSatelliteEnabled(index))
    }

    @Test
    fun `migration preserves existing disabled sources and appends once`() {
        val oldUrl = "https://example.com/old.txt"
        val otherUrl = "https://example.com/other.txt"
        prefs.edit().putString("satelliteUrls", "$oldUrl\n$otherUrl")
            .putString("satelliteEnabled", "false,true")
            .putBoolean("satnogsTleSourceMigration", true)
            .putBoolean("autoTleSourceMigration", true)
            .commit()

        val first = repo().dataSourcesSettings.value
        assertEquals(listOf(oldUrl, otherUrl, Sources.ISS_SUPGP_URL), first.satelliteUrls)
        assertEquals(listOf(false, true, true), first.satelliteEnabled)
        val second = repo().dataSourcesSettings.value
        assertEquals(1, second.satelliteUrls.count { Sources.isIssSupGpUrl(it) })
        assertFalse(second.isSatelliteEnabled(0))
    }

    @Test
    fun `independent SupGP check timestamp persists without changing GP refresh time`() {
        val settings = repo()
        val lastGeneral = settings.databaseState.value.updateTimestamp
        settings.setIssSupGpLastCheckMillis(1_732_000_000_000L)
        assertEquals(1_732_000_000_000L, repo().getIssSupGpLastCheckMillis())
        assertEquals(lastGeneral, repo().databaseState.value.updateTimestamp)
        settings.setIssSupGpLastCheckMillis(0L)
        assertEquals(0L, repo().getIssSupGpLastCheckMillis())
    }

    @Test
    fun `SupGP segments survive restart and can be cleared`() {
        val sample = OrbitalData(
            name = "ISS [Segment 01]", epoch = 26320.5, meanmo = 15.49,
            eccn = 0.0007, incl = 51.63, raan = 266.0,
            argper = 31.0, meanan = 350.0, catnum = 25544,
            bstar = 0.00013, ndot = 0.0007
        )
        repo().setIssSupGpSegments(listOf(sample, sample.copy(epoch = 26320.75)))
        val restored = repo().getIssSupGpSegments()
        assertEquals(listOf(26320.5, 26320.75), restored.map { it.epoch })
        assertEquals(15.49, restored[0].meanmo, 0.0)
        repo().setIssSupGpSegments(emptyList())
        assertTrue(repo().getIssSupGpSegments().isEmpty())
    }
}
