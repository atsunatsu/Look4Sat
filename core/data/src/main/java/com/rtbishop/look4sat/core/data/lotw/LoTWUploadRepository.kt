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
package com.rtbishop.look4sat.core.data.lotw

import android.content.Context
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.satelliteIdentity
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.repository.ILoTWUploadRepository
import com.rtbishop.look4sat.core.domain.repository.LoTWCertificate
import com.rtbishop.look4sat.core.domain.repository.LoTWOperationException
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.domain.repository.LoTWStationMeta
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadAudit
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadPreview
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

private fun readBoundedBody(response: Response, limit: Int): String = response.body.charStream().use { reader ->
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        val count = reader.read(buffer)
        if (count < 0) break
        require(result.length <= limit - count) { "Response exceeds size limit" }
        result.append(buffer, 0, count)
    }
    result.toString()
}

class LoTWUploadRepository internal constructor(
    private val storage: LoTWStorage,
    private val config: () -> LoTWConfig,
    client: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = System::currentTimeMillis
) : ILoTWUploadRepository {
    constructor(context: Context) : this(AndroidLoTWStorage(context), { LoTWConfig(context.assets.open("lotw/config.tq6")) })
    private val client = client.newBuilder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val mutex = Mutex()
    @Volatile private var pending: Pending? = null
    private data class Pending(val preview: LoTWUploadPreview, val data: ByteArray, val hashes: List<String>, val created: Long)
    private data class SigningContext(val key: LoTWKeyMaterial, val signer: LoTWSigner, val location: Map<String, String>)

    override suspend fun certificate(): LoTWCertificate? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = storage.read("certificate") ?: return@withLock null
            try {
                readBundle(bytes).let { bundle ->
                    bundle.p12.fill(0)
                    bundle.password?.fill(0)
                    bundle.info.copy(passwordSaved = bundle.password != null)
                }
            } finally { bytes.fill(0) }
        }
    }

    override suspend fun importCertificate(data: ByteArray, password: CharArray): LoTWCertificate = withContext(Dispatchers.IO) {
        mutex.withLock {
            val passwordBytes = encodePassword(password)
            try {
                if (passwordBytes.size > MAX_CERTIFICATE_PASSWORD_BYTES) fail(LoTWProblem.CERTIFICATE_PASSWORD)
                val key = LoTWKeyMaterial.read(data, password, now())
                val bytes = encodeBundle(key.info, data, passwordBytes)
                try { storage.write("certificate", bytes) } finally { bytes.fill(0) }
                discardPreview()
                key.info.copy(passwordSaved = true)
            } finally {
                passwordBytes.fill(0)
                password.fill('\u0000')
                data.fill(0)
            }
        }
    }

    override suspend fun previewCertificate(data: ByteArray, password: CharArray): LoTWCertificate = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (data.isEmpty() || data.size > MAX_CERTIFICATE_BYTES) fail(LoTWProblem.CERTIFICATE_INVALID)
                LoTWKeyMaterial.read(data, password, now()).info
            } finally {
                // Never keep the operator's password; the p12 bytes stay the caller's
                // own copy (the dialog re-reads the file for every preview attempt).
                password.fill('\u0000')
            }
        }
    }

    override suspend fun saveCertificatePassword(password: CharArray): LoTWCertificate = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = storage.read("certificate") ?: fail(LoTWProblem.CERTIFICATE_MISSING)
            val bundle = try { readBundle(stored) } finally { stored.fill(0) }
            val passwordBytes = encodePassword(password)
            try {
                if (passwordBytes.size > MAX_CERTIFICATE_PASSWORD_BYTES) fail(LoTWProblem.CERTIFICATE_PASSWORD)
                val key = LoTWKeyMaterial.read(bundle.p12, password, now())
                val bytes = encodeBundle(key.info, bundle.p12, passwordBytes)
                try { storage.write("certificate", bytes) } finally { bytes.fill(0) }
                discardPreview()
                key.info.copy(passwordSaved = true)
            } finally {
                bundle.p12.fill(0)
                bundle.password?.fill(0)
                passwordBytes.fill(0)
                password.fill('\u0000')
            }
        }
    }

    override suspend fun station(): LoTWStation? = withContext(Dispatchers.IO) {
        mutex.withLock { readStation() }
    }

    override suspend fun saveStation(station: LoTWStation): LoTWStation = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = storage.read("certificate") ?: fail(LoTWProblem.CERTIFICATE_MISSING)
            val bundle = try { readBundle(stored) } finally { stored.fill(0) }
            val normalized = station.normalized()
            try {
                config().stationFields(normalized, bundle.info.dxcc)
            } catch (e: LoTWOperationException) {
                // A region saved under a different certificate no longer applies to
                // this DXCC entity — drop it instead of failing the whole save.
                if (e.reason != LoTWProblem.STATION_REGION) throw e
                val cleaned = normalized.copy(region = "", county = "")
                config().stationFields(cleaned, bundle.info.dxcc)
                writeStation(cleaned)
                discardPreview()
                return@withLock cleaned
            } finally {
                bundle.p12.fill(0)
                bundle.password?.fill(0)
            }
            writeStation(normalized)
            discardPreview()
            normalized
        }
    }

    override suspend fun removeCertificate() = withContext(Dispatchers.IO) {
        mutex.withLock { discardPreview(); storage.delete("certificate") }
    }

    override suspend fun stationMeta(dxcc: Int): LoTWStationMeta = withContext(Dispatchers.IO) {
        config().stationMeta(dxcc)
    }

    /** The ARRL satellite catalogue is static for the lifetime of the bundled config.tq6. */
    private val satelliteNames: List<String> by lazy { config().satelliteNames() }

    override suspend fun satelliteCatalog(): List<String> = withContext(Dispatchers.IO) { satelliteNames }

    /**
     * What makes two records the same contact for batch de-duplication: the opposite station,
     * the satellite (resolved to its ARRL identity, so the tracker spelling and the official
     * name are one) and the minute the QSO started. Frequency and mode are deliberately not
     * part of the key — the same contact logged twice with slightly different tuning (or with
     * the frequency fields empty) must still be caught before LoTW rejects the duplicate.
     */
    private fun duplicateKey(record: QsoRecord): String = listOf(
        record.theirCallsign.trim().uppercase(Locale.US),
        satelliteIdentity(record.satelliteName),
        (record.startUtcMillis / 60_000L).toString()
    ).joinToString("|")

    override suspend fun audit(records: List<QsoRecord>): LoTWUploadAudit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val signing = signingContext()
            val ledger = ledger()
            val unique = hashSetOf<String>()
            var pendingCount = 0
            var uploaded = 0
            var unknown = 0
            var unavailable = 0
            var duplicates = 0
            var incomplete = 0
            val reasons = mutableMapOf<LoTWProblem, Int>()
            val details = mutableMapOf<LoTWProblem, String>()
            records.sortedBy { it.startUtcMillis }.forEach { record ->
                coroutineContext.ensureActive()
                when {
                    record.status != QsoStatus.COMPLETE -> {
                        unavailable++
                        incomplete++
                    }

                    record.lotwReceived || record.lotwUploaded -> uploaded++
                    else -> {
                        val contact = try {
                            signing.signer.contact(record, signing.key, signing.location, now())
                        } catch (e: LoTWOperationException) {
                            unavailable++
                            reasons[e.reason] = (reasons[e.reason] ?: 0) + 1
                            if (e.detail.isNotBlank()) details.putIfAbsent(e.reason, e.detail)
                            null
                        }
                        if (contact != null) when {
                            !unique.add(duplicateKey(contact.record)) -> {
                                unavailable++
                                duplicates++
                            }

                            ledger[contact.fingerprint] == "accepted" -> uploaded++
                            ledger[contact.fingerprint] == "unknown" -> unknown++
                            else -> pendingCount++
                        }
                    }
                }
            }
            LoTWUploadAudit(
                records.size, pendingCount, uploaded, unknown, unavailable,
                reasons, details, duplicates, incomplete
            )
        }
    }

    override suspend fun prepare(records: List<QsoRecord>, resubmit: Boolean): LoTWUploadPreview = withContext(Dispatchers.IO) {
        mutex.withLock {
            discardPreview()
            if (records.size > 10_000) fail(LoTWProblem.TOO_MANY_CONTACTS)
            val signing = signingContext()
            val ledger = ledger()
            var skipped = 0
            var unknown = 0
            var unavailable = 0
            var duplicates = 0
            val unique = hashSetOf<String>()
            val reasons = mutableMapOf<LoTWProblem, Int>()
            val certificateCallsign = signing.key.info.callsign
            val substitutedIds = mutableSetOf<Long>()
            val callsignConflicts = mutableListOf<Long>()
            val contacts = records.sortedBy { it.startUtcMillis }.mapNotNull { record ->
                coroutineContext.ensureActive()
                if (record.status != QsoStatus.COMPLETE || ((record.lotwReceived || record.lotwUploaded) && !resubmit)) { skipped++; return@mapNotNull null }
                // A record logged while no certificate was installed carries no own callsign: sign
                // it with the certificate's callsign (the preview says so, and an accepted upload
                // writes the callsign into the record). A record naming a DIFFERENT callsign may be
                // someone else's contact, so it stays refused — the conflict list lets the operator
                // rewrite those records or import the other certificate.
                val signable = if (record.myCallsign.isBlank()) {
                    substitutedIds += record.id
                    record.copy(myCallsign = certificateCallsign)
                } else record
                val contact = try {
                    signing.signer.contact(signable, signing.key, signing.location, now())
                } catch (e: LoTWOperationException) {
                    // One un-signable record must not abort the whole batch:
                    // skip it, count the real reason, and let the rest upload.
                    unavailable++
                    reasons[e.reason] = (reasons[e.reason] ?: 0) + 1
                    if (e.reason == LoTWProblem.CALLSIGN_MISMATCH) callsignConflicts += record.id
                    return@mapNotNull null
                }
                val previous = ledger[contact.fingerprint]
                when {
                    (previous == "accepted" && !resubmit) -> { skipped++; null }
                    !unique.add(duplicateKey(contact.record)) -> {
                        skipped++
                        duplicates++
                        null
                    }

                    previous == "unknown" && !resubmit -> { skipped++; unknown++; null }
                    else -> contact
                }
            }
            // The station-location grids this batch is signed with: the primary grid plus the
            // MY_VUCC_GRIDS set (1 inside a grid, 2 on a line, 4 on a corner — see [LoTWConfig]).
            val locationGrids = buildList {
                signing.location["GRIDSQUARE"]?.takeIf(String::isNotBlank)?.let(::add)
                signing.location["MY_VUCC_GRIDS"]?.split(',')
                    ?.map(String::trim)?.filter(String::isNotBlank)?.let(::addAll)
            }.distinct()
            val submittedIds = contacts.map { it.record.id }
            val preview = LoTWUploadPreview(
                UUID.randomUUID().toString(), signing.key.info.callsign, signing.key.info.dxcc, signing.location.getValue("GRIDSQUARE"),
                contacts.size, skipped,
                contacts.firstOrNull()?.record?.let { utc(it.startUtcMillis, "yyyy-MM-dd HH:mm:ss") }.orEmpty(),
                contacts.lastOrNull()?.record?.let { utc(it.startUtcMillis, "yyyy-MM-dd HH:mm:ss") }.orEmpty(),
                contacts.map { "${utc(it.record.startUtcMillis, "MM-dd HH:mm")} ${it.record.theirCallsign} ${it.fields["MODE"]} ${it.fields["SAT_NAME"].orEmpty()}" },
                unknown,
                unavailable,
                reasons.toMap(),
                duplicates,
                submittedIds,
                locationGrids,
                resubmit,
                // Only the callsign-filled records that actually made it into this batch are reported.
                submittedIds.count { it in substitutedIds },
                callsignConflicts
            )
            if (contacts.isNotEmpty()) pending = Pending(
                preview,
                signing.signer.sign(contacts, signing.key, signing.location),
                contacts.map { it.fingerprint },
                now()
            )
            preview
        }
    }

    override suspend fun upload(previewId: String): LoTWUploadResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val payload = pending?.takeIf { it.preview.id == previewId && now() - it.created in 0..900_000 }
                ?: return@withLock LoTWUploadResult.ExpiredPreview
            pending = null // A preview is a one-shot authorization, including across failures.
            val ledger = ledger()
            payload.hashes.forEach { ledger[it] = "unknown" }
            saveLedger(ledger) // Persist BEFORE the POST, so process death/cancellation cannot cause an automatic retry.
            try {
                val result = post(payload.data, payload.preview.count)
                when (result) {
                    is LoTWUploadResult.Accepted -> payload.hashes.forEach { ledger[it] = "accepted" }
                    is LoTWUploadResult.Rejected -> payload.hashes.forEach { ledger.remove(it) }
                    else -> Unit
                }
                saveLedger(ledger)
                result
            } finally { payload.data.fill(0) }
        }
    }

    override fun discardPreview() { pending?.data?.fill(0); pending = null }

    private fun signingContext(): SigningContext {
        val stored = storage.read("certificate") ?: fail(LoTWProblem.CERTIFICATE_MISSING)
        val bundle = try { readBundle(stored) } finally { stored.fill(0) }
        val passwordBytes = bundle.password ?: run {
            bundle.p12.fill(0)
            fail(LoTWProblem.CERTIFICATE_PASSWORD)
        }
        val password = decodePassword(passwordBytes)
        return try {
            val key = LoTWKeyMaterial.read(bundle.p12, password, now())
            val tqslConfig = config()
            val signer = LoTWSigner(tqslConfig)
            val station = readStation() ?: fail(LoTWProblem.STATION_GRID)
            SigningContext(key, signer, tqslConfig.stationFields(station, key.info.dxcc))
        } finally {
            bundle.p12.fill(0)
            passwordBytes.fill(0)
            password.fill('\u0000')
        }
    }

    private fun readStation(): LoTWStation? = storage.read("station")?.inputStream()?.let { stream ->
        DataInputStream(stream).use { input ->
            require(input.readInt() == 1)
            LoTWStation(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
        }
    }

    private fun writeStation(station: LoTWStation) {
        val bytes = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use { output ->
            output.writeInt(1)
            listOf(station.grid, station.cqZone, station.ituZone, station.region, station.county, station.iota).forEach(output::writeUTF)
        } }.toByteArray()
        try { storage.write("station", bytes) } finally { bytes.fill(0) }
    }

    private suspend fun post(data: ByteArray, count: Int): LoTWUploadResult = suspendCancellableCoroutine { continuation ->
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("upfile", "look4sat-${UUID.randomUUID()}.tq8", data.toRequestBody("application/octet-stream".toMediaType())).build()
        val call = client.newCall(Request.Builder().url("https://lotw.arrl.org/lotw/upload").post(body).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(
                    LoTWUploadResult.Unknown(
                        reason = "The network request failed; LoTW may still have received the upload.",
                        technicalDetail = (e.javaClass.simpleName + ": " + e.message.orEmpty()).take(500)
                    )
                )
            }
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val responseText = try {
                            readBoundedBody(it, 256 * 1024)
                        } catch (readError: Exception) {
                            return@use LoTWUploadResult.Unknown(
                                reason = "LoTW returned a response that could not be read.",
                                httpStatus = it.code,
                                technicalDetail = (readError.javaClass.simpleName + ": " + readError.message.orEmpty()).take(500)
                            )
                        }
                        // Prefer explicit LoTW result markers over HTTP status alone.
                        val parsed = parseLoTWUploadResponse(responseText, count, it.code)
                        if (!it.isSuccessful && parsed is LoTWUploadResult.Unknown) {
                            parsed.copy(
                                reason = "LoTW returned HTTP " + it.code + " without a definitive upload result.",
                                httpStatus = it.code
                            )
                        } else parsed
                    }
                } catch (e: Exception) {
                    LoTWUploadResult.Unknown(
                        reason = "The LoTW response could not be interpreted.",
                        technicalDetail = (e.javaClass.simpleName + ": " + e.message.orEmpty()).take(500)
                    )
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    private fun ledger(): MutableMap<String, String> = storage.read("receipts")?.inputStream()?.let { stream ->
        DataInputStream(stream).use { input ->
            require(input.readInt() == 1)
            val size = input.readInt().also { require(it in 0..200_000) }
            val entries = linkedMapOf<String, String>()
            repeat(size) { entries[input.readUTF()] = input.readUTF() }
            entries
        }
    } ?: linkedMapOf()

    private fun saveLedger(entries: Map<String, String>) {
        val bytes = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use { output ->
            output.writeInt(1); output.writeInt(entries.size)
            entries.forEach { (hash, status) -> output.writeUTF(hash); output.writeUTF(status) }
        } }.toByteArray()
        storage.write("receipts", bytes)
    }
}

