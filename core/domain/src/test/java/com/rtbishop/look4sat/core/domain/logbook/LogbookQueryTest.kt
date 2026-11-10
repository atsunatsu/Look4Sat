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
    fun ownStationSelectorDistinguishesSameCallsignAtDifferentGrids() {
        val ba7Ol62 = record(callsign = "JA1ABC", myCallsign = "BA7OPF").copy(myGrid = "OL62")
        val ba7Ol63 = record(callsign = "VK3XYZ", myCallsign = "BA7OPF").copy(myGrid = "OL63")
        val ba8Ol62 = record(callsign = "BD5EUA", myCallsign = "BA8OPF").copy(myGrid = "OL62")
        val list = listOf(ba7Ol62, ba7Ol63, ba8Ol62)

        assertEquals(
            listOf(ba7Ol62),
            filterRecords(list, "", "", LogbookFilter.ALL, OwnStationFilter("BA7OPF", "OL62"))
        )
        assertEquals(
            listOf(ba7Ol63),
            filterRecords(list, "", "", LogbookFilter.ALL, OwnStationFilter("BA7OPF", "OL63"))
        )
        assertEquals(
            listOf(ba7Ol63),
            filterRecords(list, "vk3", "", LogbookFilter.ALL, OwnStationFilter("BA7OPF", "OL63"))
        )
        assertEquals(
            emptyList<QsoRecord>(),
            filterRecords(list, "JA1", "", LogbookFilter.ALL, OwnStationFilter("BA7OPF", "OL63"))
        )
    }

    @Test
    fun ownStationSelectorUsesCompleteGridSetStampedAfterLoTWUpload() {
        val single = record(callsign = "JA1ABC").copy(myGrid = "OL62TI")
        val boundary = record(callsign = "VK3XYZ").copy(
            myGrid = "OL62TI", vuccGrids = listOf("OL62", "OL61")
        )
        val reordered = record(callsign = "YB9ABC").copy(
            myGrid = "OL61AT", vuccGrids = listOf("ol61", "OL62")
        )
        val corner = record(callsign = "E25CRF").copy(
            myGrid = "OM90XX", vuccGrids = listOf("OM91", "PM01", "OM90", "PM00")
        )
        val list = listOf(single, boundary, reordered, corner)

        assertEquals(
            listOf(
                OwnStationFilter("BA7OPF", "OL61,OL62"),
                OwnStationFilter("BA7OPF", "OL62TI"),
                OwnStationFilter("BA7OPF", "OM90,OM91,PM00,PM01")
            ),
            ownStationOptions(list)
        )
        assertEquals(
            listOf(boundary, reordered),
            filterRecords(list, "", "", LogbookFilter.ALL, OwnStationFilter("BA7OPF", "OL61,OL62"))
        )
        assertEquals(
            listOf(single),
            filterRecords(list, "", "", LogbookFilter.ALL, OwnStationFilter("BA7OPF", "OL62TI"))
        )
        assertEquals(
            listOf(corner),
            filterRecords(list, "", "", LogbookFilter.ALL, OwnStationFilter("BA7OPF", "OM90,OM91,PM00,PM01"))
        )
    }

    @Test
    fun ownStationSelectorDoesNotRetainOldGridAfterLegacyResubmission() {
        val corrected = record().copy(
            myGrid = "OL62TI",
            myFullGrids = listOf("OL62TI", "OL61AT"),
            vuccGrids = listOf("OL63")
        )
        assertEquals(listOf(OwnStationFilter("BA7OPF", "OL63")), ownStationOptions(listOf(corrected)))
        assertEquals(
            listOf(corrected),
            filterRecords(listOf(corrected), "", "", LogbookFilter.ALL, OwnStationFilter("BA7OPF", "OL63"))
        )
        assertEquals(
            emptyList<QsoRecord>(),
            filterRecords(listOf(corrected), "", "", LogbookFilter.ALL, OwnStationFilter("BA7OPF", "OL62TI"))
        )
    }

    @Test
    fun ownStationSelectorKeepsPreciseGridWhenItMatchesSignedSquare() {
        val signed = record().copy(myGrid = "OL62TI", vuccGrids = listOf("OL62"))
        assertEquals(listOf(OwnStationFilter("BA7OPF", "OL62TI")), ownStationOptions(listOf(signed)))
    }

    @Test
    fun ownStationSelectorReadsMultiGridsFromDownloadedLoTWAndLegacyAdif() {
        val downloaded = record(callsign = "BG7XYZ").copy(
            myGrid = "OL62TI", myFullGrids = listOf("OL62TI", "OL61AT"),
            vuccGrids = listOf("OL62", "OL61")
        )
        val withoutStamp = record(callsign = "BG7ZZZ").copy(
            myGrid = "OL61AT", myFullGrids = listOf("OL61AT", "OL62TI")
        )
        val oldCommaField = record(callsign = "BG7ABC").copy(myGrid = "OL62TI,OL61AT")
        val noCall = record(callsign = "BG7NOC").copy(
            myCallsign = "", myGrid = "OL62TI", vuccGrids = listOf("OL62", "OL61")
        )
        val list = listOf(downloaded, withoutStamp, oldCommaField, noCall)

        assertEquals(
            listOf(OwnStationFilter("BA7OPF", "OL61,OL62")),
            ownStationOptions(list)
        )
        assertEquals(
            listOf(downloaded, withoutStamp, oldCommaField),
            filterRecords(list, "", "", LogbookFilter.ALL, OwnStationFilter("BA7OPF", "OL61,OL62"))
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
