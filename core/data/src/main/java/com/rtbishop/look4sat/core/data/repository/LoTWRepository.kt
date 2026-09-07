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
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.repository.ILoTWRepository
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Fetches confirmed gridsquares directly from ARRL LoTW via the official report endpoint:
 *   GET https://lotw.arrl.org/lotwuser/lotwreport.adi?login=<call>&password=<pwd>
 *       &qso_query=1&qso_qsl=yes&qso_qsldetail=yes&qso_mydetail=yes&qso_qslsince=<date>
 *
 * Only QSL_RCVD=Y records are returned by LoTW for qso_qsl=yes, so every grid in the
 * report is a *confirmed* grid (green on the map). GRIDSQUARE may be a 4- or 6-char
 * value; VUCC_GRIDS ("EN52en,EN53fa") also yields 4-char fields. All values are
 * truncated/expanded to the 4-char form used by the map overlay.
 *
 * ARRL rate-limits the report endpoint: one download in progress per user id, and
 * frequent full-report pulls get refused. Failures are reported with an explicit
 * cause (see LoTWResult) so the UI can tell the user what to do next.
 */
class LoTWRepository : ILoTWRepository {

    override suspend fun fetchConfirmedGrids(callsign: String, password: String): LoTWResult =
        withContext(Dispatchers.IO) {
            fetchReportBody(callsign, password).fold(
                onSuccess = { body -> parseBoth(body)?.let { LoTWResult.Success(it.first, it.second) }
                    ?: LoTWResult.RateLimited },
                onFailure = { toResult(it) }
            )
        }

    override suspend fun fetchConfirmedGridQsos(
        callsign: String,
        password: String
    ): LoTWResult = withContext(Dispatchers.IO) {
        fetchReportBody(callsign, password).fold(
            onSuccess = { body -> parseBoth(body)?.let { LoTWResult.Success(it.first, it.second) }
                ?: LoTWResult.RateLimited },
            onFailure = { toResult(it) }
        )
    }

    internal fun toResult(e: Throwable): LoTWResult = when (e) {
        is CredentialsException -> LoTWResult.BadCredentials
        is RateLimitException -> LoTWResult.RateLimited
        is TimeoutException -> LoTWResult.Timeout
        else -> LoTWResult.NetworkError(e.message ?: e.javaClass.simpleName)
    }

    /** Single report fetch feeding both the grid set and the per-QSO detail. */
    private fun parseBoth(body: String): Pair<Set<String>, Map<String, List<com.rtbishop.look4sat.core.domain.model.GridQso>>>? {
        val grids = parseConfirmedGrids(body) ?: return null
        val qsos = parseConfirmedGridQsos(body) ?: return null
        return grids to qsos
    }

