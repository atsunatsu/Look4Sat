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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
 * ARRL rate-limits the report endpoint (roughly once per hour per account), which is
 * fine for a manual sync button.
 */
class LoTWRepository : ILoTWRepository {

    override suspend fun fetchConfirmedGrids(callsign: String, password: String): Set<String>? =
        withContext(Dispatchers.IO) {
            val call = callsign.trim().uppercase()
            val pwd = password.trim()
            if (call.isBlank() || pwd.isBlank()) return@withContext null
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
            try {
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
                    return@withContext null
                }
                val stream = connection.inputStream
                val body = ("gzip".equals(connection.contentEncoding, ignoreCase = true))
                    .let { gz -> if (gz) java.util.zip.GZIPInputStream(stream) else stream }
                    .bufferedReader().use { it.readText() }
                connection.disconnect()
                if (body.contains(" password=") && !body.startsWith("ARRL")) return@withContext null
                parseConfirmedGrids(body)
            } catch (e: Exception) {
                println("LoTWRepository fetch failure: $e")
                null
            }
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
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            val value = when {
                line.startsWith("<GRIDSQUARE:") -> line.substringAfter('>')
                line.startsWith("<VUCC_GRIDS:") -> line.substringAfter('>')
                else -> continue
            }.substringBefore("E<").trim().uppercase()
            if (value.length >= 4) grids.add(value.take(4))
        }
        return grids
    }

    private companion object {
        const val BASE_URL = "https://lotw.arrl.org/lotwuser/lotwreport.adi"
    }
}
