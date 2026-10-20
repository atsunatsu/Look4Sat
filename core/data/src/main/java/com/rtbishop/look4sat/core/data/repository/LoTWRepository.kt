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
import com.rtbishop.look4sat.core.domain.repository.LoTWPhase
import com.rtbishop.look4sat.core.domain.repository.LoTWProgress
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
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
            fetchReportBody(callsign, password, since = "", onProgress = {}).fold(
                onSuccess = { body ->
                    parseBoth(body)?.let { (grids, _, roamed) ->
                        LoTWResult.Success(grids, emptyMap(), roamed, parseLastQslCursor(body))
                    } ?: LoTWResult.RateLimited
                },
                onFailure = { toResult(it) }
            )
        }

    override suspend fun fetchConfirmedGridQsos(
        callsign: String,
        password: String,
        since: String,
        onProgress: (LoTWProgress) -> Unit
    ): LoTWResult = withContext(Dispatchers.IO) {
        fetchReportBody(callsign, password, since, onProgress).fold(
            onSuccess = { body ->
                parseBoth(body)?.let { (grids, qsos, roamed) ->
                    LoTWResult.Success(grids, qsos, roamed, parseLastQslCursor(body))
                } ?: LoTWResult.RateLimited
            },
            onFailure = { toResult(it) }
        )
    }

    internal fun toResult(e: Throwable): LoTWResult = when (e) {
        is CredentialsException -> LoTWResult.BadCredentials
        is RateLimitException -> LoTWResult.RateLimited
        is TimeoutException -> LoTWResult.Timeout
        else -> LoTWResult.NetworkError(e.message ?: e.javaClass.simpleName)
    }

    /** Latest QSL match/update time in the LoTW report header. */
    internal fun parseLastQslCursor(body: String): String? {
        val eoh = body.indexOf("<EOH>", ignoreCase = true)
        if (eoh < 0) return null
        val raw = body.substring(0, eoh).lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("<APP_LoTW_LASTQSL:", ignoreCase = true) }
            ?.substringAfter('>')?.trim()
        return raw?.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")) }
    }

    /** Single report fetch feeding the grid set, the per-QSO detail and the roamed set. */
    private fun parseBoth(
        body: String
    ): Triple<Set<String>, Map<String, List<com.rtbishop.look4sat.core.domain.model.GridQso>>, Set<String>>? {
        val grids = parseConfirmedGrids(body) ?: return null
        val qsos = parseConfirmedGridQsos(body) ?: return null
        val roamed = parseRoamedGrids(body) ?: return null
        return Triple(grids, qsos, roamed)
    }

    private suspend fun fetchReportBody(
        callsign: String,
        password: String,
        since: String,
        onProgress: (LoTWProgress) -> Unit
    ): Result<String> {
        val call = callsign.trim().uppercase()
        val pwd = password.trim()
        if (call.isBlank() || pwd.isBlank()) return Result.failure(IOException("empty credentials"))
        // Empty since -> full report. qso_qslsince with an early date forces a
        // FULL confirmed-QSL report; without it LoTW applies a "system supplied
        // default" since-date and only returns confirmations newer than the
        // account's last query — subsequent syncs would be incremental/empty.
        val effectiveSince = since.ifBlank { "2000-01-01" }
        val query = buildString {
            append("login=").append(URLEncoder.encode(call, "UTF-8"))
            append("&password=").append(URLEncoder.encode(pwd, "UTF-8"))
            append("&qso_query=1&qso_qsl=yes&qso_qsldetail=yes&qso_mydetail=yes")
            append("&qso_qslsince=").append(URLEncoder.encode(effectiveSince, "UTF-8"))
        }
        return try {
            val connection = URL("$BASE_URL?$query").openConnection() as HttpURLConnection
            // ARRL can be slow to accept connections from mobile networks
            // (long TLS handshakes across the Pacific, occasional server-side
            // queueing — a busy server took 36.5 s to accept a connection in
            // testing, so 30 s connect would have killed it).
            //
            // NOTE: readTimeout is NOT a total-download cap. It is the longest
            // wait for the NEXT chunk of data (a stall timeout). A full report
            // for a huge account (tens of thousands of QSLs ≈ tens of MB at
            // ARRL's ~10 KB/s stream rate) legitimately takes 30+ minutes;
            // as long as chunks keep arriving it must never be cut off.
            // 600 s tolerates long server-side pauses while it generates the
            // report; a truly dead link (no data at all) still fails within it.
            connection.connectTimeout = 60_000
            connection.readTimeout = 600_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.instanceFollowRedirects = true
            onProgress(LoTWProgress(LoTWPhase.Connecting))
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                // Observed in the wild (CQRLOG #2422): LoTW answers a throttled
                // report pull with HTTP 503 "Page request limit".
                return if (code == 503 || code == 429) Result.failure(RateLimitException())
                else Result.failure(IOException("HTTP $code"))
            }
            val stream = connection.inputStream
            val isGzip = "gzip".equals(connection.contentEncoding, ignoreCase = true)
            val body = readBodyWithProgress(stream, isGzip, onProgress)
            connection.disconnect()
            when {
                // Login failure: HTTP 200 + HTML login page with the error text.
                body.contains("incorrect", ignoreCase = true) &&
                    body.contains("assword", ignoreCase = true) -> Result.failure(CredentialsException())
                // Any other non-ADIF body: rate-limit refusal / server error page.
                !body.contains("<eoh>", ignoreCase = true) -> Result.failure(RateLimitException())
                else -> Result.success(body)
            }
        } catch (e: java.util.concurrent.CancellationException) {
            // Cancellation must propagate — it is not a network failure.
            throw e
        } catch (e: SocketTimeoutException) {
            Result.failure(TimeoutException(e.message ?: "timed out"))
        } catch (e: SSLException) {
            Result.failure(IOException("TLS: ${e.message ?: "handshake failed"}"))
        } catch (e: Exception) {
            println("LoTWRepository fetch failure: $e")
            Result.failure(IOException(e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Streams the report body into a string while reporting download progress.
     * Once the header's record count (<APP_LoTW_NUMREC>) is seen, the total
     * body size is estimated as NUMREC * AVG_RECORD_BYTES so the UI can show a
     * meaningful progress bar and remaining-time estimate.
     */
    private suspend fun readBodyWithProgress(
        stream: java.io.InputStream,
        isGzip: Boolean,
        onProgress: (LoTWProgress) -> Unit
    ): String {
        val input = if (isGzip) java.util.zip.GZIPInputStream(stream) else stream
        val reader = java.io.InputStreamReader(input, Charsets.UTF_8)
        val sb = StringBuilder()
        val buf = CharArray(8192)
        var bytesRead = 0L
        var expected = 0L
        var qsoCount = 0L
        var speed = 0L
        var headerSeen = false
        var lastSampleMs = System.currentTimeMillis()
        var lastSampleBytes = 0L
        var lastEmitMs = 0L
        while (true) {
            // Cooperative cancellation: lets the user abort a full sync that
            // was started by mistake. Throws CancellationException once the
            // job is cancelled; the blocking read below is fast while chunks
            // keep arriving, so the abort lands on the next chunk boundary.
            coroutineContext.ensureActive()
            val n = reader.read(buf)
            if (n <= 0) break
            sb.append(buf, 0, n)
            bytesRead += n
            // The record count sits in the report header, long before the data.
            if (!headerSeen) {
                NUMREC_REGEX.find(sb)?.let { m ->
                    qsoCount = m.groupValues[1].toLongOrNull() ?: 0L
                    expected = qsoCount * AVG_RECORD_BYTES
                    headerSeen = true
                }
            }
            // Sample speed every 500ms, EMA-smoothed.
            val now = System.currentTimeMillis()
            if (now - lastSampleMs >= 500) {
                val elapsedS = (now - lastSampleMs) / 1000.0
                if (elapsedS > 0) {
                    val inst = ((bytesRead - lastSampleBytes) / elapsedS).toLong()
                    speed = if (speed == 0L) inst else (speed * 7 + inst * 3) / 10
                    lastSampleMs = now
                    lastSampleBytes = bytesRead
                }
            }
            // Throttle callbacks to ~4/s so StateFlow updates stay cheap.
            if (now - lastEmitMs >= 250) {
                onProgress(LoTWProgress(LoTWPhase.Downloading, bytesRead, expected, speed, qsoCount))
                lastEmitMs = now
            }
        }
        onProgress(LoTWProgress(LoTWPhase.Downloading, bytesRead, expected, speed, qsoCount))
        return sb.toString()
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
        var dxcc: Int? = null
        var country: String? = null
        var cqz: Int? = null
        var state: String? = null
        // Own-station (台址) fields: MY_GRIDSQUARE + MY_VUCC_GRIDS give the
        // full grid set of the station location; the remaining MY_* fields
        // together identify the station location (one callsign can own
        // several locations, and one location can span several grids).
        var myGrids = mutableSetOf<String>()
        var myCallsign: String? = null
        var myDxcc: String? = null
        var myState: String? = null
        var myCq: String? = null
        var myItu: String? = null
        var myIota: String? = null
        var myCountry: String? = null
        val gridsInRecord = mutableListOf<String>()

        fun emitRecord() {
            if (propMode != "SAT" || gridsInRecord.isEmpty()) return
            val epochMs = adifTimestampToEpoch(qsoDate, timeOn)
            val stationKey = buildString {
                append(myCallsign ?: "")
                append('|').append(myDxcc ?: "")
                append('|').append(myState ?: "")
                append('|').append(myCq ?: "")
                append('|').append(myItu ?: "")
                append('|').append(myIota ?: "")
                append('|').append(myCountry ?: "")
            }.ifBlank { null }
            val qso = com.rtbishop.look4sat.core.domain.model.GridQso(
                call = call, epochMs = epochMs, satName = satName,
                mode = mode, bandUp = bandUp, bandDown = bandDown,
                dxcc = dxcc, country = country, cqz = cqz, state = state,
                myGrid = myGrids.firstOrNull(),
                myGrids = myGrids,
                myCallsign = myCallsign,
                stationKey = stationKey,
                // Copy, never the live buffer — resetRecord() clears it.
                theirGrids = gridsInRecord.distinct().toList()
            )
            for (grid in gridsInRecord) {
                result.getOrPut(grid) { mutableListOf() }.add(qso)
            }
        }

        fun resetRecord() {
            propMode = null; call = ""; qsoDate = ""; timeOn = ""
            satName = ""; mode = ""; bandUp = ""; bandDown = ""
            dxcc = null; country = null; cqz = null; state = null
            myGrids = mutableSetOf()
            myCallsign = null; myDxcc = null; myState = null
            myCq = null; myItu = null; myIota = null; myCountry = null
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
                    bandDown = adifValue(line).uppercase()
                line.startsWith("<BAND:") && !line.startsWith("<BAND_RX:") ->
                    bandUp = adifValue(line).uppercase()
                line.startsWith("<DXCC:") ->
                    dxcc = adifValue(line).toIntOrNull()
                line.startsWith("<COUNTRY:") ->
                    country = adifValue(line).ifBlank { null }
                line.startsWith("<CQZ:") ->
                    cqz = adifValue(line).toIntOrNull()
                line.startsWith("<STATE:") ->
                    state = adifValue(line).trim().ifBlank { null }?.let { normalizeState(it) }
                line.startsWith("<MY_GRIDSQUARE:") -> {
                    // Own-station grid (must not be confused with GRIDSQUARE —
                    // the opposite station's grid). Recorded per QSO so awards
                    // can be counted per operated grid. A station location may
                    // also carry MY_VUCC_GRIDS (below) for multi-grid roaming.
                    val value = adifValue(line)
                    if (value.length >= 4) myGrids.add(value.take(4).uppercase())
                }
                line.startsWith("<MY_VUCC_GRIDS:") -> {
                    // Own-station VUCC grids: a comma-separated list LoTW emits
                    // when one station location spans several grid squares
                    // (e.g. "OM60,OM50" with MY_GRIDSQUARE absent). Without
                    // this field the extra grids of a multi-grid 台址 silently
                    // vanished from both the blue stripes and the per-台址 VUCC
                    // count. Same split/truncate as VUCC_GRIDS below.
                    adifValue(line).split(',').forEach { grid ->
                        val field = grid.trim().uppercase()
                        if (field.length >= 4) myGrids.add(field.take(4))
                    }
                }
                // Remaining MY_* fields identify the station location (台址):
                // grouped together they form the stationKey used by the map's
                // operated-grid selector. One callsign can own several 台址.
                line.startsWith("<STATION_CALLSIGN:") ->
                    myCallsign = adifValue(line).trim().uppercase().ifBlank { null }
                line.startsWith("<MY_DXCC:") ->
                    myDxcc = adifValue(line).trim().ifBlank { null }
                line.startsWith("<MY_STATE:") ->
                    myState = adifValue(line).trim().ifBlank { null }?.let { normalizeState(it) }
                line.startsWith("<MY_CQ_ZONE:") ->
                    myCq = adifValue(line).trim().ifBlank { null }
                line.startsWith("<MY_ITU_ZONE:") ->
                    myItu = adifValue(line).trim().ifBlank { null }
                line.startsWith("<MY_IOTA:") ->
                    myIota = adifValue(line).trim().uppercase().ifBlank { null }
                line.startsWith("<MY_COUNTRY:") ->
                    myCountry = adifValue(line).trim().ifBlank { null }
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

    /**
     * LoTW returns STATE as "CODE // NAME" (e.g. "HB // Hubei" for China,
     * "34 // Tottori-ken" for Japan, "CA // California" for the US). The award
     * statistics match on the short CODE only (China 2-letter province pinyin,
     * Japan 2-digit prefecture, US 2-letter state), so strip the " // NAME"
     * suffix here once, storing the clean code for every downstream consumer
     * (persistence, AwardCalculator).
     */
    private fun normalizeState(raw: String): String = raw.substringBefore(" // ").trim()

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
        //
        // Buffered per-record pattern (same as parseConfirmedGridQsos): field order
        // is NOT reliable — ADIF producers (incl. LoTW) emit fields alphabetically,
        // so <GRIDSQUARE> (G) arrives BEFORE <PROP_MODE> (P) within a record. A
        // sequential gate ("only add while propMode == SAT") silently drops every
        // grid on real reports. Buffer the record and decide at <EOR> instead.
        var propMode: String? = null
        var pendingGrids = mutableListOf<String>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.equals("<EOR>", ignoreCase = true) -> {
                    if (propMode == "SAT") grids.addAll(pendingGrids)
                    propMode = null
                    pendingGrids = mutableListOf()
                }
                line.startsWith("<PROP_MODE:") -> {
                    propMode = adifValue(line).uppercase()
                }
                line.startsWith("<GRIDSQUARE:") || line.startsWith("<VUCC_GRIDS:") -> {
                    // VUCC_GRIDS holds a comma-separated PAIR of grids
                    // ("EN52en,EN53fa") for contacts spanning two squares —
                    // split on ',' and take the 4-char field of each, or the
                    // second grid is silently dropped.
                    val value = adifValue(line)
                    value.split(',').forEach { grid ->
                        val field = grid.trim().uppercase()
                        if (field.length >= 4) pendingGrids.add(field.take(4))
                    }
                }
            }
        }
        return grids
    }

    /**
     * Distinct 4-char gridsquares the account itself operated from, taken from
     * ADIF <MY_GRIDSQUARE> of satellite QSO records only. This is the
     * "roamed / activated" set shown as blue stripes on the map — the grids
     * where the operator's own station was located during confirmed QSOs (a
     * rover may log several distinct grids; the home grid is included too).
     * Returns null when the body is not an ADIF report (mirrors the other parsers).
     */
    internal fun parseRoamedGrids(body: String): Set<String>? {
        if (!body.contains("<eoh>", ignoreCase = true)) return null
        val grids = mutableSetOf<String>()
        // Buffered per-record pattern (same as parseConfirmedGridQsos): field
        // order is NOT reliable — ADIF producers (incl. LoTW) emit fields
        // alphabetically, so <MY_GRIDSQUARE> (M) arrives BEFORE <PROP_MODE> (P)
        // within a record. A sequential gate ("only add while propMode == SAT")
        // would silently drop every own grid on real reports; buffer the record
        // and decide at <EOR> instead.
        var propMode: String? = null
        var myGrids = mutableSetOf<String>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.equals("<EOR>", ignoreCase = true) -> {
                    if (propMode == "SAT" && myGrids.isNotEmpty()) grids.addAll(myGrids)
                    propMode = null
                    myGrids = mutableSetOf()
                }
                line.startsWith("<PROP_MODE:") -> {
                    propMode = adifValue(line).uppercase()
                }
                line.startsWith("<MY_GRIDSQUARE:") -> {
                    val value = adifValue(line)
                    if (value.length >= 4) myGrids.add(value.take(4).uppercase())
                }
                line.startsWith("<MY_VUCC_GRIDS:") -> {
                    // Multi-grid 台址: each field of MY_VUCC_GRIDS is a grid the
                    // station location covered; without this the extra grids
                    // never reached the blue stripes.
                    adifValue(line).split(',').forEach { grid ->
                        val field = grid.trim().uppercase()
                        if (field.length >= 4) myGrids.add(field.take(4))
                    }
                }
            }
        }
        return grids
    }

    private companion object {
        const val BASE_URL = "https://lotw.arrl.org/lotwuser/lotwreport.adi"

        /** Record count in the report header: `<APP_LoTW_NUMREC:4>2367`. */
        val NUMREC_REGEX = Regex("<APP_LoTW_NUMREC:\\d+>(\\d+)")
        /**
         * Measured 2026-09-16 (BH6RJD, 2367 QSLs): 1.7 MB body streamed at
         * ~9.5 KB/s ≈ 180 s, i.e. ≈ 720 bytes per ADIF record. Use a
         * CONSERVATIVE (larger) estimate so the progress bar never hits 100%
         * before the download truly ends — an under-estimate made the bar sit
         * at 100% while data was still flowing, read as "finished but stuck".
         */
        const val AVG_RECORD_BYTES = 900L
    }
}
