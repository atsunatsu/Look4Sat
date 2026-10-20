/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoTWRepositoryTest {

    private val repo = LoTWRepository()

    private fun report(vararg records: String): String =
        buildString {
            append("<ADIF_VERS:5>3.1.4\n")
            append("<PROGRAMID:8>look4sat\n")
            append("<eoh>\n")
            records.forEach { append(it).append("\n") }
        }

    @Test
    fun parseQslUpdateWatermarkFromHeader() {
        val body = "<ADIF_VERS:5>3.1.4\n<APP_LoTW_LASTQSL:19>2026-10-20 09:10:11\n<eoh>\n"
        assertEquals("2026-10-20 09:10:11", repo.parseLastQslCursor(body))
        assertNull(repo.parseLastQslCursor(report()))
    }

    @Test
    fun parseRejectsBodyWithoutEoh() {
        assertNull(repo.parseConfirmedGrids("<HTML>Username/password incorrect</HTML>"))
    }

    @Test
    fun parseCollectsOnlySatelliteGrids() {
        // Satellite QSO: PROP_MODE SAT, confirmed, opponent grid.
        val satQso = "<CALL:6>BA7OPF\n" +
            "<QSO_DATE:8>20260820\n" +
            "<PROP_MODE:3>SAT\n" +
            "<SAT_NAME:5>FO-29\n" +
            "<GRIDSQUARE:4>NL47\n" +
            "<EOR>\n"
        // Ground QSO: same grid but no PROP_MODE -> must be excluded.
        val groundQso = "<CALL:6>BA7OPF\n" +
            "<QSO_DATE:8>20260821\n" +
            "<GRIDSQUARE:4>NL47\n" +
            "<EOR>\n"
        val result = repo.parseConfirmedGrids(report(satQso, groundQso))
        assertEquals(setOf("NL47"), result)
    }

    @Test
    fun parseSupportsVuccGridPairsAndSixCharGrids() {
        val satQso = "<PROP_MODE:3>SAT\n" +
            "<SAT_NAME:5>SO-50\n" +
            "<GRIDSQUARE:6>OM60IL\n" +
            "<VUCC_GRIDS:11>EN52en,EN53fa\n" +
            "<EOR>\n"
        val result = repo.parseConfirmedGrids(report(satQso))
        assertEquals(setOf("OM60", "EN52", "EN53"), result)
    }

    @Test
    fun parsePropModeDoesNotLeakAcrossRecords() {
        // PROP_MODE SAT record first, then a ground QSO with a grid — the second
        // record must not inherit the satellite flag after its own <EOR>.
        val satQso = "<PROP_MODE:3>SAT\n<GRIDSQUARE:4>OL62\n<EOR>\n"
        val groundQso = "<GRIDSQUARE:4>PM00\n<EOR>\n"
        val result = repo.parseConfirmedGrids(report(satQso, groundQso))
        assertEquals(setOf("OL62"), result)
    }

    @Test
    fun parseHandlesAlphabeticalFieldOrder() {
        // Real lotwreport.adi emits fields in ALPHABETICAL order: GRIDSQUARE (G)
        // and VUCC_GRIDS come BEFORE PROP_MODE (P) inside each record. The old
        // sequential gate (add only while propMode == SAT) dropped every grid on
        // real reports — the buffered parser must collect them regardless of
        // field order and filter at <EOR>.
        val satQso = "<CALL:6>BA7OPF\n" +
            "<GRIDSQUARE:4>NL47\n" +
            "<MODE:3>FM\n" +
            "<PROP_MODE:3>SAT\n" +
            "<SAT_NAME:5>FO-29\n" +
            "<VUCC_GRIDS:11>EN52en,EN53fa\n" +
            "<EOR>\n"
        val groundQso = "<CALL:6>BA7OPF\n" +
            "<GRIDSQUARE:4>PM95\n" +
            "<QSO_DATE:8>20260821\n" +
            "<EOR>\n"
        val result = repo.parseConfirmedGrids(report(satQso, groundQso))
        assertEquals(setOf("NL47", "EN52", "EN53"), result)
    }

    @Test
    fun parseReturnsEmptySetForReportWithoutGrids() {
        val satQso = "<PROP_MODE:3>SAT\n<SAT_NAME:5>AO-07\n<EOR>\n"
        assertEquals(emptySet<String>(), repo.parseConfirmedGrids(report(satQso)))
    }

    // region parseConfirmedGridQsos

    private val satQsoFull = "<CALL:5>A50QO\n" +
        "<QSO_DATE:8>20260820\n" +
        "<TIME_ON:6>113045\n" +
        "<PROP_MODE:3>SAT\n" +
        "<SAT_NAME:5>FO-29\n" +
        "<MODE:2>CW\n" +
        "<BAND:3>70CM\n" +
        "<BAND_RX:3>2M\n" +
        "<GRIDSQUARE:4>NL47\n" +
        "<EOR>\n"

    @Test
    fun parseQsosExtractsSatelliteDetail() {
        val result = repo.parseConfirmedGridQsos(report(satQsoFull))!!
        val qsos = result["NL47"]!!
        assertEquals(1, qsos.size)
        val qso = qsos[0]
        assertEquals("A50QO", qso.call)
        assertEquals("FO-29", qso.satName)
        assertEquals("CW", qso.mode)
        // ADIF BAND is the transmitted (uplink) band, BAND_RX the received (downlink) one.
        // Confirmed against the live report of the own uploads: SO-50 QSOs arrive as
        // BAND=2M/FREQ=145.85000 + BAND_RX=70CM/FREQ_RX=436.80500, i.e. BAND=uplink.
        assertEquals("70CM", qso.bandUp)
        assertEquals("2M", qso.bandDown)
        assertEquals("U/V", qso.bandLabel)
        assertEquals(2026, java.time.Instant.ofEpochMilli(qso.epochMs).atZone(java.time.ZoneOffset.UTC).year)
    }

    @Test
    fun parseQsosExcludesGroundQsos() {
        val groundQso = "<CALL:6>BA7OPF\n<QSO_DATE:8>20260821\n<GRIDSQUARE:4>NL47\n<EOR>\n"
        val result = repo.parseConfirmedGridQsos(report(satQsoFull, groundQso))!!
        assertEquals(1, result["NL47"]!!.size)
    }

    @Test
    fun parseQsosVuccPairFeedsBothGrids() {
        val qso = "<PROP_MODE:3>SAT\n<SAT_NAME:5>SO-50\n" +
            "<QSO_DATE:8>20260819\n<TIME_ON:4>1231\n" +
            "<VUCC_GRIDS:11>EN52en,EN53fa\n<EOR>\n"
        val result = repo.parseConfirmedGridQsos(report(qso))!!
        assertEquals(setOf("EN52", "EN53"), result.keys)
        assertEquals(result["EN52"]!!.first(), result["EN53"]!!.first())
    }

    @Test
    fun parseQsosVuccQuadrupleFeedsAllFourGrids() {
        // VUCC allows up to four grids in one contact.
        val qso = "<PROP_MODE:3>SAT\n<SAT_NAME:5>SO-50\n" +
            "<QSO_DATE:8>20260819\n<TIME_ON:4>1231\n" +
            "<VUCC_GRIDS:23>EN52en,EN53fa,EN42gj,EN43kh\n<EOR>\n"
        val result = repo.parseConfirmedGridQsos(report(qso))!!
        assertEquals(setOf("EN52", "EN53", "EN42", "EN43"), result.keys)
        result.values.forEach { assertEquals(1, it.size) }
    }

    @Test
    fun parseQsosCapturesOppositeGridSet() {
        // The opposite station's grids (GRIDSQUARE + VUCC_GRIDS) must ride on the
        // GridQso so the logbook can show multi-grid confirmations in its QSL slot.
        val single = "<PROP_MODE:3>SAT\n<SAT_NAME:5>FO-29\n<GRIDSQUARE:4>NL47\n<EOR>\n"
        assertEquals(
            listOf("NL47"),
            repo.parseConfirmedGridQsos(report(single))!!["NL47"]!!.first().theirGrids
        )
        val pair = "<PROP_MODE:3>SAT\n<SAT_NAME:5>SO-50\n<VUCC_GRIDS:11>EN52en,EN53fa\n<EOR>\n"
        assertEquals(
            listOf("EN52", "EN53"),
            repo.parseConfirmedGridQsos(report(pair))!!["EN52"]!!.first().theirGrids
        )
        // A 6-char GRIDSQUARE plus a VUCC pair: every field, truncated to 4 chars, in order.
        val six = "<PROP_MODE:3>SAT\n<SAT_NAME:5>SO-50\n" +
            "<GRIDSQUARE:6>OM60IL\n<VUCC_GRIDS:11>EN52en,EN53fa\n<EOR>\n"
        assertEquals(
            listOf("OM60", "EN52", "EN53"),
            repo.parseConfirmedGridQsos(report(six))!!["OM60"]!!.first().theirGrids
        )
    }

    @Test
    fun parseQsosRejectsBodyWithoutEoh() {
        assertNull(repo.parseConfirmedGridQsos("<HTML>Username/password incorrect</HTML>"))
    }

    @Test
    fun parseQsosCapturesOwnGridPerQso() {
        // MY_GRIDSQUARE arrives BEFORE PROP_MODE (ADIF fields are emitted
        // alphabetically); the per-record buffer must still attach it to the QSO.
        val qso1 = "<CALL:5>A50QO\n<QSO_DATE:8>20260820\n<MY_GRIDSQUARE:4>OL62\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>FO-29\n<MODE:2>CW\n" +
            "<BAND:3>70CM\n<BAND_RX:3>2M\n<GRIDSQUARE:4>NL47\n<EOR>\n"
        val qso2 = "<CALL:6>BG7ZFK\n<QSO_DATE:8>20260819\n<MY_GRIDSQUARE:4>OL72\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>SO-50\n<MODE:3>FM\n" +
            "<BAND:3>70CM\n<BAND_RX:3>2M\n<GRIDSQUARE:4>OK48\n<EOR>\n"
        val result = repo.parseConfirmedGridQsos(report(qso1, qso2))!!
        assertEquals("OL62", result["NL47"]!!.first().myGrid)
        assertEquals("OL72", result["OK48"]!!.first().myGrid)
        // Ground QSO (no PROP_MODE=SAT) must not leak its MY_GRIDSQUARE.
        val ground = "<CALL:6>BA7OPF\n<QSO_DATE:8>20260821\n<MY_GRIDSQUARE:4>OL62\n<GRIDSQUARE:4>NL47\n<EOR>\n"
        assertTrue(repo.parseConfirmedGridQsos(report(qso1, ground))!!.values.all { it.all { q -> q.myGrid == "OL62" } })
    }

    @Test
    fun parseQsosCapturesMyVuccGridsMultiGridStation() {
        // A station location (台址) can span several grids: LoTW emits
        // <MY_VUCC_GRIDS> (comma-separated) with MY_GRIDSQUARE absent. Both
        // grids must land in myGrids, and myGrid (back-compat) = the first.
        val qso = "<CALL:5>A50QO\n<QSO_DATE:8>20260820\n<TIME_ON:4>1130\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>RS-44\n<MODE:2>CW\n" +
            "<MY_VUCC_GRIDS:11>OM60,OM50\n<GRIDSQUARE:4>NL47\n<EOR>\n"
        val result = repo.parseConfirmedGridQsos(report(qso))!!
        val parsed = result["NL47"]!!.first()
        assertEquals(setOf("OM60", "OM50"), parsed.myGrids)
        assertEquals("OM60", parsed.myGrid)
        // 6-char fields are truncated to 4 like every other grid path.
        val six = "<PROP_MODE:3>SAT\n<SAT_NAME:5>RS-44\n" +
            "<MY_VUCC_GRIDS:17>OM60IL70,OM50MA20\n<GRIDSQUARE:4>OK48\n<EOR>\n"
        assertEquals(setOf("OM60", "OM50"), repo.parseConfirmedGridQsos(report(six))!!["OK48"]!!.first().myGrids)
    }

    @Test
    fun parseQsosKeepsCompleteOwnAndCounterpartGridSetsAndStationIdentity() {
        val qso = "<CALL:5>K1ABC\n<QSO_DATE:8>20260820\n<TIME_ON:4>1130\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>FO-29\n<MODE:2>CW\n" +
            "<GRIDSQUARE:6>OM60IL\n<VUCC_GRIDS:13>EN52en,EN53fa\n" +
            "<MY_GRIDSQUARE:4>OL62\n<MY_VUCC_GRIDS:9>OL72,OL73\n" +
            "<STATION_CALLSIGN:6>BA7OPF\n<MY_DXCC:3>318\n<EOR>\n"
        val body = report(qso)
        val result = repo.parseConfirmedGridQsos(body)!!

        assertEquals(setOf("OM60", "EN52", "EN53"), repo.parseConfirmedGrids(body))
        assertEquals(setOf("OM60", "EN52", "EN53"), result.keys)
        assertEquals(setOf("OL62", "OL72", "OL73"), repo.parseRoamedGrids(body))
        val detail = result.getValue("EN52").single()
        assertEquals("OL62", detail.myGrid)
        assertEquals(setOf("OL62", "OL72", "OL73"), detail.myGrids)
        assertEquals(listOf("OM60", "EN52", "EN53"), detail.theirGrids)
        assertEquals("BA7OPF", detail.myCallsign)
        assertEquals("BA7OPF|318|||||", detail.stationKey)
    }

    @Test
    fun parseQsosBuildsStationKeyFromMyFields() {
        // 台址 identity = the MY_* station snapshot (callsign + dxcc + state +
        // CQ/ITU + IOTA + country). A record with the full snapshot yields the
        // stable key the operated-grid selector groups by.
        val qso = "<CALL:5>A50QO\n<QSO_DATE:8>20260820\n<TIME_ON:4>1130\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>RS-44\n" +
            "<STATION_CALLSIGN:6>BH6RJD\n<MY_DXCC:3>318\n<MY_STATE:11>JS // Jiangsu\n" +
            "<MY_CQ_ZONE:2>24\n<MY_ITU_ZONE:2>44\n<MY_COUNTRY:5>CHINA\n" +
            "<GRIDSQUARE:4>NL47\n<EOR>\n"
        val parsed = repo.parseConfirmedGridQsos(report(qso))!!["NL47"]!!.first()
        assertEquals("BH6RJD", parsed.myCallsign)
        assertEquals("BH6RJD|318|JS|24|44||CHINA", parsed.stationKey)
    }

    @Test
    fun parseQsosStationKeyDistinguishesLocationsUnderSameCallsign() {
        // One callsign can own several 台址 (e.g. BH6RJD with a Hubei location
        // and a Zhejiang location); different MY_STATE -> different stationKey.
        val hb = "<CALL:5>A50QO\n<QSO_DATE:8>20260820\n<TIME_ON:4>1130\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>RS-44\n" +
            "<STATION_CALLSIGN:6>BH6RJD\n<MY_STATE:11>HB // Hubei\n<GRIDSQUARE:4>NL47\n<EOR>\n"
        val zj = "<CALL:5>BG7ZFK\n<QSO_DATE:8>20260819\n<TIME_ON:4>1130\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>SO-50\n" +
            "<STATION_CALLSIGN:6>BH6RJD\n<MY_STATE:11>ZJ // Zhejiang\n<GRIDSQUARE:4>OK48\n<EOR>\n"
        val result = repo.parseConfirmedGridQsos(report(hb, zj))!!
        assertTrue(result["NL47"]!!.first().stationKey != result["OK48"]!!.first().stationKey)
    }

    // endregion

    // region parseRoamedGrids (MY_GRIDSQUARE = grids the account operated from)

    @Test
    fun parseRoamedGridsCollectsOwnStationGrids() {
        // Satellite QSOs carry MY_GRIDSQUARE = the grid the account itself
        // operated from (the "roamed/activated" set shown as blue stripes).
        val qso1 = "<CALL:6>BA7OPF\n<QSO_DATE:8>20260820\n<PROP_MODE:3>SAT\n<SAT_NAME:5>IO-86\n" +
            "<MY_GRIDSQUARE:4>OL62\n<GRIDSQUARE:4>PM95\n<EOR>\n"
        val qso2 = "<CALL:6>BA7OPF\n<QSO_DATE:8>20260901\n<PROP_MODE:3>SAT\n<SAT_NAME:5>IO-86\n" +
            "<MY_GRIDSQUARE:4>OL72\n<GRIDSQUARE:4>NL47\n<EOR>\n"
        assertEquals(setOf("OL62", "OL72"), repo.parseRoamedGrids(report(qso1, qso2)))
    }

    @Test
    fun parseRoamedGridsIgnoresGroundQsos() {
        val ground = "<CALL:6>BA7OPF\n<QSO_DATE:8>20260821\n<MY_GRIDSQUARE:4>OL72\n<EOR>\n"
        assertEquals(emptySet<String>(), repo.parseRoamedGrids(report(ground)))
    }

    @Test
    fun parseRoamedGridsDoesNotConfuseGridsquareWithMyGridsquare() {
        // GRIDSQUARE (opposite station's grid) must never leak into the roamed set.
        val qso = "<PROP_MODE:3>SAT\n<SAT_NAME:5>SO-50\n<MY_GRIDSQUARE:4>OL62\n" +
            "<GRIDSQUARE:6>OM60IL\n<EOR>\n"
        assertEquals(setOf("OL62"), repo.parseRoamedGrids(report(qso)))
    }

    @Test
    fun parseRoamedGridsTruncatesSixCharToFour() {
        val qso = "<PROP_MODE:3>SAT\n<SAT_NAME:5>IO-86\n<MY_GRIDSQUARE:6>OL62AA\n<EOR>\n"
        assertEquals(setOf("OL62"), repo.parseRoamedGrids(report(qso)))
    }

    @Test
    fun parseRoamedGridsHandlesAlphabeticalFieldOrder() {
        // Real lotwreport.adi emits fields alphabetically, so <MY_GRIDSQUARE>
        // (M) arrives BEFORE <PROP_MODE> (P). The old sequential gate (add only
        // while propMode == SAT) silently returned an EMPTY set on real reports —
        // this is the exact bug that made the blue roamed stripes never appear.
        val qso = "<CALL:6>BA7OPF\n" +
            "<GRIDSQUARE:4>PM95\n" +
            "<MY_GRIDSQUARE:6>OL72XX\n" +
            "<PROP_MODE:3>SAT\n" +
            "<SAT_NAME:5>IO-86\n" +
            "<EOR>\n"
        assertEquals(setOf("OL72"), repo.parseRoamedGrids(report(qso)))
    }

    @Test
    fun parseRoamedGridsRejectsBodyWithoutEoh() {
        assertNull(repo.parseRoamedGrids("<HTML>Username/password incorrect</HTML>"))
    }

    @Test
    fun parseRoamedGridsCollectsMyVuccGrids() {
        // Multi-grid 台址: MY_VUCC_GRIDS carries the extra grids (MY_GRIDSQUARE
        // may be absent); every field must reach the roamed set for the stripes.
        val qso = "<PROP_MODE:3>SAT\n<SAT_NAME:5>RS-44\n" +
            "<MY_VUCC_GRIDS:11>OM60,OM50\n<GRIDSQUARE:4>NL47\n<EOR>\n"
        assertEquals(setOf("OM60", "OM50"), repo.parseRoamedGrids(report(qso)))
        // Combined with MY_GRIDSQUARE the union is kept.
        val both = "<PROP_MODE:3>SAT\n<SAT_NAME:5>RS-44\n" +
            "<MY_GRIDSQUARE:4>OL62\n<MY_VUCC_GRIDS:11>OM60,OM50\n<GRIDSQUARE:4>NL47\n<EOR>\n"
        assertEquals(setOf("OL62", "OM60", "OM50"), repo.parseRoamedGrids(report(both)))
    }

    @Test
    fun parseRoamedGridsUppercasesLowercaseMyGrid() {
        // LoTW emits the <eor> tag lowercase, so field CASE is not guaranteed:
        // a lowercase MY_GRIDSQUARE must still match the overlay's uppercase
        // labels (regression: take(4) without .uppercase() silently dropped
        // stripes for lowercase grids).
        val qso = "<PROP_MODE:3>SAT\n<SAT_NAME:5>IO-86\n" +
            "<MY_GRIDSQUARE:4>ol62\n<GRIDSQUARE:4>PM95\n<EOR>\n"
        assertEquals(setOf("OL62"), repo.parseRoamedGrids(report(qso)))
    }

    // endregion

    // region failure classification (fetchReportBody -> LoTWResult mapping)

    @Test
    fun failureClassificationMapsEachException() {
        // LoTWRepository.toResult() must keep each failure cause distinct so
        // the UI can show a specific message per cause.
        assertEquals(
            LoTWResult.BadCredentials,
            repo.toResult(LoTWRepository.CredentialsException())
        )
        assertEquals(
            LoTWResult.RateLimited,
            repo.toResult(LoTWRepository.RateLimitException())
        )
        assertEquals(
            LoTWResult.Timeout,
            repo.toResult(LoTWRepository.TimeoutException("read timed out"))
        )
        val net = repo.toResult(java.io.IOException("HTTP 500"))
        assertEquals(LoTWResult.NetworkError("HTTP 500"), net)
    }


    @Test
    fun parseQsosExtractsAwardFields() {
        // Award statistics rely on the DXCC/CQZ/STATE fields coming straight
        // from LoTW's qso_qsldetail report (STATE depends on DXCC). LoTW
        // writes STATE as "CODE // NAME" (verified with a real report 2026-09);
        // the parser must strip the name suffix and keep the bare code.
        val qso = "<CALL:5>BG7XYZ\n" +
            "<QSO_DATE:8>20260820\n<TIME_ON:4>1130\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>FO-29\n<MODE:2>CW\n" +
            "<DXCC:3>318\n<COUNTRY:5>CHINA\n<CQZ:2>24\n<STATE:13>GD // Guangdong\n" +
            "<GRIDSQUARE:4>OL62\n<EOR>\n"
        val result = repo.parseConfirmedGridQsos(report(qso))!!
        val parsed = result["OL62"]!!.first()
        assertEquals(318, parsed.dxcc)
        assertEquals("CHINA", parsed.country)
        assertEquals(24, parsed.cqz)
        assertEquals("GD", parsed.state)
    }

    @Test
    fun parseQsosStripsStateNameSuffixForAllEntities() {
        // Japan prefecture: "34 // Tottori-ken" -> "34" (WAJA matching needs
        // the 2-digit code). US states also arrive as "CODE // Name".
        val jp = "<CALL:5>JH0ABC\n<QSO_DATE:8>20260820\n<TIME_ON:4>1130\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>RS-44\n<DXCC:3>339\n<COUNTRY:7>JAPAN\n" +
            "<STATE:18>34 // Tottori-ken\n<GRIDSQUARE:4>PM95\n<EOR>\n"
        val us = "<CALL:5>K1ABC\n<QSO_DATE:8>20260820\n<TIME_ON:4>1130\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>AO-07\n<DXCC:3>291\n<COUNTRY:12>UNITED STATES\n" +
            "<STATE:22>CA // California\n<GRIDSQUARE:4>EM40\n<EOR>\n"
        val result = repo.parseConfirmedGridQsos(report(jp, us))!!
        assertEquals("34", result["PM95"]!!.first().state)
        assertEquals("CA", result["EM40"]!!.first().state)
    }

    @Test
    fun parseQsosLeavesAwardFieldsNullWhenAbsent() {
        val qso = "<CALL:5>JH0ABC\n<QSO_DATE:8>20260820\n<TIME_ON:4>1130\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>RS-44\n<GRIDSQUARE:4>PM95\n<EOR>\n"
        val result = repo.parseConfirmedGridQsos(report(qso))!!
        val parsed = result["PM95"]!!.first()
        assertNull(parsed.dxcc)
        assertNull(parsed.country)
        assertNull(parsed.cqz)
        assertNull(parsed.state)
    }

    @Test
    fun parseQsosAwardFieldsDoNotLeakAcrossRecords() {
        val withFields = "<CALL:5>BG7AAA\n<QSO_DATE:8>20260820\n<TIME_ON:4>1130\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>FO-29\n<DXCC:3>318\n<CQZ:2>24\n<STATE:2>GD\n" +
            "<GRIDSQUARE:4>OL62\n<EOR>\n"
        val withoutFields = "<CALL:5>JH0BBB\n<QSO_DATE:8>20260821\n<TIME_ON:4>1130\n" +
            "<PROP_MODE:3>SAT\n<SAT_NAME:5>RS-44\n<GRIDSQUARE:4>PM95\n<EOR>\n"
        val result = repo.parseConfirmedGridQsos(report(withFields, withoutFields))!!
        assertNull(result["PM95"]!!.first().dxcc)
        assertNull(result["PM95"]!!.first().cqz)
        assertNull(result["PM95"]!!.first().state)
    }

    // endregion
}
