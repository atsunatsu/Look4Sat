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

/** What a Wavelog sync pulls: everything, or only records newer than the stored cursors. */
enum class WavelogSyncMode { Full, Incremental }

/** Outcome of one sync fetch. */
data class WavelogSyncFetch(
    /** Records pulled this run, each tagged with the station profile it came from. */
    val records: List<QsoRecord>,
    /** stationId -> lastfetchedid to resume from on the next incremental run. */
    val cursors: Map<String, Long>,
    /** Station ids that could not be fetched (transport/parse); empty when all succeeded. */
    val failedStations: List<String>
)

/**
 * Pulls QSO records from a self-hosted Wavelog (v1 API, `api/get_contacts_adif`).
 * One request per station profile; the server exports only rows newer than the
 * `fetchfromid` cursor, so repeated syncs stay incremental per station.
 */
interface IWavelogSyncRepository {

    /**
     * Fetches records for every entry of [stations]. [cursors] maps stationId to the
     * last id already synced (missing -> 0); [full] re-pulls each station from the
     * beginning, ignoring the cursors. Returns null when the fetch cannot run at all
     * (blank url/key or no stations); individual station failures land in
     * [WavelogSyncFetch.failedStations] with the remaining stations still fetched.
     */
    suspend fun fetchNewRecords(
        url: String,
        apiKey: String,
        stations: List<WavelogStationInfo>,
        cursors: Map<String, Long>,
        full: Boolean
    ): WavelogSyncFetch?

    /** Fetches the current ADIF rows for LoTW confirmations dated [since] or later. */
    suspend fun fetchLoTWConfirmations(
        url: String,
        apiKey: String,
        token: String,
        stations: List<WavelogStationInfo>,
        since: String
    ): List<QsoRecord>?
}
