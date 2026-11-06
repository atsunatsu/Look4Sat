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

import com.rtbishop.look4sat.core.domain.logbook.AdifCodec
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.repository.IWavelogUploadRepository
import com.rtbishop.look4sat.core.domain.repository.WavelogProblem
import com.rtbishop.look4sat.core.domain.repository.WavelogProbe
import com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadOutcome
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Uploads local QSO records to a self-hosted Wavelog via the v1 API.
 *
 * The server contract (verified against Wavelog 3.0.2, `Api.php` / `Logbook_model.php`):
 * - `POST {base}/index.php/api/qso` with `{"key","station_profile_id","type":"adif","string"}`.
 * - A clean import answers HTTP 201; skipped records (duplicates, differing locator/callsign)
 *   answer HTTP 400 with one message line per record while the remaining records ARE inserted.
 *   Duplicates count as "already in Wavelog" and are marked; `SKIPPED` records are not.
 * - The export omits `STATION_CALLSIGN` so the server fills it from the chosen station profile
 *   (a present-but-different callsign would make the server skip the record).
 */
class WavelogUploadRepository internal constructor(
    client: OkHttpClient = OkHttpClient()
) : IWavelogUploadRepository {

    private val client = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val mutex = Mutex()
    @Volatile private var pending: Pending? = null

    private data class Pending(
        val submittedIds: List<Long>,
        val callsById: Map<Long, String>,
        val adif: ByteArray,
        val created: Long
    )

    override suspend fun probe(url: String, apiKey: String): WavelogProbe? = withContext(Dispatchers.IO) {
        val base = url.trim().trimEnd('/')
        if (base.isBlank() || apiKey.isBlank()) return@withContext null
        try {
            val authBody = get("$base/index.php/api/check_auth/$apiKey") ?: return@withContext null
            val rights = JSONObject(authBody).optString("rights")
            val stationsBody = get("$base/index.php/api/station_info/$apiKey") ?: return@withContext null
            WavelogProbe(rights, parseStations(stationsBody))
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun prepare(records: List<QsoRecord>, settings: WavelogUploadSettings): WavelogUploadPreview =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                discardPending()
                if (settings.url.isBlank() || settings.apiKey.isBlank()) {
                    return@withLock blocked(settings, WavelogProblem.NOT_CONFIGURED)
                }
                if (settings.stationId.isBlank()) return@withLock blocked(settings, WavelogProblem.NO_STATION)
                var skipped = 0
                val reasons = mutableMapOf<WavelogProblem, Int>()
                val unique = hashSetOf<String>()
                val submitted = records.sortedBy { it.startUtcMillis }.filter { record ->
                    when {
                        record.status != QsoStatus.COMPLETE -> {
                            skipped++
                            false
                        }

                        !gridCompatible(
                            record.myFullGrids.takeIf { it.size > 1 }?.joinToString(",") ?: record.myGrid,
                            settings.stationGrid
                        ) -> {
                            skipped++
                            reasons[WavelogProblem.GRID_MISMATCH] = (reasons[WavelogProblem.GRID_MISMATCH] ?: 0) + 1
                            false
                        }

                        !unique.add(stableQsoKey(record)) -> {
                            skipped++
                            false
                        }

                        else -> true
                    }
                }
                val preview = WavelogUploadPreview(
                    count = submitted.size,
                    skipped = skipped,
                    reasons = reasons,
                    stationLabel = stationLabel(settings),
                    stationId = settings.stationId,
                    stationGrid = settings.stationGrid,
                    qsoGrids = submitted.flatMap { record ->
                        record.myFullGrids.ifEmpty { listOf(record.myGrid) + record.vuccGrids }
                    }.map(String::trim).filter(String::isNotBlank).distinct(),
                    firstUtc = submitted.firstOrNull()?.let { utcText(it.startUtcMillis) }.orEmpty(),
                    lastUtc = submitted.lastOrNull()?.let { utcText(it.startUtcMillis) }.orEmpty(),
                    contacts = submitted.map { record ->
                        "${shortText(record.startUtcMillis)} ${record.theirCallsign} ${record.displayMode} " +
                            record.satelliteName.substringBefore('(').trim()
                    },
                    submittedIds = submitted.map { it.id }
                )
                if (submitted.isNotEmpty()) {
                    pending = Pending(
                        submittedIds = submitted.map { it.id },
                        callsById = submitted.associate { it.id to it.theirCallsign.trim().uppercase(Locale.US) },
                        adif = AdifCodec.encode(submitted, includeStationCallsign = false).toByteArray(Charsets.UTF_8),
                        created = System.currentTimeMillis()
                    )
                }
                preview
            }
        }

    override suspend fun upload(preview: WavelogUploadPreview, settings: WavelogUploadSettings): WavelogUploadOutcome =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val payload = pending
                pending = null // a preview is a one-shot authorization, including across failures
                val now = System.currentTimeMillis()
                if (payload == null || payload.submittedIds != preview.submittedIds ||
                    settings.stationId != preview.stationId || settings.stationGrid != preview.stationGrid ||
                    now - payload.created !in 0..900_000
                ) {
                    return@withLock WavelogUploadOutcome.Expired
                }
                try {
                    val base = settings.url.trim().trimEnd('/')
                    val body = JSONObject().apply {
                        put("key", settings.apiKey.trim())
                        put("station_profile_id", settings.stationId)
                        put("type", "adif")
                        put("string", String(payload.adif, Charsets.UTF_8))
                    }.toString()
                    val request = Request.Builder()
                        .url("$base/index.php/api/qso")
                        .post(body.toRequestBody(jsonType))
                        .build()
                    client.newCall(request).execute().use { response ->
                        val text = response.body.string().take(64 * 1024)
                        classifyUploadResponse(response.code, text, payload.callsById)
                    }
                } catch (_: Exception) {
                    WavelogUploadOutcome.Failed("Upload failed — check server address/network (Tailscale on?)")
                } finally {
                    payload.adif.fill(0)
                }
            }
        }

    private fun discardPending() {
        pending?.adif?.fill(0)
        pending = null
    }

    private fun blocked(settings: WavelogUploadSettings, reason: WavelogProblem) = WavelogUploadPreview(
        count = 0,
        skipped = 0,
        reasons = emptyMap(),
        stationLabel = stationLabel(settings),
        stationId = settings.stationId,
        stationGrid = settings.stationGrid,
        firstUtc = "",
        lastUtc = "",
        contacts = emptyList(),
        submittedIds = emptyList(),
        blockedBy = reason
    )

    private fun stationLabel(settings: WavelogUploadSettings): String = listOf(
        settings.stationName, settings.stationCallsign, settings.stationGrid
    ).map(String::trim).filter(String::isNotBlank).joinToString(" · ")

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body.string().take(256 * 1024)
        }
    }
}

