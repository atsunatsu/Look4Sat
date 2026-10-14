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

import org.junit.Assert.assertEquals
import org.junit.Test

class LogbookQueryTest {
    private fun record(
        callsign: String = "BA1PK",
        myCallsign: String = "BA7OPF",
        grid: String = "OL62TI",
        satellite: String = "SO-50",
        mode: String = "FM",
        comment: String = "",
        status: QsoStatus = QsoStatus.COMPLETE,
        confirmed: Boolean = false
    ) = QsoRecord(
        startUtcMillis = 0L,
        theirCallsign = callsign,
        myCallsign = myCallsign,
        theirGrid = grid,
        satelliteName = satellite,
        mode = mode,
        comment = comment,
        status = status,
        lotwConfirmed = confirmed
    )

    private val satFm = record()
    private val satCw = record(callsign = "BD5EUA", satellite = "FO-29", mode = "CW", confirmed = true)
    private val linear = record(callsign = "BH6RJD", satellite = "RS-44", mode = "SSB")
    private val draft = record(callsign = "BG5JSU", status = QsoStatus.DRAFT)
    private val all = listOf(satFm, satCw, linear, draft)

    @Test
    fun blankQueryReturnsEverything() {
        assertEquals(all, filterRecords(all, "", "", LogbookFilter.ALL))
        assertEquals(all, filterRecords(all, "   ", "", LogbookFilter.ALL))
    }

    @Test
    fun queryMatchesCallsignSatelliteGridAndComment() {
        assertEquals(listOf(satFm), filterRecords(all, "ba1pk", "", LogbookFilter.ALL))
        // The draft inherits the default satellite, so both satellite records match here.
        assertEquals(listOf(satFm, draft), filterRecords(all, "SO-50", "", LogbookFilter.ALL))
        assertEquals(all, filterRecords(all, "OL62TI", "", LogbookFilter.ALL))
        assertEquals(listOf(satFm, satCw, linear, draft), filterRecords(all, "ol62", "", LogbookFilter.ALL))
    }

    @Test
    fun queryAlsoMatchesMyCallsignAndComment() {
        val commented = record(comment = "first QSO of the day")
        val list = listOf(commented, satFm)
        assertEquals(listOf(commented), filterRecords(list, "first qso", "", LogbookFilter.ALL))
        assertEquals(listOf(commented, satFm), filterRecords(list, "BA7OPF", "", LogbookFilter.ALL))
    }

    @Test
    fun satelliteModeFilterKeepsOnlySatelliteRecords() {
        // Every fixture is a satellite record except the non-satellite one added below.
        val terrestrial = record(satellite = "", mode = "SSB")
        val list = listOf(satFm, terrestrial)
        assertEquals(listOf(satFm), filterRecords(list, "", SATELLITE_MODE_FILTER, LogbookFilter.ALL))
        assertEquals(listOf(terrestrial), filterRecords(list, "", "SSB", LogbookFilter.ALL))
    }

    @Test
    fun exactModeFilterMatchesTheDisplayMode() {
        assertEquals(listOf(satCw), filterRecords(all, "", "CW", LogbookFilter.ALL))
        assertEquals(emptyList<QsoRecord>(), filterRecords(all, "", "FT4", LogbookFilter.ALL))
    }

    @Test
    fun confirmationFilterSplitsConfirmedUnconfirmedAndDrafts() {
        assertEquals(listOf(satCw), filterRecords(all, "", "", LogbookFilter.CONFIRMED))
        assertEquals(listOf(satFm, linear), filterRecords(all, "", "", LogbookFilter.UNCONFIRMED))
        assertEquals(listOf(draft), filterRecords(all, "", "", LogbookFilter.DRAFTS))
    }

    @Test
    fun filtersCombineWithAnd() {
        assertEquals(
            listOf(satCw),
            filterRecords(all, "eu", SATELLITE_MODE_FILTER, LogbookFilter.CONFIRMED)
        )
        assertEquals(
            emptyList<QsoRecord>(),
            filterRecords(all, "eu", "FM", LogbookFilter.CONFIRMED)
        )
    }
}
