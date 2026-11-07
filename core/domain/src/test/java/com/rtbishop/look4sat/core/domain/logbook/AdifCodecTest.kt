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

import com.rtbishop.look4sat.core.domain.repository.isQslConfirmed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdifCodecTest {

    @Test
    fun outgoingAdifDefaultsMissingSignalReportsByMode() {
        fun encode(mode: String, sent: String = "", received: String = "") = AdifCodec.encode(
            listOf(QsoRecord(
                startUtcMillis = 1_700_000_000_000L,
                theirCallsign = "BG5JVM",
                myCallsign = "BA7OPF",
                mode = mode,
                sentReport = sent,
                receivedReport = received
            ))
        )

        listOf("FM", "SSB", "USB", "LSB").forEach { mode ->
            val adif = encode(mode)
            assertTrue(adif.contains("<RST_SENT:2>59"))
            assertTrue(adif.contains("<RST_RCVD:2>59"))
        }
        val cw = encode("CW")
        assertTrue(cw.contains("<RST_SENT:3>599"))
        assertTrue(cw.contains("<RST_RCVD:3>599"))

        val explicit = encode("CW", sent = "579", received = "559")
        assertTrue(explicit.contains("<RST_SENT:3>579"))
        assertTrue(explicit.contains("<RST_RCVD:3>559"))

        val partial = encode("CW", sent = "579")
        assertTrue(partial.contains("<RST_SENT:3>579"))
        assertTrue(partial.contains("<RST_RCVD:3>599"))

        // Digital modes without a requested default must remain unmodified.
        val digital = encode("MFSK")
        assertFalse(digital.contains("<RST_SENT:"))
        assertFalse(digital.contains("<RST_RCVD:"))
    }

    @Test
    fun encodeDecode_roundTrip() {
        val record = QsoRecord(
            startUtcMillis = 1_700_000_000_000L,
            endUtcMillis = 1_700_000_000_100L,
            theirCallsign = "BG5JVM",
            myCallsign = "BA7OPF",
            theirGrid = "EN52",
            theirVuccGrids = listOf("EN52", "EN53"),
            myGrid = "OL62TI",
            vuccGrids = listOf("OL62", "OL61"),
            sentReport = "59",
            receivedReport = "59",
            txFrequencyHz = 145_850_000L,
            rxFrequencyHz = 436_805_000L,
            band = "2M",
            rxBand = "70CM",
            mode = "FM",
            satelliteName = "SO-50",
            satelliteMode = "FM",
            propagationMode = "SAT",
            status = QsoStatus.COMPLETE,
            lotwConfirmed = true,
            lotwQslDate = "2026-09-20"
        )
        val decoded = AdifCodec.decode(AdifCodec.encode(listOf(record))).single()
        assertEquals(record.startUtcMillis, decoded.startUtcMillis)
        assertEquals(record.theirCallsign, decoded.theirCallsign)
        assertEquals(record.myCallsign, decoded.myCallsign)
        assertEquals(record.satelliteName, decoded.satelliteName)
        assertEquals("2M", decoded.band)
        assertEquals("70CM", decoded.rxBand)
        assertTrue(decoded.lotwConfirmed)
        // Grid directions survive a round trip: the opposite's set stays theirs,
        // the operator's own set stays theirs.
        assertEquals("EN52", decoded.theirGrid)
        assertEquals(listOf("EN52", "EN53"), decoded.theirVuccGrids)
        assertEquals("OL62TI", decoded.myGrid)
        assertEquals(listOf("OL62", "OL61"), decoded.vuccGrids)
    }

    @Test
    fun iotaBelongsToOppositeStationAndSurvivesAdifRoundTrip() {
        val record = QsoRecord(
            startUtcMillis = 1_700_000_000_000L,
            theirCallsign = "YB0ABC",
            myCallsign = "BA7OPF",
            theirGrid = "OI33KS",
            satelliteName = "SO-50",
            iota = "OC-177"
        )
        val text = AdifCodec.encode(listOf(record))
        assertTrue(text.contains("<IOTA:6>OC-177"))
        val decoded = AdifCodec.decode(text).single()
        assertEquals("OC-177", decoded.iota)
        assertEquals("OI33KS", decoded.theirGrid)
    }


    @Test
    fun preservesEightCharGridsAndIotaWithoutChangingVuccIndex() {
        val raw = mapOf(
            "CALL" to "YB9ABC", "QSO_DATE" to "20261021", "TIME_ON" to "1030",
            "GRIDSQUARE" to "OI71OI42", "VUCC_GRIDS" to "OI71OI42,PI59PC55,PI79AD",
            "MY_GRIDSQUARE" to "OL62TI99", "MY_VUCC_GRIDS" to "OL62TI99,OL61AT",
            "IOTA" to "OC-022", "MY_IOTA" to "AS-129", "PROP_MODE" to "SAT"
        )
        val q = AdifCodec.decodeRecords(listOf(raw)).single()
        assertEquals(listOf("OI71", "PI59", "PI79"), q.theirVuccGrids)
        assertEquals(listOf("OI71OI42", "PI59PC55", "PI79AD"), q.theirFullGrids)
        assertEquals(listOf("OL62TI99", "OL61AT"), q.myFullGrids)
        assertEquals("OC-022", q.iota)
        assertEquals("AS-129", q.myIota)
        val back = AdifCodec.decode(AdifCodec.encode(listOf(q))).single()
        assertEquals(q.theirFullGrids, back.theirFullGrids)
        assertEquals(q.myFullGrids, back.myFullGrids)
        assertEquals(q.iota, back.iota)
        assertEquals(q.myIota, back.myIota)
        assertEquals(q.theirVuccGrids, back.theirVuccGrids)
    }

    @Test
    fun decode_lotwStyleReportFields() {
        // LoTW report subset: lowercase eor, alphabetical fields. The un-prefixed
        // grid fields belong to the OPPOSITE station, MY_* fields to the operator.
        val adi = """
            <EOH>
            <CALL:6>BH6RJD
            <QSO_DATE:8>20260916
            <TIME_ON:6>051300
            <MODE:2>FM
            <SAT_NAME:5>SO-50
            <PROP_MODE:3>SAT
            <BAND:3>2M
            <BAND_RX:4>70CM
            <GRIDSQUARE:4>OM60
            <VUCC_GRIDS:9>OM60,OM50
            <MY_GRIDSQUARE:6>OL62TI
            <MY_VUCC_GRIDS:9>OL62,OL61
            <STATION_CALLSIGN:6>BH6RJD
            <DXCC:3>318
            <COUNTRY:5>China
            <LOTW_QSL_RCVD:1>Y
            <eor>
        """.trimIndent()
        val records = AdifCodec.decode(adi)
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals("BH6RJD", record.theirCallsign)
        assertEquals("SO-50", record.satelliteName)
        assertEquals("FM", record.displayMode)
        assertEquals("OM60", record.theirGrid)
        assertEquals(listOf("OM60", "OM50"), record.theirVuccGrids)
        assertEquals("OL62TI", record.myGrid)
        assertEquals(listOf("OL62", "OL61"), record.vuccGrids)
        assertTrue(record.lotwConfirmed)
        assertTrue(record.isSatellite)
    }

    @Test
    fun decodeLoTWMultiGridOnlyRecordKeepsPrimaryGridAndDisplaysFullSet() {
        val adi = """
            <EOH>
            <CALL:8>BA8AFK/9
            <QSO_DATE:8>20261014
            <TIME_ON:6>093051
            <PROP_MODE:3>SAT
            <SAT_NAME:5>RS-44
            <VUCC_GRIDS:13>NM84XO,NM94AO
            <LOTW_QSL_RCVD:1>Y
            <EOR>
        """.trimIndent()

        val record = AdifCodec.decode(adi).single()
        assertEquals("NM84", record.theirGrid)
        assertEquals(listOf("NM84", "NM94"), record.theirVuccGrids)
        assertEquals("NM84, NM94", record.displayTheirGrid)
    }

    @Test
    fun displayTheirGridCombinesLegacyPrimaryWithAdditionalVuccGrids() {
        val record = QsoRecord(
            startUtcMillis = 1_760_000_000_000L,
            theirCallsign = "BA8AFK/9",
            myCallsign = "BH2VSQ",
            theirGrid = "NM84",
            theirVuccGrids = listOf("NM94")
        )

        assertEquals("NM84, NM94", record.displayTheirGrid)
        assertEquals("NM84, NM94", record.copy(theirGrid = "", theirVuccGrids = listOf("NM84", "NM94")).displayTheirGrid)
        assertEquals("NM84XO", record.copy(theirGrid = "NM84XO", theirVuccGrids = emptyList()).displayTheirGrid)
    }

    @Test
    fun decodeNormalizesPrimaryAndMultiGridFieldsIntoCompleteVuccSets() {
        val adi = """
            <EOH>
            <CALL:5>K1ABC
            <QSO_DATE:8>20260820
            <TIME_ON:4>1130
            <GRIDSQUARE:6>OM60IL
            <VUCC_GRIDS:13>EN52en,EN53fa
            <MY_GRIDSQUARE:6>OL62TI
            <MY_VUCC_GRIDS:9>OL72,OL73
            <EOR>
        """.trimIndent()

        val record = AdifCodec.decode(adi).single()
        assertEquals("OM60IL", record.theirGrid)
        assertEquals(listOf("OM60", "EN52", "EN53"), record.theirVuccGrids)
        assertEquals("OL62TI", record.myGrid)
        assertEquals(listOf("OL62", "OL72", "OL73"), record.vuccGrids)
    }

    @Test
    fun decodeMultiGridSetsDeduplicateSquaresAndDiscardInvalidLocators() {
        val record = AdifCodec.decodeRecords(listOf(mapOf(
            "CALL" to "K1ABC", "QSO_DATE" to "20260820", "TIME_ON" to "1130",
            "GRIDSQUARE" to "EN52EN",
            "VUCC_GRIDS" to "en52en01, EN53fa, ZZ99, garbage",
            "MY_GRIDSQUARE" to "OL62TI",
            "MY_VUCC_GRIDS" to "ol62ti01, OL63, XX99"
        ))).single()

        assertEquals(listOf("EN52", "EN53"), record.theirVuccGrids)
        assertEquals(listOf("OL62", "OL63"), record.vuccGrids)
    }

    @Test
    fun decodeSingleGridWithoutMultiFieldKeepsLocatorPrecisionAndEmptySets() {
        val record = AdifCodec.decodeRecords(listOf(mapOf(
            "CALL" to "K1ABC", "QSO_DATE" to "20260820", "TIME_ON" to "1130",
            "GRIDSQUARE" to "EN52EN01", "MY_GRIDSQUARE" to "OL62TI"
        ))).single()

        assertEquals("EN52EN01", record.theirGrid)
        assertEquals("OL62TI", record.myGrid)
        assertTrue(record.theirVuccGrids.isEmpty())
        assertTrue(record.vuccGrids.isEmpty())
    }

    @Test
    fun decodeWavelogPullStyleRecordKeepsGridDirections() {
        // Wavelog's export: the OPPOSITE station's grids un-prefixed, the station
        // profile's grid under MY_*. A pull must not flip them into each other.
        val adi = "<EOH><CALL:6>BG5JVM<QSO_DATE:8>20260916<TIME_ON:4>0745" +
            "<VUCC_GRIDS:9>EN52,EN53<MY_GRIDSQUARE:6>OL62TI<MY_VUCC_GRIDS:9>OL62,OL61" +
            "<MODE:2>FM<PROP_MODE:3>SAT<SAT_NAME:5>SO-50<EOR>"
        val record = AdifCodec.decode(adi).single()
        assertEquals(listOf("EN52", "EN53"), record.theirVuccGrids)
        assertEquals("OL62TI", record.myGrid)
        assertEquals(listOf("OL62", "OL61"), record.vuccGrids)
    }

    @Test
    fun encodeOmitsStationCallsignForWavelog() {
        val record = QsoRecord(
            startUtcMillis = 1_700_000_000_000L,
            theirCallsign = "N0CALL",
            myCallsign = "BA7OPF",
            txFrequencyHz = 145_850_000L,
            band = "2M",
            satelliteName = "SO-50"
        )
        val adif = AdifCodec.encode(listOf(record), includeStationCallsign = false)
        assertTrue(!adif.contains("<STATION_CALLSIGN"))
        assertTrue(adif.contains("<CALL:6>N0CALL"))
        assertTrue(adif.contains("<SAT_NAME:5>SO-50"))
    }

    @Test
    fun decodeReadsEveryConfirmationRouteWavelogCounts() {
        // Wavelog's qsl_filter treats lotw / qsl / eqsl alike; the pull must see all three,
        // and an unconfirmed record must stay unconfirmed.
        val adi = "<EOH><CALL:6>BG5JVM<QSO_DATE:8>20260916<TIME_ON:4>0745<LOTW_QSL_RCVD:1>Y<EOR>" +
            "<CALL:6>BG5JVN<QSO_DATE:8>20260916<TIME_ON:4>0746<QSL_RCVD:1>Y<EOR>" +
            "<CALL:6>BG5JVO<QSO_DATE:8>20260916<TIME_ON:4>0747<EQSL_QSL_RCVD:1>Y<EOR>" +
            "<CALL:6>BG5JVP<QSO_DATE:8>20260916<TIME_ON:4>0748<EOR>"
        val records = AdifCodec.decode(adi)
        assertTrue(records[0].isQslConfirmed())
        assertTrue(records[1].isQslConfirmed())
        assertTrue(records[2].isQslConfirmed())
        assertTrue(!records[3].isQslConfirmed())
        assertTrue(records[3].lotwConfirmed.not() && records[3].qslConfirmed.not() && records[3].eqslConfirmed.not())
    }

    @Test
    fun encodeKeepsOwnGridsOutOfTheOppositeVuccField() {
        // The reported defect: an operator's stamped own grid set must never land in
        // VUCC_GRIDS — Wavelog displays that field as the OPPOSITE station's grid.
        val record = QsoRecord(
            startUtcMillis = 1_700_000_000_000L,
            theirCallsign = "N0CALL",
            myCallsign = "BA7OPF",
            myGrid = "OL62TI",
            vuccGrids = listOf("OL62"),
            satelliteName = "SO-50"
        )
        val adif = AdifCodec.encode(listOf(record), includeStationCallsign = false)
        assertTrue(adif.contains("<MY_VUCC_GRIDS:4>OL62"))
        assertFalse(adif.contains("<VUCC_GRIDS:"))
        assertFalse(adif.contains("<GRIDSQUARE:"))
    }

    @Test
    fun encodeWritesTheOppositeMultiGridSetToVuccGrids() {
        val multi = QsoRecord(
            startUtcMillis = 1_700_000_000_000L,
            theirCallsign = "N0CALL",
            myCallsign = "BA7OPF",
            theirGrid = "EN52",
            theirVuccGrids = listOf("EN52", "EN53"),
            satelliteName = "SO-50"
        )
        val multiAdif = AdifCodec.encode(listOf(multi), false)
        assertTrue(multiAdif.contains("<GRIDSQUARE:4>EN52"))
        assertTrue(multiAdif.contains("<VUCC_GRIDS:9>EN52,EN53"))

        // A single opposite grid rides in GRIDSQUARE only; one value inside the VUCC
        // field would trigger Wavelog's single-grid warning in its QSO view.
        val single = multi.copy(theirVuccGrids = listOf("EN52"))
        val singleAdif = AdifCodec.encode(listOf(single), false)
        assertTrue(singleAdif.contains("<GRIDSQUARE:4>EN52"))
        assertFalse(singleAdif.contains("<VUCC_GRIDS:"))
    }
}
