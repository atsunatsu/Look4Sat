/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.logbook

import com.rtbishop.look4sat.core.domain.model.GridQso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogbookMergeTest {

    private val local = QsoRecord(
        startUtcMillis = 1_700_000_000_000L,
        theirCallsign = "bg5jvm",
        myCallsign = "ba7opf",
        txFrequencyHz = 145_850_000L,
        mode = "FM",
        submode = "",
        satelliteName = "SO-50 (SaudiOSCAR 50)",
        propagationMode = "SAT",
        status = QsoStatus.COMPLETE
    )

    private val confirmed = QsoRecord(
        startUtcMillis = 1_700_000_000_000L,
        theirCallsign = "BG5JVM",
        myCallsign = "BA7OPF",
        mode = "FM",
        submode = "",
        satelliteName = "SO-50",
        propagationMode = "SAT",
        status = QsoStatus.COMPLETE,
        lotwConfirmed = true,
        vuccGrids = listOf("OM60", "OM50"),
        theirVuccGrids = listOf("EN52", "EN53")
    )

    @Test
    fun satMatchKey_stripsParenthetical() {
        assertEquals("SO-50", satMatchKey("SO-50 (SaudiOSCAR 50)"))
        assertEquals("SO-50", satMatchKey("SO-50"))
        assertEquals("AO-91", satMatchKey("AO-91 (FUNcube-1)"))
        assertEquals("IO-86", satMatchKey("IO-86"))
    }

    @Test
    fun sameConfirmedContact_matchesAcrossSatNOGSAndOfficialName() {
        assertTrue(sameConfirmedContact(local, confirmed))
    }

    @Test
    fun sameConfirmedContact_rejectsDifferentSatellite() {
        val other = confirmed.copy(satelliteName = "AO-91")
        assertFalse(sameConfirmedContact(local, other))
    }

    @Test
    fun sameConfirmedContact_rejectsTimeSkew() {
        val skewed = confirmed.copy(startUtcMillis = confirmed.startUtcMillis + 120_000L)
        assertFalse(sameConfirmedContact(local, skewed))
    }

    @Test
    fun confirmationLookupKey_usesNormalizedSatellite() {
        assertEquals(local.confirmationLookupKey(), confirmed.confirmationLookupKey())
    }

    @Test
    fun confirmationLookupKey_groupsMfskAndFt4Spellings() {
        // Satellite FT4 is MFSK + FT4; a row persisted by an older release as plain MFSK is
        // the same mode and must resolve to the same contact.
        val plainMfsk = confirmed.copy(mode = "MFSK", submode = "")
        val ft4 = confirmed.copy(mode = "MFSK", submode = "FT4")
        assertEquals(plainMfsk.confirmationLookupKey(), ft4.confirmationLookupKey())
        assertTrue(sameContactIdentity(plainMfsk, ft4))
    }

    @Test
    fun withConfirmation_marksConfirmedAndMergesVuccGrids() {
        val merged = local.withConfirmation(confirmed)
        assertTrue(merged.lotwConfirmed)
        assertTrue(merged.lotwReceived)
        assertEquals(listOf("OM60", "OM50"), merged.vuccGrids)
        // The opposite station's grid set arrives with the confirmation too.
        assertEquals(listOf("EN52", "EN53"), merged.theirVuccGrids)
        // Local myCallsign is kept when present; only blanks are backfilled.
        assertEquals("ba7opf", merged.myCallsign)
    }

    @Test
    fun withConfirmation_replacesStaleOwnGridWithReportedLocator() {
        val old = local.copy(myGrid = "OL62", txFrequencyHz = 145_850_000L)
        val fromLoTW = confirmed.copy(myGrid = "OL62TI")

        val merged = old.withConfirmation(fromLoTW)

        assertEquals("OL62TI", merged.myGrid)
        assertEquals(145_850_000L, merged.txFrequencyHz)
    }

    @Test
    fun withConfirmation_keepsOwnGridWhenReportOmitsIt() {
        val old = local.copy(myGrid = "OL62TI")

        assertEquals("OL62TI", old.withConfirmation(confirmed.copy(myGrid = "")).myGrid)
    }

    @Test
    fun toConfirmedRecord_mapsGridQso() {
        val qso = GridQso(
            call = "BH6RJD",
            epochMs = 1_700_000_000_000L,
            satName = "SO-50",
            mode = "FM",
            bandUp = "70CM",
            bandDown = "2M",
            dxcc = 318,
            country = "China",
            cqz = 24,
            state = "GD",
            myGrid = "OM60",
            myGrids = setOf("OM60", "OM50"),
            theirGrids = listOf("EN52", "EN53")
        )
        val record = qso.toConfirmedRecord("ba7opf")
        assertEquals("BH6RJD", record.theirCallsign)
        assertEquals("BA7OPF", record.myCallsign)
        assertEquals("OM60", record.myGrid)
        assertEquals(listOf("OM60", "OM50"), record.vuccGrids)
        assertEquals("EN52", record.theirGrid)
        assertEquals(listOf("EN52", "EN53"), record.theirVuccGrids)
        assertTrue(record.lotwConfirmed)
        assertTrue(record.isSatellite)
        assertEquals("70CM", record.band)
        assertEquals("2M", record.rxBand)
    }

    @Test
    fun frequencyBand_edges() {
        assertEquals("2M", frequencyBand(144_000_000L))
        assertEquals("70CM", frequencyBand(450_000_000L))
        assertEquals("10M", frequencyBand(29_000_000L))
        assertEquals("", frequencyBand(null))
    }

    @Test
    fun displayMode_prefersSubmode() {
        val ft4 = QsoRecord(startUtcMillis = 0, theirCallsign = "x", myCallsign = "y", mode = "MFSK", submode = "FT4")
        assertEquals("FT4", ft4.displayMode)
        assertEquals("FM", local.displayMode)
    }

    @Test
    fun displayMode_ignoresStaleSubmodeForOtherModes() {
        // Regression: records persisted with the old default submode="FT4" must not
        // show FT4 when the actual mode is FM/CW/SSB.
        val staleFm = QsoRecord(startUtcMillis = 0, theirCallsign = "x", myCallsign = "y", mode = "FM", submode = "FT4")
        val staleCw = QsoRecord(startUtcMillis = 0, theirCallsign = "x", myCallsign = "y", mode = "CW", submode = "FT4")
        assertEquals("FM", staleFm.displayMode)
        assertEquals("CW", staleCw.displayMode)
    }

    // region logbook split (tracker name / band direction, 2026-09-28)

    @Test
    fun satelliteIdentity_mapsTrackerNamesOntoArlNames() {
        // Local records keep the tracking-source name, LoTW reports the ARRL name; both
        // must land on one identity or every confirmation becomes a second row.
        assertEquals("SO-50", satelliteIdentity("SAUDISAT 1C"))
        assertEquals("SO-50", satelliteIdentity("SO-50"))
        assertEquals("ARISS", satelliteIdentity("ISS (ZARYA)"))
        assertEquals("ARISS", satelliteIdentity("ARISS"))
        assertEquals("PO-101", satelliteIdentity("DIWATA 2B"))
        assertEquals("FO-29", satelliteIdentity("JAS 2"))
    }

    @Test
    fun satelliteIdentity_isSymmetricForEveryAliasEntry() {
        // Guards the whole table: an entry whose target does not resolve back to the same
        // identity would split that satellite's contacts again.
        LoTWSatelliteAliases.table.forEach { (tracker, official) ->
            assertEquals(official, satelliteIdentity(tracker), satelliteIdentity(official))
        }
    }

    @Test
    fun satelliteIdentity_keepsRecycledPlaceholdersUnmatched() {
        // "OBJECT xx" names are reused between objects and deliberately unmapped, so they
        // must never resolve onto a real ARRL satellite.
        assertEquals("OBJECT AY", satelliteIdentity("OBJECT AY"))
        assertNotEquals(satelliteIdentity("AO-123"), satelliteIdentity("OBJECT AY"))
    }

    @Test
    fun sameConfirmedContact_matchesTrackerNameAgainstArlName() {
        // The reported bug: SO-50 logged in the app (tracker name, 145.850 uplink) never
        // picked up the confirmation LoTW reported for it.
        val uploaded = loggedRecord("SAUDISAT 1C", uplinkHz = 145_850_000L, downlinkHz = 436_795_000L)
        val reported = reportedRecord("SO-50", band = "2M", rxBand = "70CM")
        assertTrue(sameConfirmedContact(uploaded, reported))
    }

    @Test
    fun sameConfirmedContact_stillRejectsOtherContactOrMinute() {
        val uploaded = loggedRecord("SAUDISAT 1C", uplinkHz = 145_850_000L, downlinkHz = 436_795_000L)
        val otherOperator = reportedRecord("SO-50", band = "2M", rxBand = "70CM").copy(theirCallsign = "BG5JVM")
        val anotherMinute = reportedRecord("SO-50", band = "2M", rxBand = "70CM")
            .copy(startUtcMillis = uploaded.startUtcMillis + 90_000L)
        assertFalse(sameConfirmedContact(uploaded, otherOperator))
        assertFalse(sameConfirmedContact(uploaded, anotherMinute))
    }

    @Test
    fun splitConfirmationPairs_foldsRowsSplitByTheOldMismatch() {
        // Both pre-fix symptoms in one pair: the imported row keeps the ARRL name and the
        // mirrored band direction the old parser produced (band=downlink).
        val uploaded = loggedRecord("SAUDISAT 1C", 145_850_000L, 436_795_000L)
            .copy(id = 1L, lotwUploaded = true)
        val imported = reportedRecord("SO-50", band = "70CM", rxBand = "2M").copy(id = 2L)
        val pairs = splitConfirmationPairs(listOf(uploaded, imported))
        assertEquals(1, pairs.size)
        assertEquals(0, pairs.first().localIndex)
        assertEquals(1, pairs.first().confirmationIndex)
    }

    @Test
    fun splitConfirmationPairs_foldsTwoConfirmedRowsAndKeepsTheRicher() {
        // A contact stored twice with BOTH sides confirmed: the mirrored import the old parser
        // created (no frequencies, report-only fills) plus the app row that was confirmed on
        // its own later (frequencies, upload state). Pass 1 cannot fold those; without pass 2
        // they stay doubled in the logbook forever.
        val mirroredImport = reportedRecord("SO-50", band = "70CM", rxBand = "2M").copy(id = 1L)
        val confirmedLocal = loggedRecord("SAUDISAT 1C", 145_850_000L, 436_795_000L)
            .copy(id = 2L, lotwConfirmed = true)
        val pairs = splitConfirmationPairs(listOf(mirroredImport, confirmedLocal))
        assertEquals(1, pairs.size)
        // The richer row (frequencies + consistent bands + upload state) is the survivor.
        assertEquals(1, pairs.first().localIndex)
        assertEquals(0, pairs.first().confirmationIndex)
    }

    @Test
    fun splitConfirmationPairs_keepsTwoConfirmedRowsOfDifferentMinutesApart() {
        val one = reportedRecord("SO-50", band = "70CM", rxBand = "2M").copy(id = 1L)
        val otherMinute = reportedRecord("SO-50", band = "2M", rxBand = "70CM")
            .copy(id = 2L, startUtcMillis = REPORTED_START + 90_000L)
        assertTrue(splitConfirmationPairs(listOf(one, otherMinute)).isEmpty())
    }

    @Test
    fun splitConfirmationPairs_keepsUnrelatedRowsApart() {
        val uploaded = loggedRecord("SAUDISAT 1C", 145_850_000L, 436_795_000L)
            .copy(id = 1L, lotwUploaded = true)
        // Another operator on another satellite in the same minute must not be folded in.
        val otherOperator = reportedRecord("SO-50", band = "70CM", rxBand = "2M")
            .copy(id = 2L, theirCallsign = "BG5JVM")
        // A contact on a satellite the local record does not name at all stays separate.
        val otherSatellite = reportedRecord("AO-91", band = "70CM", rxBand = "2M").copy(id = 3L)
        assertTrue(splitConfirmationPairs(listOf(uploaded, otherOperator, otherSatellite)).isEmpty())
    }

    /** A record as the log page creates it: tracker name, uplink/downlink bands, no flags. */
    @Test
    fun officialSatelliteName_storesTheArlName() {
        assertEquals("SO-50", officialSatelliteName("SAUDISAT 1C"))
        assertEquals("ARISS", officialSatelliteName("ISS (ZARYA)"))
        assertEquals("PO-101", officialSatelliteName("DIWATA 2B"))
        assertEquals("FO-29", officialSatelliteName("JAS 2"))
        // Already-ARRL names are left alone (the rewrite is idempotent).
        assertEquals("SO-50", officialSatelliteName("SO-50"))
        assertEquals("AO-123", officialSatelliteName("AO-123"))
        // Nothing to map onto: a satellite ARRL does not list, a recycled placeholder, a blank.
        assertEquals("FOO-1", officialSatelliteName("FOO-1"))
        assertEquals("OBJECT AY", officialSatelliteName("OBJECT AY"))
        assertEquals("", officialSatelliteName("   "))
    }

    @Test
    fun officialSatelliteName_usesTheCatalogueWhenItKnowsTheNameTheTrackerCarries() {
        val catalogue = listOf("BO-102", "SO-50")
        // "CAS-7B" resolves through the alias table, the description-only name through the catalogue.
        assertEquals("BO-102", officialSatelliteName("CAS-7B", catalogue))
        assertEquals("BO-102", officialSatelliteName("CAS-7B (BO-102)", catalogue))
        assertEquals("SO-50", officialSatelliteName("SAUDISAT 1C", catalogue))
    }

    private fun loggedRecord(satelliteName: String, uplinkHz: Long, downlinkHz: Long) = QsoRecord(
        startUtcMillis = REPORTED_START,
        theirCallsign = "BG5JSB",
        myCallsign = "BA7OPF",
        txFrequencyHz = uplinkHz,
        rxFrequencyHz = downlinkHz,
        band = frequencyBand(uplinkHz),
        rxBand = frequencyBand(downlinkHz),
        mode = "FM",
        satelliteName = satelliteName,
        propagationMode = "SAT",
        status = QsoStatus.COMPLETE,
        lotwUploaded = true
    )

    /** A record as a LoTW confirmation carries it: ARRL name, report bands, confirmed. */
    private fun reportedRecord(satelliteName: String, band: String, rxBand: String) = QsoRecord(
        startUtcMillis = REPORTED_START,
        theirCallsign = "BG5JSB",
        myCallsign = "BA7OPF",
        band = band,
        rxBand = rxBand,
        mode = "FM",
        satelliteName = satelliteName,
        propagationMode = "SAT",
        status = QsoStatus.COMPLETE,
        lotwConfirmed = true
    )

    private companion object {
        /** 2026-09-16 07:45Z — an SO-50 pass of the user's real report. */
        const val REPORTED_START = 1_789_544_700_000L
    }

    // endregion
}
