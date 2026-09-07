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
package com.rtbishop.look4sat.core.domain.repository

/** Result of a LoTW report fetch, with the failure cause kept explicit. */
sealed class LoTWResult {

    /** Report downloaded and parsed successfully. */
    data class Success(
        val grids: Set<String>,
        val qsos: Map<String, List<com.rtbishop.look4sat.core.domain.model.GridQso>>
    ) : LoTWResult()

    /** HTTP 200 but LoTW replied with its login-error page (bad callsign/password). */
    data object BadCredentials : LoTWResult()

    /**
     * Report endpoint refused the request without a usable error page. LoTW
     * limits downloads to one in progress per user id and flags accounts that
     * pull the full report too often; the server answers those with a bare
     * non-ADIF body (no <eoh>).
     */
    data object RateLimited : LoTWResult()

    /** Connect/read timed out — typically a slow route to the ARRL servers. */
    data object Timeout : LoTWResult()

    /** Any other network-level failure (DNS, TLS, connection reset, HTTP 5xx). */
    data class NetworkError(val detail: String) : LoTWResult()
}
