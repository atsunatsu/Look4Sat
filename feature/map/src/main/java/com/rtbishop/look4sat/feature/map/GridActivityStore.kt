/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 Look4Sat contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.map

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/** GridMaster award codes exposed by its public API for the satellite activity map. */
internal enum class GridActivityAward(
    val apiCode: String,
    val cacheFileName: String,
    val minimumGridCount: Int
) {
    SATELLITE("sat", "gridmaster_sat_activity.json", 50),
    LEO("sat/leo", "gridmaster_sat_leo_activity.json", 1),
    MEO("sat/meo", "gridmaster_sat_meo_activity.json", 1),
    GEO("sat/geo", "gridmaster_sat_geo_activity.json", 1),
    NON_GEOSTATIONARY("sat/ngs", "gridmaster_sat_ngs_activity.json", 1);

    companion object {
        fun fromApiCode(code: String?): GridActivityAward =
            values().firstOrNull { it.apiCode == code } ?: SATELLITE
    }
}

/** GridMaster.fr's percentage of submitted SAT logs containing each 4-character grid. */
internal data class GridActivitySnapshot(
    val percentages: Map<String, Int>,
    val downloadedAt: Long,
    val fromCache: Boolean
)

/** One of the latest operators reported by GridMaster for a Maidenhead grid. */
internal data class GridActivityRecentOperator(
    val call: String,
    val date: String?
)

/** Latest GridMaster operators fetched for the currently visible 4-character grids. */
internal data class GridActivityLatestCallSnapshot(
    val callsByGrid: Map<String, String>,
    val operatorsByGrid: Map<String, List<GridActivityRecentOperator>>,
    val checkedGrids: Set<String>,
    val shouldPauseQueue: Boolean = false
)

/**
 * Public, aggregate GridMaster satellite data; no callsigns or local QSOs are uploaded.
 * Refresh the aggregate and per-grid snapshots once per UTC calendar day. Retain the last
 * successfully validated responses so the overlay continues to work offline.
 */
internal object GridActivityStore {
    private const val ENDPOINT = "https://gridmaster.fr/api/grids.json"

    internal fun endpointFor(award: GridActivityAward): String =
        "$ENDPOINT?award=${award.apiCode}&format=map"
    internal const val MILLIS_PER_UTC_DAY = 24L * 60 * 60 * 1000
    internal const val REQUEST_REFILL_INTERVAL_MS = 36_000L
    internal const val REQUEST_BUDGET_CAPACITY = 80.0
    private const val REQUEST_BUDGET_FILE = "gridmaster_request_budget.json"
    private const val GRID_DETAIL_MAX_BYTES = 64_000
    private const val MAX_BYTES = 2_000_000
    private val requestBudgetMutex = Mutex()
    private val latestCallLoadMutex = Mutex()

    internal data class RequestBudgetState(
        val tokens: Double,
        val updatedAt: Long,
        val blockedUntil: Long = 0L
    )

    private class GridMasterRateLimitException(val retryAfterMillis: Long) :
        IOException("GridMaster rate limit reached")

