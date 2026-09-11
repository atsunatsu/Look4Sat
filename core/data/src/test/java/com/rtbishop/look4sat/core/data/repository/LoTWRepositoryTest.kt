package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("2M", qso.bandUp)
        assertEquals("70CM", qso.bandDown)
        assertEquals("V/U", qso.bandLabel)
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
    fun parseQsosRejectsBodyWithoutEoh() {
        assertNull(repo.parseConfirmedGridQsos("<HTML>Username/password incorrect</HTML>"))
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
