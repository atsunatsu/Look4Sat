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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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

/**
 * Public, aggregate GridMaster satellite data; no callsigns or local QSOs are uploaded.
 * Updates are infrequent (Wavelog refreshes this dataset monthly). Retain the last
 * successfully validated response so the overlay continues to work offline.
 */
internal object GridActivityStore {
    private const val ENDPOINT = "https://gridmaster.fr/api/grids.json"

    internal fun endpointFor(award: GridActivityAward): String =
        "$ENDPOINT?award=${award.apiCode}&format=map"
    private const val CACHE_VALID_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_BYTES = 2_000_000

    suspend fun load(
        context: Context,
        award: GridActivityAward = GridActivityAward.SATELLITE,
        forceRefresh: Boolean = false
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
            if (!forceRefresh && cached != null && now - cached.downloadedAt in 0 until CACHE_VALID_MS) {
                return@withContext cached
            }
            try {
                val connection = (URL(endpointFor(award)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "Look4Sat/1.0 (satellite grid activity)")
                }
                val response = try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("GridMaster HTTP " + connection.responseCode)
                    }
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
            } catch (_: Exception) {
                cached
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
