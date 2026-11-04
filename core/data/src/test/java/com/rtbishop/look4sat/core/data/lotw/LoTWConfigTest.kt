/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.lotw

import com.rtbishop.look4sat.core.domain.logbook.LoTWSatelliteAliases
import com.rtbishop.look4sat.core.domain.repository.LoTWOperationException
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.domain.repository.LoTWZonePair
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses the real bundled ARRL config.tq6 to verify region-field and zonemap extraction. */
class LoTWConfigTest {

    private val config = LoTWConfig(File("src/main/assets/lotw/config.tq6").inputStream())

    @Test
    fun `china exposes province field with all 31 options and zonemaps`() {
        val meta = config.stationMeta(318)
        assertEquals("CN_PROVINCE", meta.regionField?.id)
        assertEquals("Province", meta.regionField?.label)
        assertEquals(31, meta.regionField?.options?.size)
        val gd = meta.regionField?.options?.first { it.code == "GD" }
        assertEquals("Guangdong", gd?.name)
        assertEquals(listOf(LoTWZonePair(44, 24)), gd?.zones)
        val gx = meta.regionField?.options?.first { it.code == "GX" }
        assertEquals(listOf(LoTWZonePair(43, 24), LoTWZonePair(44, 24)), gx?.zones)
        // China spans 8 zone pairs — the UI must NOT prefill from the national map.
        assertTrue(meta.countryZones.size > 1)
    }

    @Test
    fun `usa exposes state field with 49 continental options`() {
        val meta = config.stationMeta(291)
        assertEquals("US_STATE", meta.regionField?.id)
        assertEquals("State", meta.regionField?.label)
        // AK and HI are separate DXCC entities (6 and 110) with their own 1-option blocks.
        assertEquals(49, meta.regionField?.options?.size)
        assertTrue(meta.regionField!!.options.none { it.code == "AK" || it.code == "HI" })
        assertTrue(meta.countryZones.size > 1)
    }

    @Test
    fun `alaska and hawaii each expose their own single-state block`() {
        val ak = config.stationMeta(6)
        assertEquals(listOf("AK"), ak.regionField?.options?.map { it.code })
        val hi = config.stationMeta(110)
        assertEquals(listOf("HI"), hi.regionField?.options?.map { it.code })
    }

    @Test
    fun `germany has no region field and one national zone pair`() {
        val meta = config.stationMeta(230)
        assertNull(meta.regionField)
        assertEquals(listOf(LoTWZonePair(28, 14)), meta.countryZones)
    }

    @Test
    fun `india has no region field and one national zone pair`() {
        val meta = config.stationMeta(324)
        assertNull(meta.regionField)
        assertEquals(listOf(LoTWZonePair(41, 22)), meta.countryZones)
    }

    @Test
    fun `unknown entity yields empty meta`() {
        val meta = config.stationMeta(999999)
        assertNull(meta.regionField)
        assertTrue(meta.countryZones.isEmpty())
    }

    @Test
    fun `boundary pair is serialized as one comma-separated GRIDSQUARE field`() {
        val fields = config.stationFields(LoTWStation(grid = "OM60, OM61"), 318)

        assertEquals("OM60,OM61", fields["GRIDSQUARE"])
        assertTrue("MY_VUCC_GRIDS" !in fields)
        assertTrue("GRIDSQUARE" in config.stationOrder)
        assertTrue("MY_VUCC_GRIDS" !in config.stationOrder)
    }

    @Test
    fun `four-way intersection is serialized in full in GRIDSQUARE`() {
        val fields = config.stationFields(LoTWStation(grid = "OM90,OM91,PM00,PM01"), 318)

        assertEquals("OM90,OM91,PM00,PM01", fields["GRIDSQUARE"])
        assertTrue("MY_VUCC_GRIDS" !in fields)
    }

    @Test
    fun `single grid remains a plain GRIDSQUARE value`() {
        val fields = config.stationFields(LoTWStation(grid = "OM60"), 318)

        assertEquals("OM60", fields["GRIDSQUARE"])
        assertTrue("MY_VUCC_GRIDS" !in fields)
    }

    @Test
    fun `tracker catalogue names resolve to the ARRL satellite`() {
        // The names the TLE sources store vs the single name ARRL knows for that satellite.
        assertEquals("SO-50", config.resolveSatellite("SAUDISAT 1C"))
        assertEquals("ARISS", config.resolveSatellite("ISS (ZARYA)"))
        assertEquals("PO-101", config.resolveSatellite("DIWATA 2B"))
        assertEquals("AO-91", config.resolveSatellite("FOX-1B"))
        assertEquals("IO-86", config.resolveSatellite("LAPAN-A2"))
        assertEquals("RS-44", config.resolveSatellite("DOSAAF-85"))
        assertEquals("RS95S", config.resolveSatellite("QMR-KWT 2"))
        assertEquals("BO-102", config.resolveSatellite("CAS-7B"))
        assertEquals("SO-50", config.resolveSatellite("SO-50"))
    }

    @Test
    fun `satellite signing accepts tracker names and keeps ARRL spelling`() {
        assertEquals("SO-50", config.satellite("SAUDISAT 1C", "2026-09-27"))
        assertEquals("ARISS", config.satellite("ISS (ZARYA)", "2026-09-27"))
        assertEquals("SO-50", config.satellite("SO-50 (SaudiOSCAR 50)", "2026-09-27"))
    }

    @Test
    fun `satellite signing rejects names ARRL does not know`() {
        // Placeholder designations are deliberately unmapped: signing them would name the
        // wrong object, so the record must stay un-uploadable.
        listOf("OBJECT AY", "MARINA", "NOT A SATELLITE").forEach { name ->
            val error = runCatching { config.satellite(name, "2026-09-27") }.exceptionOrNull()
            assertTrue("$name should be rejected", error is LoTWOperationException)
        }
    }

    @Test
    fun `satellite signing enforces the ARRL service dates`() {
        // SO-50 is listed from 2002-12-20: a QSO before that cannot be signed.
        val error = runCatching { config.satellite("SAUDISAT 1C", "2002-01-01") }.exceptionOrNull()
        assertTrue(error is LoTWOperationException)
        assertEquals("SO-50", config.satellite("SAUDISAT 1C", "2003-01-01"))
    }

    @Test
    fun `every alias target is a real ARRL satellite name`() {
        val catalogue = config.satelliteNames().toSet()
        assertTrue("ARRL catalogue is unexpectedly small", catalogue.size > 100)
        val unknown = LoTWSatelliteAliases.table.filterValues { it !in catalogue }
        assertEquals("alias targets missing from config.tq6: $unknown", emptyMap<String, String>(), unknown)
    }
}
