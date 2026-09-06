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

import com.rtbishop.look4sat.core.domain.repository.IWavelogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Fetches the list of worked gridsquares from a self-hosted Wavelog instance.
 *
 * Uses the Wavelog API v2 endpoint (Wavelog >= 3.1.0):
 *   GET {base}/index.php/api/v2/lookup?grid=all
 *   Authorization: Bearer wl2_...
 * Response: {"data": {"grids": ["JN47", "JO30"], "count": 2}, ...}
 *
 * Uses a plain HttpURLConnection (bearer auth header) — IRemoteSource does not
 * support custom headers, and no feature module touches this class directly.
 */
class WavelogRepository : IWavelogRepository {

    /**
     * Fetch worked grids. Returns the grid set, or null on any failure
     * (network error, non-200, malformed response, bad token).
     */
    override suspend fun fetchWorkedGrids(url: String, token: String): Set<String>? = withContext(Dispatchers.IO) {
        if (url.isBlank() || token.isBlank()) return@withContext null
        val base = url.trim().trimEnd('/')
        val requestUrl = "$base/index.php/api/v2/lookup?grid=all"
        try {
            // IRemoteSource.getNetworkStream does not support custom headers, so
            // run the bearer-token request directly here via java.net (simple GET,
            // no OkHttp dependency added to this module's public API).
            val connection = java.net.URL(requestUrl).openConnection() as java.net.HttpURLConnection
            connectionTimeouts(connection)
            connection.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code != 200) {
                connection.disconnect()
                return@withContext null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            parseWorkedGrids(body)
        } catch (e: Exception) {
            println("WavelogRepository fetch failure: $e")
            null
        }
    }

    private fun connectionTimeouts(connection: java.net.HttpURLConnection) {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
    }

    internal fun parseWorkedGrids(body: String): Set<String>? {
        return try {
            val obj = JSONObject(body)
            val data = obj.optJSONObject("data") ?: return null
            val grids = data.optJSONArray("grids") ?: return null
            val result = mutableSetOf<String>()
            for (i in 0 until grids.length()) {
                val grid = grids.optString(i, "").trim().uppercase()
                // Keep only well-formed 4-character Maidenhead squares (e.g. OL62)
                if (grid.length == 4 && grid[0] in 'A'..'R' && grid[1] in 'A'..'R'
                    && grid[2] in '0'..'9' && grid[3] in '0'..'9'
                ) {
                    result.add(grid)
                }
            }
            result
        } catch (_: Exception) {
            null
        }
    }
}
