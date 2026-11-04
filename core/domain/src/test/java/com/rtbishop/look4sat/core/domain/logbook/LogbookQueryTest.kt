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
    fun freeTextQueryMatchesCounterpartCallsignAndCommentButNotOwnCallsign() {
        val commented = record(comment = "first QSO of the day")
        val list = listOf(commented, satFm)
        assertEquals(listOf(commented), filterRecords(list, "first qso", "", LogbookFilter.ALL))
        assertEquals(emptyList<QsoRecord>(), filterRecords(list, "BA7OPF", "", LogbookFilter.ALL))
    }

    @Test
    fun ownCallsignSelectorFiltersRecordsAndCombinesWithFreeText() {
        val ba7 = record(callsign = "JA1ABC", myCallsign = "BA7OPF")
        val ba8 = record(callsign = "VK3XYZ", myCallsign = "BA8OPF")
        val list = listOf(ba7, ba8)

        assertEquals(
            listOf(ba7),
            filterRecords(list, "", "", LogbookFilter.ALL, "ba7opf")
        )
        assertEquals(
            listOf(ba8),
            filterRecords(list, "vk3", "", LogbookFilter.ALL, "BA8OPF")
        )
        assertEquals(
            emptyList<QsoRecord>(),
            filterRecords(list, "JA1", "", LogbookFilter.ALL, "BA8OPF")
        )
    }

    @Test
    fun queryMatchesAdditionalCounterpartGridsInLegacyRecords() {
        val legacy = record(grid = "NM84", mode = "CW", confirmed = true)
            .copy(theirVuccGrids = listOf("NM94"))
        assertEquals(listOf(legacy), filterRecords(listOf(legacy, satFm), " nm94 ", "CW", LogbookFilter.CONFIRMED))
        assertEquals(emptyList<QsoRecord>(), filterRecords(listOf(legacy), "NM94", "FM", LogbookFilter.ALL))
        assertEquals(emptyList<QsoRecord>(), filterRecords(listOf(legacy), "NM94", "", LogbookFilter.UNCONFIRMED))
    }

    @Test
    fun queryMatchesMultiGridOnlyRecordsWithoutChangingThem() {
        val legacy = record(grid = "").copy(theirVuccGrids = listOf("NM84", "NM94"))
        assertEquals(listOf(legacy), filterRecords(listOf(legacy), "nm94", "", LogbookFilter.ALL))
        assertEquals("", legacy.theirGrid)
        assertEquals(listOf("NM84", "NM94"), legacy.theirVuccGrids)
    }

    @Test
    fun exactModeFilterMatchesTheDisplayMode() {
        assertEquals(listOf(satCw), filterRecords(all, "", "CW", LogbookFilter.ALL))
        assertEquals(emptyList<QsoRecord>(), filterRecords(all, "", "FT4", LogbookFilter.ALL))
        // A terrestrial row (blank satellite name) is addressed by its own mode label.
        val terrestrial = record(satellite = "", mode = "SSB")
        assertEquals(
            listOf(terrestrial),
            filterRecords(listOf(terrestrial, satFm), "", "SSB", LogbookFilter.ALL)
        )
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
            filterRecords(all, "eu", "CW", LogbookFilter.CONFIRMED)
        )
        assertEquals(
            emptyList<QsoRecord>(),
            filterRecords(all, "eu", "FM", LogbookFilter.CONFIRMED)
        )
    }
}