internal fun parseLoTWUploadResponse(body: String, count: Int, httpStatus: Int? = null): LoTWUploadResult {
    val markers = Regex("<!--\\s*\\.UPL\\.\\s*(accepted|rejected)\\s*-->", RegexOption.IGNORE_CASE)
        .findAll(body).map { it.groupValues[1].lowercase() }.toList()
    return when (markers.singleOrNull()) {
        "accepted" -> LoTWUploadResult.Accepted(count)
        "rejected" -> {
            val message = Regex("<!--\\s*\\.UPLMESSAGE\\.\\s*(.*?)-->", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(body)?.groupValues?.get(1).orEmpty().replace(Regex("<[^>]*>"), " ")
                .replace(Regex("[\\p{Cc}\\s]+"), " ").trim().take(500)
            LoTWUploadResult.Rejected(message)
        }
        else -> LoTWUploadResult.Unknown(
            reason = "LoTW's response did not contain a single accepted or rejected marker.",
            httpStatus = httpStatus,
            technicalDetail = body.replace(Regex("<[^>]*>"), " ")
                .replace(Regex("[\\p{Cc}\\s]+"), " ").trim().take(500)
        )
    }
}

private data class StoredCertificate(val info: LoTWCertificate, val p12: ByteArray, val password: ByteArray?)

private fun encodeBundle(info: LoTWCertificate, p12: ByteArray, password: ByteArray): ByteArray = ByteArrayOutputStream().also { stream ->
    DataOutputStream(stream).use { output ->
        output.writeInt(2)
        output.writeUTF(info.callsign); output.writeInt(info.dxcc); output.writeUTF(info.serial)
        output.writeUTF(info.expires); output.writeUTF(info.firstQsoDate); output.writeUTF(info.lastQsoDate)
        output.writeInt(password.size); output.write(password)
        output.writeInt(p12.size); output.write(p12)
    }
}.toByteArray()

private fun readBundle(bytes: ByteArray): StoredCertificate = DataInputStream(bytes.inputStream()).use { input ->
    val version = input.readInt().also { require(it in 1..2) }
    val info = LoTWCertificate(input.readUTF(), input.readInt(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
    val password = if (version >= 2) {
        val size = input.readInt().also { require(it in 0..MAX_CERTIFICATE_PASSWORD_BYTES) }
        ByteArray(size).also { input.readFully(it) }
    } else null
    val p12Size = input.readInt().also { require(it in 1..MAX_CERTIFICATE_BYTES) }
    StoredCertificate(info, ByteArray(p12Size).also { input.readFully(it) }, password)
}

private fun encodePassword(password: CharArray): ByteArray {
    val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(password))
    return ByteArray(buffer.remaining()).also(buffer::get)
}

private fun decodePassword(password: ByteArray): CharArray {
    val buffer = Charsets.UTF_8.decode(ByteBuffer.wrap(password))
    return CharArray(buffer.remaining()).also(buffer::get)
}

private fun LoTWStation.normalized() = LoTWStation(
    grid.trim().uppercase(Locale.US),
    cqZone.trim(),
    ituZone.trim(),
    region.trim().uppercase(Locale.US),
    county.trim().uppercase(Locale.US),
    iota.trim().uppercase(Locale.US)
)

private const val MAX_CERTIFICATE_PASSWORD_BYTES = 16 * 1024
