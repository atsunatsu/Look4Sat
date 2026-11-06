/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.repository.WavelogProblem
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadOutcome
import com.rtbishop.look4sat.core.domain.repository.withStationProfile
import com.rtbishop.look4sat.core.domain.logbook.withOperatorGridSet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WavelogUploadRepositoryTest {

    // region # gridCompatible (mirror of the server's adif_grid_check_location prefix rule)

    @Test
    fun grid_sameGridsPass() {
        assertTrue(gridCompatible("OL62TI", "OL62TI"))
    }

    @Test
    fun grid_longerAdifGridPasses() {
        assertTrue(gridCompatible("OL62TI", "OL62"))
    }

    @Test
    fun grid_longerStationGridPasses() {
        assertTrue(gridCompatible("OL62", "OL62TI"))
    }

    @Test
    fun grid_differentSquaresFail() {
        assertFalse(gridCompatible("PM01", "OL62"))
    }

    @Test
    fun grid_blankSidesDisableTheCheck() {
        assertTrue(gridCompatible("", "OL62"))
        assertTrue(gridCompatible("OL62", ""))
    }

    @Test
    fun grid_stationMarginAcceptsAPrefixPart() {
        assertTrue(gridCompatible("OL63AA", "OL62,OL63"))
        assertFalse(gridCompatible("PM01AA", "OL62,OL63"))
    }

    @Test
    fun grid_caseInsensitive() {
        assertTrue(gridCompatible("ol62ti", "OL62"))
    }

    // endregion

    // region # classifyUploadResponse

    private val calls = mapOf(1L to "BG5JVM", 2L to "BH6RJD", 3L to "BI3SIR")

    @Test
    fun createdMarksEverything() {
        val outcome = classifyUploadResponse(
            201, """{"status":"created","adif_count":3,"adif_errors":0,"messages":[""]}""", calls
        )
        assertEquals(WavelogUploadOutcome.Imported(3, 0, 0, listOf(1L, 2L, 3L)), outcome)
    }

    @Test
    fun duplicatesAreCountedAndStillMarked() {
        val body = """{"status":"abort","messages":["Date/Time: 2026-10-04 12:34:00 Callsign: BG5JVM Band: 70CM Duplicate for BA7OPF<br>"]}"""
        val outcome = classifyUploadResponse(400, body, calls)
        assertEquals(WavelogUploadOutcome.Imported(2, 1, 0, listOf(1L, 2L, 3L)), outcome)
    }

    @Test
    fun skippedRecordIsNotMarked() {
        val body = """{"status":"abort","messages":["Differing locator <b>PM01</b> while importing QSO with <b>BH6RJD</b> for station locator <b>OL62TI</b>: SKIPPED<br>"]}"""
        val outcome = classifyUploadResponse(400, body, calls)
        assertEquals(WavelogUploadOutcome.Imported(2, 0, 1, listOf(1L, 3L)), outcome)
    }

    @Test
    fun duplicateAndSkipTogether() {
        val body = """{"status":"abort","messages":["Differing locator <b>PM01</b> while importing QSO with <b>BH6RJD</b> for station locator <b>OL62TI</b>: SKIPPED<br>Date/Time: 2026-10-04 12:34:00 Callsign: BG5JVM Band: 70CM Duplicate for BA7OPF<br>"]}"""
        val outcome = classifyUploadResponse(400, body, calls)
        assertEquals(WavelogUploadOutcome.Imported(1, 1, 1, listOf(1L, 3L)), outcome)
    }

    @Test
    fun unmappedSkipMarksNothing() {
        val body = """{"status":"abort","messages":["Differing locator <b>PM01</b> while importing QSO with <b>N0CALL</b> for station locator <b>OL62TI</b>: SKIPPED<br>"]}"""
        val outcome = classifyUploadResponse(400, body, calls)
        assertTrue(outcome is WavelogUploadOutcome.Rejected)
    }

    @Test
    fun otherMessagesAreRejected() {
        val body = """{"status":"abort","messages":["QSO on 2026-10-04: You tried to import a QSO without any given CALL. This QSO wasn't imported. It's invalid.<br>"]}"""
        val outcome = classifyUploadResponse(400, body, calls)
        assertTrue(outcome is WavelogUploadOutcome.Rejected)
    }

    @Test
    fun authFailuresAreActionable() {
        assertTrue(classifyUploadResponse(401, "", calls) is WavelogUploadOutcome.Failed)
        val forbidden = classifyUploadResponse(403, """{"reason":"API key does not have write permissions"}""", calls)
        assertTrue(forbidden is WavelogUploadOutcome.Failed)
        assertTrue((forbidden as WavelogUploadOutcome.Failed).message.contains("write"))
    }

    @Test
    fun htmlGarbageOrEmptyBodyMarksNothing() {
        assertTrue(classifyUploadResponse(400, "<html>oops</html>", calls) is WavelogUploadOutcome.Rejected)
        assertTrue(classifyUploadResponse(400, "", calls) is WavelogUploadOutcome.Failed)
    }

    // endregion

    // region # parseStations + prepare

    @Test
    fun stationsParse() {
        val body = """[{"station_id":"1","station_profile_name":"Shenzhen","station_gridsquare":"OL62TI","station_callsign":"BA7OPF","station_active":"1"}]"""
        val stations = parseStations(body)
        assertEquals(1, stations.size)
        assertEquals("1", stations.single().id)
        assertEquals("Shenzhen", stations.single().name)
        assertEquals("BA7OPF", stations.single().callsign)
        assertEquals("OL62TI", stations.single().grid)
        assertTrue(stations.single().active)
    }

    @Test
    fun selectedStationMetadataRefreshesById() {
        val previous = WavelogUploadSettings(
            url = "http://example.org", apiKey = "k", stationId = "42",
            stationName = "Old", stationCallsign = "BA7OPF", stationGrid = "OL62TI"
        )
        val updated = previous.withStationProfile(
            com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo(
                id = "42", name = "New", callsign = "BA7OPF", grid = "OL62TJ,OL62TK", active = true
            )
        )
        assertEquals("42", updated.stationId)
        assertEquals("OL62TJ,OL62TK", updated.stationGrid)
        assertEquals("New", updated.stationName)
    }

    @Test
    fun prepareKeepsEveryOperatedGridInThePreview() = runBlocking {
        val settings = WavelogUploadSettings(
            url = "http://example.org", apiKey = "k", stationId = "42",
            stationName = "Shenzhen", stationCallsign = "BA7OPF", stationGrid = "OL62TI,OL62TJ"
        )
        val qso = sampleRecord(start = 1_700_000_000_000L).withOperatorGridSet("OL62TI,OL62TJ")
        val preview = WavelogUploadRepository().prepare(listOf(qso), settings)
        assertEquals(1, preview.count)
        assertEquals("42", preview.stationId)
        assertEquals("OL62TI,OL62TJ", preview.stationGrid)
        assertEquals(listOf("OL62TI", "OL62TJ"), preview.qsoGrids)
        assertEquals("Shenzhen · BA7OPF · OL62TI,OL62TJ", preview.stationLabel)
    }

    @Test
    fun prepareRejectsAnIncompleteMultiGridSet() = runBlocking {
        val settings = WavelogUploadSettings(
            url = "http://example.org", apiKey = "k", stationId = "42",
            stationGrid = "OL62TI,OL62TJ"
        )
        val qso = sampleRecord(start = 1_700_000_000_000L).withOperatorGridSet("OL62TI,OL63AA")
        val preview = WavelogUploadRepository().prepare(listOf(qso), settings)
        assertEquals(0, preview.count)
        assertEquals(1, preview.reasons[WavelogProblem.GRID_MISMATCH])
    }

    @Test
    fun changingUploadStationAfterPreviewExpiresTheSubmission() = runBlocking {
        val settings = WavelogUploadSettings(
            url = "http://example.org", apiKey = "k", stationId = "42",
            stationGrid = "OL62TI"
        )
        val repo = WavelogUploadRepository()
        val preview = repo.prepare(listOf(sampleRecord(1_700_000_000_000L)), settings)
        assertEquals(1, preview.count)
        assertEquals(
            WavelogUploadOutcome.Expired,
            repo.upload(preview, settings.copy(stationId = "43"))
        )
    }

    @Test
    fun prepareWithoutConfigIsBlocked() = runBlocking {
        val repo = WavelogUploadRepository()
        val preview = repo.prepare(listOf(sampleRecord(start = 1_700_000_000_000L)), WavelogUploadSettings())
        assertEquals(WavelogProblem.NOT_CONFIGURED, preview.blockedBy)
        assertEquals(0, preview.count)
    }

    @Test
    fun prepareWithoutStationIsBlocked() = runBlocking {
        val repo = WavelogUploadRepository()
        val settings = WavelogUploadSettings(url = "http://example.org", apiKey = "k")
        val preview = repo.prepare(listOf(sampleRecord(start = 1_700_000_000_000L)), settings)
        assertEquals(WavelogProblem.NO_STATION, preview.blockedBy)
    }

    @Test
    fun prepareSkipsGridMismatchAndDeduplicates() = runBlocking {
        val repo = WavelogUploadRepository()
        val settings = WavelogUploadSettings(
            url = "http://example.org", apiKey = "k", stationId = "1",
            stationName = "Shenzhen", stationCallsign = "BA7OPF", stationGrid = "OL62TI"
        )
        val records = listOf(
            sampleRecord(start = 1_700_000_000_000L, myGrid = "PM01AA"),
            sampleRecord(start = 1_700_000_120_000L, myGrid = "OL62TI"),
            sampleRecord(start = 1_700_000_120_000L, myGrid = "OL62TI") // batch duplicate of the previous
        )
        val preview = repo.prepare(records, settings)
        assertEquals(1, preview.count)
        assertEquals(2, preview.skipped)
        assertEquals(1, preview.reasons[WavelogProblem.GRID_MISMATCH])
        assertEquals(1, preview.submittedIds.size)
        assertEquals("Shenzhen · BA7OPF · OL62TI", preview.stationLabel)
    }

    // endregion

    private fun sampleRecord(start: Long, myGrid: String = "OL62TI") = QsoRecord(
        id = start,
        startUtcMillis = start,
        theirCallsign = "BG5JVM",
        myCallsign = "BA7OPF",
        myGrid = myGrid,
        txFrequencyHz = 145_850_000L,
        rxFrequencyHz = 436_795_000L,
        band = "2M",
        rxBand = "70CM",
        mode = "FM",
        satelliteName = "SO-50",
        propagationMode = "SAT",
        status = QsoStatus.COMPLETE
    )
}