/**
 * Whether a record's logged grid is consistent with the Wavelog station profile grid.
 * Mirror of the server's `adif_grid_check_location` prefix rule; either side blank
 * disables the check, exactly like the server.
 */
internal fun gridCompatible(adifGrid: String, stationGrid: String): Boolean {
    val adif = adifGrid.trim().uppercase(Locale.US)
    val station = stationGrid.trim().uppercase(Locale.US)
    if (adif.isEmpty() || station.isEmpty()) return true
    val adifParts = adif.split(',').map(String::trim).filter(String::isNotEmpty)
    val stationParts = station.split(',').map(String::trim).filter(String::isNotEmpty)
    if (adifParts.isEmpty() || stationParts.isEmpty()) return true
    val adifMargin = adifParts.size > 1
    val stationMargin = stationParts.size > 1
    return when {
        adifMargin && stationMargin -> adifParts.sorted() == stationParts.sorted()
        adifMargin -> false
        stationMargin -> stationParts.any { adif.startsWith(it) }
        else -> adif.startsWith(station) || station.startsWith(adif)
    }
}

/**
 * Classifies Wavelog's upload response.
 *
 * 2xx = everything imported. 400 = per-record errors: duplicates count as "already in Wavelog"
 * (they are marked, so a re-run converges), `SKIPPED` records are excluded from marking, and
 * any other message is surfaced verbatim with nothing marked.
 */
