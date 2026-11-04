/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
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
package com.rtbishop.look4sat.core.data.lotw

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import java.io.File
import java.security.Signature
import java.util.Base64
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the complete station grid set in the signed TQ8 payload. */
class LoTWSignerTest {

    private val config = LoTWConfig(File("src/main/assets/lotw/config.tq6").inputStream())

    private fun assertStationGridSetIsSigned(gridSet: String) {
        val keyBytes = javaClass.classLoader!!.getResourceAsStream("test_tqsl_empty.p12")!!.use { it.readBytes() }
        val key = LoTWKeyMaterial.read(keyBytes, charArrayOf(), System.currentTimeMillis())
        val station = config.stationFields(LoTWStation(grid = gridSet), key.info.dxcc)
        val start = 1_787_000_000_000L
        val record = QsoRecord(
            id = start,
            startUtcMillis = start,
            endUtcMillis = start,
            theirCallsign = "BH6RJD",
            myCallsign = key.info.callsign,
            myGrid = "OL62TI",
            txFrequencyHz = 145_850_000L,
            rxFrequencyHz = 436_795_000L,
            band = "2M",
            rxBand = "70CM",
            mode = "FM",
            satelliteName = "SO-50",
            propagationMode = "SAT",
            status = QsoStatus.COMPLETE
        )
        val signer = LoTWSigner(config)
        val contact = signer.contact(record, key, station, start + 60_000L)
        val payload = GZIPInputStream(signer.sign(listOf(contact), key, station).inputStream())
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

        assertTrue(payload.contains("<GRIDSQUARE:${gridSet.length}>$gridSet"))
        assertFalse(payload.contains("MY_VUCC_GRIDS"))
        assertTrue(contact.signData.startsWith(gridSet))
        assertTrue(
            payload.contains(
                "<SIGNDATA:${contact.signData.toByteArray(Charsets.UTF_8).size}>${contact.signData}"
            )
        )

        // Verify that the signature in the generated TQ8 covers the full comma-separated grid set.
        val encodedSignature = payload.substringAfter("<SIGN_LOTW_V2.0:")
            .substringAfter(">")
            .substringBefore("<SIGNDATA:")
            .filterNot(Char::isWhitespace)
        val signature = Base64.getDecoder().decode(encodedSignature)
        val valid = Signature.getInstance("SHA1withRSA").run {
            initVerify(key.certificate.publicKey)
            update(contact.signData.toByteArray(Charsets.UTF_8))
            verify(signature)
        }
        assertTrue("TQ8 signature must verify against the full station grid set", valid)
    }

    @Test
    fun `two-grid boundary pair is serialized and signed in full`() {
        assertStationGridSetIsSigned("OM60,OM61")
    }

    @Test
    fun `four-grid intersection is serialized and signed in full`() {
        assertStationGridSetIsSigned("OM90,OM91,PM00,PM01")
    }
}
