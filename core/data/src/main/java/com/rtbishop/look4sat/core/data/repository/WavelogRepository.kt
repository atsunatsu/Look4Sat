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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches the list of worked gridsquares from a self-hosted Wavelog instance.
 *
 * Uses the Wavelog v1 API endpoint (verified against Wavelog 3.0.2):
 *   POST {base}/index.php/api/logbook_get_worked_grids
 *   Body: {"key": "<api key>", "logbook_id": "<logbook id>"}
 *   Response 201: JSON array of 4-char gridsquares, e.g. ["JN47","JO30"]
 *   (see Wavelog Api.php logbook_get_worked_grids / Api_model::get_grids_worked_in_logbook)
 *
 * The token field carries "<api_key>:<logbook_id>" so one credential line
 * configures both values (the dialog explains this to the user).
 */
class WavelogRepository : IWavelogRepository {

    override suspend fun fetchWorkedGrids(url: String, token: String): Set<String>? = withContext(Dispatchers.IO) {
        val parts = token.trim().split(":")
        val apiKey = parts.getOrNull(0)?.trim().orEmpty()
        val logbookId = parts.getOrNull(1)?.trim().orEmpty()
        if (url.isBlank() || apiKey.isBlank() || logbookId.isBlank()) return@withContext null
        val base = url.trim().trimEnd('/')
        val requestUrl = "$base/index.php/api/logbook_get_worked_grids"
        try {
            val connection = java.net.URL(requestUrl).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().apply {
                put("key", apiKey)
                put("logbook_id", logbookId)
            }.toString().toByteArray()
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                return@withContext null
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            parseWorkedGrids(response)
        } catch (e: Exception) {
            println("WavelogRepository fetch failure: $e")
            null
        }
    }

    internal fun parseWorkedGrids(body: String): Set<String>? {
        return try {
            val array = when {
                body.trimStart().startsWith("[") -> JSONArray(body)
                else -> {
                    // Tolerate {"grids": [...]} wrappers too
                    JSONObject(body).optJSONArray("grids") ?: return null
                }
            }
            val result = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val grid = array.optString(i, "").trim().uppercase()
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
