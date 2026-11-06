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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Own identity follows the active destination: selected Wavelog station when ready, otherwise
 * the LoTW certificate with a Wavelog fallback.
 */
class OperatorIdentityTest {

    @Test
    fun callsign_certificateWinsOverTheWavelogStation() {
        assertEquals("BA7OPF", operatorCallsign("ba7opf", "BH3HCF"))
    }

    @Test
    fun callsign_fallsBackToTheWavelogStationWithoutACertificate() {
        assertEquals("BH3HCF", operatorCallsign("", "bh3hcf"))
        assertEquals("BH3HCF", operatorCallsign("   ", "BH3HCF"))
    }

    @Test
    fun callsign_isBlankWhenNeitherSourceKnowsOne() {
        assertEquals("", operatorCallsign("", ""))
    }

    @Test
    fun grids_stationLocationWins() {
        assertEquals(listOf("OL62TI"), operatorGrids("OL62TI", "OL62TJ,OL62TK"))
    }

    @Test
    fun grids_fallBackToTheWavelogStationProfile() {
        // Wavelog keeps several gridsquares comma-separated (a location on a line or a corner);
        // they come back trimmed and uppercase.
        assertEquals(listOf("OL62TI", "OL62TJ"), operatorGrids("", " ol62ti , OL62TJ "))
    }

    @Test
    fun callsign_wavelogStationWinsWhenWavelogIsActive() {
        assertEquals("BH3HCF", operatorCallsign("BA7OPF", " bh3hcf ", wavelogMode = true))
        // Never silently log under an old certificate if the selected profile lacks a callsign.
        assertEquals("", operatorCallsign("BA7OPF", "", wavelogMode = true))
    }

    @Test
    fun grids_followTheWavelogStationWhenWavelogIsActive() {
        assertEquals(listOf("OL62TJ", "OL62TK"),
            operatorGrids("OL62TI", " ol62tj , ol62tk ", wavelogMode = true))
        assertTrue(operatorGrids("OL62TI", "", wavelogMode = true).isEmpty())
    }

    @Test
    fun grids_areEmptyWhenNeitherSourceKnowsOne() {
        assertTrue(operatorGrids("", "").isEmpty())
    }

    @Test
    fun qso_preservesMultiGridLocatorsWithoutTruncatingThem() {
        val qso = QsoRecord(
            startUtcMillis = 1L, theirCallsign = "BH6RJD", myCallsign = "BA7OPF"
        ).withOperatorGridSet(" ol62ti99 , OL62TJ, OL63AA ")
        assertEquals("OL62TI99", qso.myGrid)
        assertEquals(listOf("OL62TI99", "OL62TJ", "OL63AA"), qso.myFullGrids)
        assertEquals(listOf("OL62", "OL63"), qso.vuccGrids)
        val adif = AdifCodec.encode(listOf(qso))
        assertTrue(adif.contains("<MY_GRIDSQUARE:8>OL62TI99"))
        assertTrue(adif.contains("<MY_VUCC_GRIDS:22>OL62TI99,OL62TJ,OL63AA"))
    }

    @Test
    fun qso_singleGridHasNoRedundantVuccField() {
        val qso = QsoRecord(
            startUtcMillis = 1L, theirCallsign = "BH6RJD", myCallsign = "BA7OPF"
        ).withOperatorGridSet("OL62TI")
        assertEquals("OL62TI", qso.myGrid)
        assertTrue(qso.myFullGrids.isEmpty())
        assertTrue(qso.vuccGrids.isEmpty())
    }
}