    private fun fetchReportBody(callsign: String, password: String): Result<String> {
        val call = callsign.trim().uppercase()
        val pwd = password.trim()
        if (call.isBlank() || pwd.isBlank()) return Result.failure(IOException("empty credentials"))
        // qso_qslsince with an early date forces a FULL confirmed-QSL report.
        // Without it, LoTW applies a "system supplied default" since-date and
        // only returns confirmations newer than the account's last query —
        // subsequent syncs would return an empty/incremental report.
        val since = "2000-01-01"
        val query = buildString {
            append("login=").append(URLEncoder.encode(call, "UTF-8"))
            append("&password=").append(URLEncoder.encode(pwd, "UTF-8"))
            append("&qso_query=1&qso_qsl=yes&qso_qsldetail=yes&qso_mydetail=yes")
            append("&qso_qslsince=").append(URLEncoder.encode(since, "UTF-8"))
        }
        return try {
            val connection = URL("$BASE_URL?$query").openConnection() as HttpURLConnection
            // ARRL can be slow to accept connections from mobile networks
            // (long TLS handshakes across the Pacific, occasional server-side
            // queueing). 30s connect + 120s read gives the request enough
            // headroom; the sync button stays disabled meanwhile so users
            // see progress rather than a hung dialog.
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                // Observed in the wild (CQRLOG #2422): LoTW answers a throttled
                // report pull with HTTP 503 "Page request limit".
                return if (code == 503 || code == 429) Result.failure(RateLimitException())
                else Result.failure(IOException("HTTP $code"))
            }
            val stream = connection.inputStream
            val body = ("gzip".equals(connection.contentEncoding, ignoreCase = true))
                .let { gz -> if (gz) java.util.zip.GZIPInputStream(stream) else stream }
                .bufferedReader().use { it.readText() }
            connection.disconnect()
            when {
                // Login failure: HTTP 200 + HTML login page with the error text.
                body.contains("incorrect", ignoreCase = true) &&
                    body.contains("assword", ignoreCase = true) -> Result.failure(CredentialsException())
                // Any other non-ADIF body: rate-limit refusal / server error page.
                !body.contains("<eoh>", ignoreCase = true) -> Result.failure(RateLimitException())
                else -> Result.success(body)
            }
        } catch (e: SocketTimeoutException) {
            Result.failure(TimeoutException(e.message ?: "timed out"))
        } catch (e: SSLException) {
            Result.failure(IOException("TLS: ${e.message ?: "handshake failed"}"))
        } catch (e: Exception) {
            println("LoTWRepository fetch failure: $e")
            Result.failure(IOException(e.message ?: e.javaClass.simpleName))
        }
    }

    internal class CredentialsException : Exception("bad callsign/password")
    internal class RateLimitException : Exception("report refused (rate limit / server error)")
    internal class TimeoutException(message: String) : Exception(message)

    internal fun parseConfirmedGridQsos(
        body: String
    ): Map<String, List<com.rtbishop.look4sat.core.domain.model.GridQso>>? {
        if (!body.contains("<eoh>", ignoreCase = true)) return null
        // One ADIF record = fields up to <EOR>. We buffer the fields we care
        // about, then emit a GridQso on <EOR> when the record is a satellite
        // QSO (PROP_MODE=SAT) carrying a grid.
        val result = mutableMapOf<String, MutableList<com.rtbishop.look4sat.core.domain.model.GridQso>>()
        var propMode: String? = null
        var call = ""
        var qsoDate = ""
        var timeOn = ""
        var satName = ""
        var mode = ""
        var bandUp = ""
        var bandDown = ""
        val gridsInRecord = mutableListOf<String>()

        fun emitRecord() {
            if (propMode != "SAT" || gridsInRecord.isEmpty()) return
            val epochMs = adifTimestampToEpoch(qsoDate, timeOn)
            val qso = com.rtbishop.look4sat.core.domain.model.GridQso(
                call = call, epochMs = epochMs, satName = satName,
                mode = mode, bandUp = bandUp, bandDown = bandDown
            )
            for (grid in gridsInRecord) {
                result.getOrPut(grid) { mutableListOf() }.add(qso)
            }
        }

        fun resetRecord() {
            propMode = null; call = ""; qsoDate = ""; timeOn = ""
            satName = ""; mode = ""; bandUp = ""; bandDown = ""
            gridsInRecord.clear()
        }

        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.equals("<EOR>", ignoreCase = true) -> {
                    emitRecord()
                    resetRecord()
                }
                line.startsWith("<PROP_MODE:") ->
                    propMode = adifValue(line).uppercase()
                line.startsWith("<CALL:") ->
                    call = adifValue(line).uppercase()
                line.startsWith("<QSO_DATE:") ->
                    qsoDate = adifValue(line)
                line.startsWith("<TIME_ON:") ->
                    timeOn = adifValue(line)
                line.startsWith("<SAT_NAME:") ->
                    satName = adifValue(line)
                line.startsWith("<MODE:") ->
                    mode = adifValue(line)
                line.startsWith("<BAND_RX:") ->
                    bandUp = adifValue(line).uppercase()
                line.startsWith("<BAND:") && !line.startsWith("<BAND_RX:") ->
                    bandDown = adifValue(line).uppercase()
                line.startsWith("<GRIDSQUARE:") || line.startsWith("<VUCC_GRIDS:") -> {
                    // VUCC_GRIDS holds a comma-separated list of grids
                    // ("EN52en,EN53fa"), up to four for contacts spanning
                    // several squares; split and keep every 4-char field.
                    adifValue(line).split(',').forEach { grid ->
                        val field = grid.trim().uppercase()
                        if (field.length >= 4) gridsInRecord.add(field.take(4))
                    }
                }
            }
        }
        return result
    }

    /** ADIF field value: "<GRIDSQUARE:4>OL62" -> "OL62". */
    private fun adifValue(line: String): String =
        line.substringAfter('>').substringBefore("E<").trim()

    /** "20260820" + "1130" (or "113000") -> UTC epoch ms; 0 when unparseable. */
    private fun adifTimestampToEpoch(date: String, time: String): Long = try {
        val d = date.trim()
        val t = time.trim().padEnd(6, '0').take(6)
        val fmt = java.time.format.DateTimeFormatterBuilder()
            .appendPattern("yyyyMMddHHmmss")
            .toFormatter()
            .withZone(java.time.ZoneOffset.UTC)
        java.time.Instant.from(fmt.parse(d + t)).toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    internal fun parseConfirmedGrids(body: String): Set<String>? {
        // LoTW answers with ADIF text; on bad credentials it returns a short error page
        // containing "password=?" or an <eoh>-less block. Treat anything without a header
        // marker as failure so the caller can show a sensible message.
        // LoTW answers with ADIF text; on bad credentials it returns a short error
        // page without an <eoh> header terminator. Real reports always carry <eoh>
        // (LoTW writes it lowercase). Match case-insensitively to be safe.
        if (!body.contains("<eoh>", ignoreCase = true)) return null
        val grids = mutableSetOf<String>()
        // ADIF fields of one QSO record span multiple lines and are terminated by
        // <EOR>. Satellite QSOs carry <PROP_MODE:3>SAT (plus <SAT_NAME>); ground
        // QSOs omit it. Grid fields (GRIDSQUARE / VUCC_GRIDS) must only be
        // collected for records whose PROP_MODE is SAT, otherwise the map mixes
        // in terrestrial contacts.
        var propMode: String? = null
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.equals("<EOR>", ignoreCase = true) -> propMode = null
                line.startsWith("<PROP_MODE:") -> {
                    propMode = line.substringAfter('>').substringBefore("E<").trim().uppercase()
                }
                line.startsWith("<GRIDSQUARE:") || line.startsWith("<VUCC_GRIDS:") -> {
                    if (propMode == "SAT") {
                        // VUCC_GRIDS holds a comma-separated PAIR of grids
                        // ("EN52en,EN53fa") for contacts spanning two squares —
                        // split on ',' and take the 4-char field of each, or the
                        // second grid is silently dropped.
                        val value = line.substringAfter('>').substringBefore("E<")
                        value.split(',').forEach { grid ->
                            val field = grid.trim().uppercase()
                            if (field.length >= 4) grids.add(field.take(4))
                        }
                    }
                }
            }
        }
        return grids
    }

    private companion object {
        const val BASE_URL = "https://lotw.arrl.org/lotwuser/lotwreport.adi"
    }
}