internal fun classifyUploadResponse(code: Int, body: String, callsById: Map<Long, String>): WavelogUploadOutcome = when {
    code in 200..299 -> WavelogUploadOutcome.Imported(
        imported = callsById.size, duplicates = 0, skipped = 0, markIds = callsById.keys.toList()
    )

    code == 401 -> WavelogUploadOutcome.Failed("Wavelog rejected the API key or station (HTTP 401)")

    code == 403 -> WavelogUploadOutcome.Failed(
        "API key has no write permission — create a read & write key in Wavelog (Settings → API)"
    )

    else -> classifyAbortMessages(body, callsById)
}

private val skipCallRegex = Regex("importing QSO with\\s*<b>([^<]+)</b>", RegexOption.IGNORE_CASE)

private fun classifyAbortMessages(body: String, callsById: Map<Long, String>): WavelogUploadOutcome {
    val raw = rawMessages(body)
    val lines = stripTags(raw).split('\n').map(String::trim).filter(String::isNotEmpty)
    if (lines.isEmpty()) {
        return WavelogUploadOutcome.Failed("Wavelog did not accept the upload — check the server log")
    }
    val duplicates = lines.count { it.contains("Duplicate for", ignoreCase = true) }
    val skipLines = lines.filter { it.contains("SKIPPED", ignoreCase = true) }
    val others = lines.filter {
        !it.contains("Duplicate for", ignoreCase = true) && !it.contains("SKIPPED", ignoreCase = true)
    }
    if (others.isNotEmpty()) {
        return WavelogUploadOutcome.Rejected("Wavelog reported: ${others.first().take(200)}")
    }
    val submittedCalls = callsById.values.toSet()
    val skipCalls = skipCallRegex.findAll(raw)
        .map { it.groupValues[1].trim().uppercase(Locale.US) }.toSet()
    if (skipLines.isNotEmpty() && (skipCalls.isEmpty() || skipCalls.any { it !in submittedCalls })) {
        // A skip we cannot map back to a submitted record: stay conservative, mark nothing.
        return WavelogUploadOutcome.Rejected("Some records were skipped by Wavelog — nothing was marked; review and retry")
    }
    val markIds = callsById.filterValues { it !in skipCalls }.keys.toList()
    val skipped = callsById.size - markIds.size
    val imported = (callsById.size - duplicates - skipped).coerceAtLeast(0)
    return WavelogUploadOutcome.Imported(imported, duplicates, skipped, markIds)
}

private fun rawMessages(body: String): String {
    val json = runCatching { JSONObject(body) }.getOrNull()
    return if (json != null) {
        val messages = json.optJSONArray("messages")
        if (messages != null) (0 until messages.length()).joinToString("") { messages.optString(it) }
        else json.optString("reason")
    } else {
        body
    }
}

private fun stripTags(raw: String): String = raw
    .replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(Regex("<[^>]*>"), " ")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    .replace(Regex("[ \\t]+"), " ")
    .lines().joinToString("\n") { it.trim() }

/** Parses the v1 station_info array (only the fields the app uses). */
internal fun parseStations(body: String): List<WavelogStationInfo> {
    val array = JSONArray(body)
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("station_id")
        if (id.isBlank()) return@mapNotNull null
        WavelogStationInfo(
            id = id,
            name = item.optString("station_profile_name"),
            callsign = item.optString("station_callsign"),
            grid = item.optString("station_gridsquare"),
            active = item.optString("station_active") == "1"
        )
    }
}

private fun utcText(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(millis))

private fun shortText(millis: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(millis))
