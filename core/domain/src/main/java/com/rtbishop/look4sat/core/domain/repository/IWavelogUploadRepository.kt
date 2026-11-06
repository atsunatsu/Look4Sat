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
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings

/** One station profile as returned by the v1 station_info endpoint. */
data class WavelogStationInfo(
    val id: String,
    val name: String,
    val callsign: String,
    val grid: String,
    val active: Boolean
)

/** Result of probing a Wavelog server (check_auth + station_info). */
data class WavelogProbe(val rights: String, val stations: List<WavelogStationInfo>)

/** Why a record (or the whole upload) cannot be submitted. */
enum class WavelogProblem { NOT_CONFIGURED, NO_STATION, GRID_MISMATCH }

/** Records skipped before the POST (with reasons), plus the records that will be submitted. */
data class WavelogUploadPreview(
    val count: Int,
    val skipped: Int,
    val reasons: Map<WavelogProblem, Int>,
    val stationLabel: String,
    val firstUtc: String,
    val lastUtc: String,
    val contacts: List<String>,
    val submittedIds: List<Long>,
    val blockedBy: WavelogProblem? = null,
    val stationId: String = "",
    val stationGrid: String = "",
    val qsoGrids: List<String> = emptyList()
)

sealed interface WavelogUploadOutcome {
    /** [markIds] can be smaller than the submission when Wavelog reported skipped records. */
    data class Imported(
        val imported: Int,
        val duplicates: Int,
        val skipped: Int,
        val markIds: List<Long>
    ) : WavelogUploadOutcome

    data class Rejected(val message: String) : WavelogUploadOutcome
    data class Failed(val message: String) : WavelogUploadOutcome
    data object Expired : WavelogUploadOutcome
}

/**
 * Uploads local QSO records to a self-hosted Wavelog via the v1 API
 * (`POST {base}/index.php/api/qso` with a batch ADIF string).
 */
interface IWavelogUploadRepository {
    /** check_auth + station_info; null on any transport/parse failure. */
    suspend fun probe(url: String, apiKey: String): WavelogProbe?

    /** Filters + encodes the batch and keeps a one-shot pending payload for [upload]. */
    suspend fun prepare(records: List<QsoRecord>, settings: WavelogUploadSettings): WavelogUploadPreview

    /** POSTs the prepared batch and classifies Wavelog's response. */
    suspend fun upload(preview: WavelogUploadPreview, settings: WavelogUploadSettings): WavelogUploadOutcome
}

/** Update selected station metadata by ID without changing the upload target. */
fun WavelogUploadSettings.withStationProfile(station: WavelogStationInfo): WavelogUploadSettings =
    copy(
        stationId = station.id,
        stationName = station.name,
        stationCallsign = station.callsign,
        stationGrid = station.grid
    )
