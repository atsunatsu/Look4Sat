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

import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings

import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import kotlinx.coroutines.flow.StateFlow

interface ISettingsRepo {

    val appVersionName: String

    //region # Satellites selection settings
    val selectedIds: StateFlow<List<Int>>
    val selectedTypes: StateFlow<List<String>>
    fun setSelectedIds(ids: List<Int>)
    fun setSelectedTypes(types: List<String>)
    //endregion

    //region # Passes filter settings
    val passesSettings: StateFlow<PassesSettings>
    fun setPassesSettings(settings: PassesSettings)
    //endregion

    //region # Station position settings
    val stationPosition: StateFlow<GeoPos>
    fun setStationPosition(latitude: Double, longitude: Double, altitude: Double): Boolean
    fun setStationPosition(): Boolean
    fun setStationPosition(locator: String): Boolean
    /**
     * Freshest known position grid for the roaming check, read-only: the newer of a fresh
     * (≤ 24 h) system location fix and the stored station position. Null only when neither
     * yields a usable locator. Never writes the station position.
     */
    fun getCurrentGrid(): String?
    //endregion

    //region # Database update settings
    val databaseState: StateFlow<DatabaseState>
    fun getSatelliteTypesIds(types: List<String>): List<Int>
    fun setSatelliteTypeIds(type: String, ids: List<Int>)
    fun updateDatabaseState(state: DatabaseState)
    //endregion

    //region # RC settings
    val rcSettings: StateFlow<RCSettings>
    fun updateRCSettings(settings: RCSettings)
    //endregion

    //region # Other settings
    val otherSettings: StateFlow<OtherSettings>
    fun updateOtherSettings(transform: (OtherSettings) -> OtherSettings)
    fun setWarningDismissed() = updateOtherSettings { it.copy(shouldSeeWarning = false) }
    fun setWhatsNewDismissed() = updateOtherSettings { it.copy(shouldSeeWhatsNew = false) }
    //endregion

    //region # Transceivers settings
    val dataSourcesSettings: StateFlow<DataSourcesSettings>
    fun updateDataSourcesSettings(settings: DataSourcesSettings)
    //endregion

    //region # Data sources status
    val dataSourcesStatus: StateFlow<Map<String, Int>>
    fun updateDataSourcesStatus(status: Map<String, Int>)
    //endregion

    //region # Radio control settings
    val radioControlSettings: StateFlow<RadioControlSettings>
    fun getRemoteCwPresets(): List<String> = CwTransmitPresets.defaults
    fun updateRemoteCwPreset(index: Int, text: String) = Unit
    fun getRadioProfileMetadata(): Map<String, RadioProfileMetadata> = emptyMap()
    fun setRadioProfileMetadata(values: Map<String, RadioProfileMetadata>) = Unit
    fun getRadioProfileAliases(): Map<String, String> = emptyMap()
    fun setRadioProfileAliases(values: Map<String, String>) = Unit
    fun updateRadioControlSettings(settings: RadioControlSettings)
    //endregion

    //region # Per-satellite calculator offset settings
    fun getSatelliteOffset(catnum: Int): String
    fun setSatelliteOffset(catnum: Int, offset: String)
    //endregion

    //region # Per-satellite logbook mode preset settings
    /** Last selected logbook mode (CW/SSB/FT4) for a satellite; empty when unset. */
    fun getSatelliteMode(catnum: Int): String
    fun setSatelliteMode(catnum: Int, mode: String)
    //endregion

    //region # AMSAT status report settings
    fun getAmSatCallsign(): String
    fun setAmSatCallsign(callsign: String)
    //endregion

    //region # Worked-grid data (written by the LoTW / Wavelog syncs)
    fun getWorkedGrids(): Set<String>
    fun setWorkedGrids(grids: Set<String>)
    /** Confirmed satellite QSOs grouped by worked 4-char gridsquare (map tap detail). */
    fun getWorkedGridQsos(): Map<String, List<com.rtbishop.look4sat.core.domain.model.GridQso>>
    fun setWorkedGridQsos(qsos: Map<String, List<com.rtbishop.look4sat.core.domain.model.GridQso>>)
    /** Distinct 4-char gridsquares the account operated from (LoTW <MY_GRIDSQUARE>). */
    fun getRoamedGrids(): Set<String>
    fun setRoamedGrids(grids: Set<String>)
    /** VUCC map's operated-station selection; empty means All. Grid-set IDs are source-independent. */
    fun getVuccSelectedStationIds(): Set<String>
    fun setVuccSelectedStationIds(ids: Set<String>)
    /** Stations the user marked as "want to contact" in unworked gridsquares
     *  (grid -> ordered list of marks; the first is the pinned/top one). The
     *  whole grid clears automatically once it becomes worked. */
    fun getMarkedGridStations(): Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>>
    fun setMarkedGridStations(stations: Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>>)
    //endregion

    //region # Wavelog upload settings
    val wavelogUploadSettings: StateFlow<WavelogUploadSettings>
    fun updateWavelogUploadSettings(settings: WavelogUploadSettings)
    //endregion

    //region # Wavelog sync bookkeeping
    /** Station profiles as last reported by the server — labels for the 台址 selector and targets for sync. */
    fun getWavelogStations(): List<WavelogStationInfo>
    fun setWavelogStations(stations: List<WavelogStationInfo>)
    /** stationId -> lastfetchedid cursors of the last successful sync (per station profile). */
    fun getWavelogSyncCursors(): Map<String, Long>
    fun setWavelogSyncCursors(cursors: Map<String, Long>)
    /** Wavelog base URL the stored cursors belong to (empty when never synced). */
    fun getWavelogSyncUrl(): String
    fun setWavelogSyncUrl(url: String)
    /** Epoch ms of the last successful Wavelog sync (0 when never synced). */
    fun getLastWavelogSyncEpochMs(): Long
    fun setLastWavelogSyncEpochMs(value: Long)
    /** Epoch ms of the last FULL Wavelog re-scan (0 when never). An incremental request is
     *  promoted to a full pull once this is stale — Wavelog's pull filters by row id, so a
     *  confirmation arriving for an already-pulled row is invisible to the incremental cursor. */
    fun getLastWavelogFullSyncEpochMs(): Long
    fun setLastWavelogFullSyncEpochMs(value: Long)
    //endregion

    //region # LoTW confirmed-grids settings
    val lotwSettings: StateFlow<com.rtbishop.look4sat.core.domain.model.LoTWSettings>
    fun updateLoTWSettings(settings: com.rtbishop.look4sat.core.domain.model.LoTWSettings)
    /** Last successful LoTW sync date ("yyyyMMdd", empty when never synced). */
    fun getLastLotwSyncDate(): String
    fun setLastLotwSyncDate(date: String)
    /** Callsign of the last successful LoTW sync (empty when never synced). */
    fun getLastLotwSyncCallsign(): String
    fun setLastLotwSyncCallsign(callsign: String)
    //endregion
}
