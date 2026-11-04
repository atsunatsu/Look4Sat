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
