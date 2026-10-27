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
import com.rtbishop.look4sat.core.domain.repository.LoTWOperationException
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * previewCertificate parses a .p12 without touching storage — just enough to
 * learn the certificate's DXCC entity, so the settings dialog can offer the
 * entity's Province/State dropdown before the operator confirms the import.
 *
 * Fixture test_tqsl_empty.p12 mirrors a modern TQSL export: PBES2/AES-256,
 * empty password, TQSL subject attribute 1.3.6.1.4.1.12348.1.1 = BA7OPF and
 * extensions .2/.3/.4 = first/last QSO date, DXCC 318 (China).
 */
class LoTWUploadRepositoryPreviewTest {

    private class MemStorage : LoTWStorage {
        val files = mutableMapOf<String, ByteArray>()
        // Reads hand out their own copy, like the file-backed storage does: several call sites
        // wipe what they read, and that must not corrupt the stored bytes.
        override fun read(name: String): ByteArray? = files[name]?.copyOf()
        override fun write(name: String, data: ByteArray) { files[name] = data.copyOf() }
        override fun delete(name: String) { files.remove(name) }
    }

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun repository(storage: MemStorage) = LoTWUploadRepository(
        storage, { error("station config not needed") }, now = { System.currentTimeMillis() }
    )

    @Test
    fun `preview parses TQSL-style certificate without persisting it`() {
        runBlocking {
            val storage = MemStorage()
            val repo = repository(storage)
            val info = repo.previewCertificate(fixture("test_tqsl_empty.p12"), charArrayOf())
            assertEquals("BA7OPF", info.callsign)
            assertEquals(318, info.dxcc)
            assertEquals("2026-01-01", info.firstQsoDate)
            assertTrue("nothing written", storage.files.isEmpty())
            assertNull("no certificate stored", repo.certificate())
        }
    }

    @Test
    fun `wrong password fails as certificate password and wipes the attempt`() {
        runBlocking {
            val storage = MemStorage()
            val repo = repository(storage)
            val password = "definitely-wrong".toCharArray()
            val error = assertThrows(LoTWOperationException::class.java) {
                runBlocking { repo.previewCertificate(fixture("test_pbes2.p12"), password) }
            }
            assertEquals(LoTWProblem.CERTIFICATE_PASSWORD, error.reason)
            assertTrue("password wiped", password.all { it == '\u0000' })
            assertTrue("nothing written", storage.files.isEmpty())
        }
    }

    /**
     * Records logged before a certificate was installed (blank own callsign) or under another
     * certificate are signed with the certificate's callsign instead of being refused, and the
     * preview reports how many they were.
     */
    @Test
    fun `records without a matching callsign upload under the certificate and are reported`() {
        runBlocking {
            val storage = MemStorage()
            val repo = LoTWUploadRepository(
                storage,
                { LoTWConfig(File("src/main/assets/lotw/config.tq6").inputStream()) },
                now = { System.currentTimeMillis() }
            )
            repo.importCertificate(fixture("test_tqsl_empty.p12"), charArrayOf())
            repo.saveStation(LoTWStation(grid = "OL62TI"))
            val start = 1_787_000_000_000L // 2026-08, inside the fixture certificate's QSO range
            val preview = repo.prepare(
                listOf(
                    sampleRecord(start, "BH6RJD", ""),
                    sampleRecord(start + 60_000L, "BG7QBL", "XX0YY"),
                    sampleRecord(start + 120_000L, "BA8BLK", "BA7OPF")
                ),
                resubmit = false
            )
            // The blank record is signed with the certificate's callsign; the one naming another
            // callsign is refused and offered up for a rewrite instead.
            assertEquals(2, preview.count)
            assertEquals(1, preview.missingCallsign)
            assertEquals(listOf(start + 60_000L), preview.callsignConflicts)
            assertEquals(1, preview.unavailableReasons[LoTWProblem.CALLSIGN_MISMATCH])
        }
    }

    /** Nothing signable at all: the preview still comes back (count 0) with the conflicts listed,
     *  so the UI can offer the rewrite / use-another-certificate actions instead of a dead end. */
    @Test
    fun `fully blocked batch still returns a preview with the conflicts`() {
        runBlocking {
            val storage = MemStorage()
            val repo = LoTWUploadRepository(
                storage,
                { LoTWConfig(File("src/main/assets/lotw/config.tq6").inputStream()) },
                now = { System.currentTimeMillis() }
            )
            repo.importCertificate(fixture("test_tqsl_empty.p12"), charArrayOf())
            repo.saveStation(LoTWStation(grid = "OL62TI"))
            val start = 1_787_000_000_000L
            val preview = repo.prepare(
                listOf(
                    sampleRecord(start, "BH6RJD", "XX0YY"),
                    sampleRecord(start + 60_000L, "BG7QBL", "XX0ZZ")
                ),
                resubmit = false
            )
            assertEquals(0, preview.count)
            assertEquals(2, preview.unavailableSkipped)
            assertEquals(listOf(start, start + 60_000L), preview.callsignConflicts)
            assertEquals(2, preview.unavailableReasons[LoTWProblem.CALLSIGN_MISMATCH])
            assertEquals(0, preview.missingCallsign)
            // No payload was prepared: confirming this preview must fail as expired, not crash.
            assertTrue(repo.upload(preview.id) is LoTWUploadResult.ExpiredPreview)
        }
    }

    /** The mismatch reason names both callsigns (record vs certificate). The old detail showed the
     *  opposite station's call, which told the operator nothing about which side to fix. */
    @Test
    fun `callsign mismatch detail names the record and the certificate callsign`() {
        runBlocking {
            val storage = MemStorage()
            val repo = LoTWUploadRepository(
                storage,
                { LoTWConfig(File("src/main/assets/lotw/config.tq6").inputStream()) },
                now = { System.currentTimeMillis() }
            )
            repo.importCertificate(fixture("test_tqsl_empty.p12"), charArrayOf())
            repo.saveStation(LoTWStation(grid = "OL62TI"))
            val audit = repo.audit(listOf(sampleRecord(1_787_000_000_000L, "BH6RJD", "XX0YY")))
            assertEquals("XX0YY ≠ BA7OPF", audit.details[LoTWProblem.CALLSIGN_MISMATCH])
        }
    }

    @Test
    fun `explicit LoTW rejection is preserved even with an HTTP error status`() {
        val result = parseLoTWUploadResponse(
            "<!-- .UPL. rejected --><!-- .UPLMESSAGE. Invalid callsign -->",
            count = 1,
            httpStatus = 500
        )
        assertTrue(result is LoTWUploadResult.Rejected)
        assertEquals("Invalid callsign", (result as LoTWUploadResult.Rejected).message)
    }

    @Test
    fun `unrecognized LoTW response retains status and diagnostic summary`() {
        val result = parseLoTWUploadResponse("<html>Gateway error</html>", count = 2, httpStatus = 502)
        assertTrue(result is LoTWUploadResult.Unknown)
        val unknown = result as LoTWUploadResult.Unknown
        assertEquals(502, unknown.httpStatus)
        assertTrue(unknown.technicalDetail.contains("Gateway error"))
    }

    private fun sampleRecord(start: Long, call: String, myCallsign: String) = QsoRecord(
        id = start,
        startUtcMillis = start,
        endUtcMillis = start,
        theirCallsign = call,
        myCallsign = myCallsign,
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
}
