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

import com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the `get_contacts_adif` response envelope (transport-level wiring is exercised on device). */
class WavelogSyncRepositoryTest {

    private val repository = WavelogSyncRepository()

    @Test
    fun successfulPageParsesRecordsAndCursor() {
        val adif = "<ADIF_VER:5>3.1.7\n<EOH>\n" +
            "<QSO_DATE:8>20260916<TIME_ON:4>0745<CALL:6>BG5JSB<GRIDSQUARE:4>EN52" +
            "<MODE:2>FM<PROP_MODE:3>SAT<SAT_NAME:5>SO-50<EOR>\n"
        val text = JSONObject()
            .put("status", "successful")
            .put("lastfetchedid", 42)
            .put("exported_qsos", 1)
            .put("adif", adif)
            .toString()

        val page = repository.parsePage(text)

        assertNotNull(page)
        assertEquals(42L, page!!.cursor)
        assertEquals(1, page.exported)
        assertEquals(1, page.records.size)
        assertEquals("BG5JSB", page.records.single().theirCallsign)
        assertEquals("EN52", page.records.single().theirGrid)
    }

    @Test
    fun downloadedAdifRetainsTransmitAndReceiveFrequencies() {
        val adif = "<ADIF_VER:5>3.1.7<EOH>" +
            "<QSO_DATE:8>20260916<TIME_ON:4>0745<CALL:6>BG5JSB" +
            "<FREQ:7>145.850<FREQ_RX:7>436.795" +
            "<MODE:2>FM<PROP_MODE:3>SAT<SAT_NAME:5>SO-50<EOR>"
        val page = repository.parsePage(JSONObject()
            .put("status", "successful")
            .put("lastfetchedid", 1)
            .put("exported_qsos", 1)
            .put("adif", adif)
            .toString())!!

        assertEquals(145_850_000L, page.records.single().txFrequencyHz)
        assertEquals(436_795_000L, page.records.single().rxFrequencyHz)
    }

    @Test
    fun fetchLoTWConfirmationsFollowsPagesAndFetchesAnOldQsoByExactId() = runBlocking {
        val confirmedAdif = "<EOH><QSO_DATE:8>20260916<TIME_ON:4>0745<CALL:6>BG5JSB" +
            "<LOTW_QSL_RCVD:1>Y<LOTW_QSLRDATE:8>20261119<EOR>"
        var hydrationRequests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("/confirmation")) {
                assertEquals("Bearer wl2_test", request.header("Authorization"))
                assertEquals("lotw", request.url.queryParameter("type"))
                assertEquals("2026-11-18", request.url.queryParameter("since"))
                assertEquals("2", request.url.queryParameter("station_id"))
                val first = request.url.queryParameter("page") == "1"
                """{"data":[{"qso_id":42}],"meta":{"has_more":$first}}"""
            } else {
                hydrationRequests++
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                val payload = JSONObject(buffer.readUtf8())
                assertEquals(41L, payload.getLong("fetchfromid"))
                assertEquals(1, payload.getInt("limit"))
                assertEquals("2", payload.getJSONArray("station_id").getString(0))
                JSONObject().put("status", "successful").put("lastfetchedid", 42)
                    .put("exported_qsos", 1).put("adif", confirmedAdif).toString()
            }
            Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody())
                .build()
        }.build()

        val records = WavelogSyncRepository(client).fetchLoTWConfirmations(
            "https://radio.example", "wl_test", "wl2_test",
            listOf(WavelogStationInfo(id = "2", name = "Station", callsign = "BA7OPF", grid = "OL62", active = true)),
            "2026-11-18"
        )

        assertEquals(1, hydrationRequests)
        val record = records!!.single()
        assertEquals("2", record.wavelogStation)
        assertTrue(record.lotwConfirmed)
        assertEquals("20261119", record.lotwQslDate)
    }

    @Test
    fun failedStatusIsRejected() {
        assertNull(repository.parsePage("""{"status":"failed","reason":"missing api key"}"""))
    }

    @Test
    fun missingCursorIsRejected() {
        assertNull(repository.parsePage("""{"status":"successful","exported_qsos":0,"adif":null}"""))
        assertNull(repository.parsePage("not json at all"))
    }

    @Test
    fun emptyExportKeepsTheCursorAndYieldsNoRecords() {
        val page = repository.parsePage("""{"status":"successful","lastfetchedid":7,"exported_qsos":0,"adif":null}""")

        assertNotNull(page)
        assertEquals(7L, page!!.cursor)
        assertTrue(page.records.isEmpty())
    }
}