    suspend fun load(
        context: Context,
        award: GridActivityAward = GridActivityAward.SATELLITE
    ): GridActivitySnapshot? = withContext(Dispatchers.IO) {
            // Keep the original Satellite cache filename for seamless v21.1 upgrades;
            // each additional award receives its own independent cache and timestamp.
            val file = File(context.applicationContext.filesDir, award.cacheFileName)
            val cached = try {
                if (file.isFile) {
                    GridActivitySnapshot(parse(file.readText(), award.minimumGridCount), file.lastModified(), fromCache = true)
                } else null
            } catch (_: Exception) {
                null
            }
            val now = System.currentTimeMillis()
            if (cached != null && isCacheFreshForUtcDay(cached.downloadedAt, now)) {
                return@withContext cached
            }
            try {
                awaitRequestPermit(context)
                val connection = (URL(endpointFor(award)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "Look4Sat/1.0 (satellite grid activity)")
                }
                val response = try {
                    checkSuccessfulResponse(connection)
                    connection.inputStream.use { stream ->
                        // Reject oversized/untrusted responses before parsing or overwriting cache.
                        val bytes = ByteArray(MAX_BYTES + 1)
                        var count = 0
                        while (count < bytes.size) {
                            val n = stream.read(bytes, count, bytes.size - count)
                            if (n < 0) break
                            count += n
                        }
                        if (count > MAX_BYTES) throw IOException("GridMaster data too large")
                        String(bytes, 0, count, Charsets.UTF_8)
                    }
                } finally {
                    connection.disconnect()
                }
                val parsed = parse(response, award.minimumGridCount)
                // Only a fully validated download replaces the previous good cache.
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(response)
                if (!tmp.renameTo(file)) {
                    file.writeText(response)
                    tmp.delete()
                }
                GridActivitySnapshot(parsed, file.lastModified(), fromCache = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (limited: GridMasterRateLimitException) {
                pauseAfterRateLimit(context, limited.retryAfterMillis)
                cached
            } catch (_: Exception) {
                cached
            }
        }

    /** Build the public single-grid endpoint used to retrieve its latest operators. */
    internal fun endpointForGrid(grid: String, award: GridActivityAward): String {
        val normalized = normalizeGrid(grid) ?: throw IllegalArgumentException("Invalid Maidenhead grid")
        return "https://gridmaster.fr/api/grid.json?grid=" + normalized + "&award=" + award.apiCode
    }

    /**
     * Fetch one grid at a time so the map can progressively fill in callsign labels.
     * The cache is shared by the map and detail dialog and is refreshed once per UTC day.
     */
    suspend fun loadLatestCall(
        context: Context,
        grid: String,
        award: GridActivityAward = GridActivityAward.SATELLITE
    ): GridActivityLatestCallSnapshot = withContext(Dispatchers.IO) {
        val normalized = normalizeGrid(grid)
            ?: return@withContext GridActivityLatestCallSnapshot(emptyMap(), emptyMap(), emptySet())
        latestCallLoadMutex.withLock {
            val cacheFile = File(
                context.applicationContext.filesDir,
                "gridmaster_" + award.cacheFileName.removePrefix("gridmaster_")
                    .removeSuffix("_activity.json") + "_latest_calls.json"
            )
            val cache = try {
                readLatestCallCache(cacheFile)
            } catch (_: Exception) {
                mutableMapOf()
            }
            var entry = cache[normalized]
            val now = System.currentTimeMillis()
            if (entry == null || !isCacheFreshForUtcDay(entry.checkedAt, now)) {
                try {
                    awaitRequestPermit(context)
                    entry = CachedGridActivityCall(
                        checkedAt = System.currentTimeMillis(),
                        operators = fetchLatestOperators(normalized, award)
                    )
                    cache[normalized] = entry
                    try {
                        writeLatestCallCache(cacheFile, cache)
                    } catch (_: Exception) {
                        // Keep the response usable for this session if disk caching fails.
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (limited: GridMasterRateLimitException) {
                    pauseAfterRateLimit(context, limited.retryAfterMillis)
                } catch (_: Exception) {
                    return@withLock snapshotForGrid(normalized, entry, shouldPauseQueue = true)
                }
            }
            snapshotForGrid(normalized, entry)
        }
    }

    private fun snapshotForGrid(
        grid: String,
        entry: CachedGridActivityCall?,
        shouldPauseQueue: Boolean = false
    ): GridActivityLatestCallSnapshot {
        val operators = entry?.operators?.takeIf { it.isNotEmpty() }?.let { mapOf(grid to it) }
            ?: emptyMap()
        val calls = operators[grid]?.firstOrNull()?.call?.let { mapOf(grid to it) } ?: emptyMap()
        return GridActivityLatestCallSnapshot(
            callsByGrid = calls,
            operatorsByGrid = operators,
            checkedGrids = if (entry != null) setOf(grid) else emptySet(),
            shouldPauseQueue = shouldPauseQueue
        )
    }

    /**
     * A GridMaster cache entry is valid only for the UTC calendar day on which it was fetched.
     * The first lookup after 00:00 UTC refreshes it, independent of the device's local timezone.
     */
    internal fun isCacheFreshForUtcDay(fetchedAt: Long, now: Long): Boolean =
        fetchedAt > 0L && now >= fetchedAt &&
            fetchedAt / MILLIS_PER_UTC_DAY == now / MILLIS_PER_UTC_DAY

    /** Parse up to all six recent operators from GridMaster's newest-first last[] list. */
    internal fun parseRecentOperators(json: String): List<GridActivityRecentOperator> {
        val last = JSONObject(json).optJSONArray("last")
            ?: throw IOException("GridMaster grid response is missing last[]")
        return (0 until minOf(last.length(), 6)).mapNotNull { index ->
            val entry = last.optJSONObject(index) ?: return@mapNotNull null
            val call = entry.optString("call").trim()
            if (call.isEmpty() || call.equals("null", ignoreCase = true)) {
                null
            } else {
                val date = entry.optString("date").trim().takeIf {
                    it.isNotEmpty() && !it.equals("null", ignoreCase = true)
                }
                GridActivityRecentOperator(call = call, date = date)
            }
        }
    }

    /** The map label uses only the first (most recent) entry from last[]. */
    internal fun parseLatestCall(json: String): String? =
        parseRecentOperators(json).firstOrNull()?.call

    private data class CachedGridActivityCall(
        val checkedAt: Long,
        val operators: List<GridActivityRecentOperator>
    )

    private fun normalizeGrid(grid: String): String? {
        val normalized = grid.trim().uppercase(Locale.ROOT)
        return normalized.takeIf { it.matches(Regex("[A-R]{2}[0-9]{2}")) }
    }

    private fun readLatestCallCache(file: File): MutableMap<String, CachedGridActivityCall> {
        if (!file.isFile) return mutableMapOf()
        val root = JSONObject(file.readText())
        val result = mutableMapOf<String, CachedGridActivityCall>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val rawGrid = keys.next()
            val grid = normalizeGrid(rawGrid) ?: continue
            val entry = root.optJSONObject(rawGrid) ?: continue
            val checkedAt = entry.optLong("checked_at", 0L)
            if (checkedAt <= 0L) continue
            // Read the old single-call cache format as a one-entry list for seamless upgrades.
            val operators = entry.optJSONArray("last")?.let { last ->
                (0 until minOf(last.length(), 6)).mapNotNull { index ->
                    val item = last.optJSONObject(index) ?: return@mapNotNull null
                    val call = item.optString("call").trim()
                    if (call.isEmpty()) null else {
                        val date = item.optString("date").trim().takeIf {
                            it.isNotEmpty() && !it.equals("null", ignoreCase = true)
                        }
                        GridActivityRecentOperator(call, date)
                    }
                }
            } ?: (entry.optString("call").trim().takeIf {
                it.isNotEmpty() && !it.equals("null", ignoreCase = true)
            }?.let { listOf(GridActivityRecentOperator(it, null)) } ?: emptyList())
            result[grid] = CachedGridActivityCall(checkedAt, operators)
        }
        return result
    }

    private fun writeLatestCallCache(
        file: File,
        cache: Map<String, CachedGridActivityCall>
    ) {
        val root = JSONObject()
        cache.forEach { (grid, entry) ->
            root.put(
                grid,
                JSONObject()
                    .put("checked_at", entry.checkedAt)
                    .put("last", org.json.JSONArray().apply {
                        entry.operators.forEach { operator ->
                            put(
                                JSONObject()
                                    .put("call", operator.call)
                                    .put("date", operator.date ?: JSONObject.NULL)
                            )
                        }
                    })
            )
        }
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(root.toString())
        if (!temporary.renameTo(file)) {
            file.writeText(root.toString())
            temporary.delete()
        }
    }

    private fun fetchLatestOperators(
        grid: String,
        award: GridActivityAward
    ): List<GridActivityRecentOperator> {
        val connection = (URL(endpointForGrid(grid, award)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Look4Sat/1.0 (satellite grid activity)")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GridMaster HTTP " + connection.responseCode)
            }
            val response = connection.inputStream.use { stream ->
                val bytes = ByteArray(GRID_DETAIL_MAX_BYTES + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = stream.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > GRID_DETAIL_MAX_BYTES) throw IOException("GridMaster grid response too large")
                String(bytes, 0, count, Charsets.UTF_8)
            }
            parseRecentOperators(response)
        } finally {
            connection.disconnect()
        }
    }

    /** Parse the Wavelog-compatible format: { "OL62": 12, ... } (values in 0..100). */
    internal fun parse(json: String, minimumEntries: Int = 50): Map<String, Int> {
        val obj = JSONObject(json)
        val result = LinkedHashMap<String, Int>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val raw = keys.next()
            if (raw.length < 4) continue
            val grid = raw.take(4).uppercase(Locale.ROOT)
            if (grid.length != 4 || grid[0] !in 'A'..'R' || grid[1] !in 'A'..'R' ||
                grid[2] !in '0'..'9' || grid[3] !in '0'..'9') continue
            val value = obj.opt(raw)
            if (value !is Number) continue
            val percentage = value.toDouble()
            if (percentage.isFinite() && percentage in 0.0..100.0) {
                result[grid] = percentage.toInt()
            }
        }
        // Prevent empty/error documents from masquerading as a successful refresh.
        if (result.size < minimumEntries) throw IOException("GridMaster dataset is empty or incomplete")
        return result
    }
}
