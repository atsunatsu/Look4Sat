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

    // endregion
}
