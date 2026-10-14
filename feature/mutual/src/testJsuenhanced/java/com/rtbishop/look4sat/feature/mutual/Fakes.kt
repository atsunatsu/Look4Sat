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
package com.rtbishop.look4sat.feature.mutual

import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.model.Ft4Settings
import com.rtbishop.look4sat.core.domain.model.GridQso
import com.rtbishop.look4sat.core.domain.model.LoTWSettings
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.SatRadio

import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.model.NominalFrequencies
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test fake for ISatelliteRepo. Only `satellites` and `passes` are backed by
 * mutable state; everything else the ViewModel never touches fails loudly.
 */
class FakeSatelliteRepo(
    satellites: List<OrbitalObject> = emptyList(),
    passes: List<OrbitalPass> = emptyList()
) : ISatelliteRepo {

    override val satellites = MutableStateFlow(satellites)
    override val passes = MutableStateFlow(passes)
    override val isCalculating = MutableStateFlow(false)
    override val selectedPass = MutableStateFlow(Pair(0, 0L))

    override fun selectPass(catNum: Int, aosTime: Long) = TODO()
    override suspend fun initRepository() = TODO()
    override suspend fun calculatePasses(
        time: Long, hoursAhead: Int, minElevation: Double,
        aosStartMinute: Int, aosEndMinute: Int,
        invertAosTimeWindow: Boolean, modes: List<String>
    ) = TODO()
    override suspend fun getPosition(sat: OrbitalObject, pos: GeoPos, time: Long): OrbitalPos = TODO()
    override suspend fun getTrack(sat: OrbitalObject, pos: GeoPos, start: Long, end: Long): List<OrbitalPos> = TODO()
    override suspend fun getRadios(sat: OrbitalObject, pos: GeoPos, radios: List<SatRadio>, time: Long): List<SatRadio> = TODO()
    override suspend fun getRadiosWithId(id: Int): List<SatRadio> = TODO()
    override suspend fun nominalFrequencies(name: String): NominalFrequencies? = null
    override suspend fun getSatelliteIdsWithModes(modes: List<String>): List<Int> = emptyList()
    override suspend fun getSatelliteIdsWithModesAndUplink(modes: List<String>): List<Int> = emptyList()
    override suspend fun getSatelliteIdsWithModesAndAmateur(modes: List<String>): List<Int> = emptyList()
}

/**
 * Test fake for ISettingsRepo. Only [stationPosition] is backed by mutable
 * state; everything else the ViewModel never touches fails loudly.
 */
class FakeSettingsRepo(
    initialPosition: GeoPos = GeoPos(23.13, 113.26)
) : ISettingsRepo {

    override val stationPosition = MutableStateFlow(initialPosition)

    override val appVersionName: String = "test"
    override val selectedIds: StateFlow<List<Int>> = MutableStateFlow(emptyList())
    override val selectedTypes: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val passesSettings: StateFlow<PassesSettings> = MutableStateFlow(
        PassesSettings(hoursAhead = 24, minElevation = 0.0, selectedModes = emptyList())
    )
    override val databaseState: StateFlow<DatabaseState> = MutableStateFlow(DatabaseState(0, 0, 0L))
    override val rcSettings: StateFlow<RCSettings> = MutableStateFlow(
        RCSettings(false, "", "", "", false, "", "", "", 0L, false, "", "", "", false, "", "")
    )
    override val rotatorSettings: StateFlow<com.rtbishop.look4sat.core.domain.rotator.RotatorSettings> =
        MutableStateFlow(com.rtbishop.look4sat.core.domain.rotator.RotatorSettings())
    override val otherSettings: StateFlow<OtherSettings> = MutableStateFlow(
        OtherSettings(
            stateOfAutoUpdate = false,
            stateOfSensors = false,
            stateOfSweep = false,
            stateOfUtc = false,
            stateOfLightTheme = false,
            stateOfNightMode = false,
            stateOfMapGrid = false,
            shouldSeeWarning = false,
            shouldSeeWhatsNew = false
        )
    )
    override val ft4Settings: StateFlow<Ft4Settings> = MutableStateFlow(Ft4Settings())
    override val dataSourcesSettings: StateFlow<DataSourcesSettings> = MutableStateFlow(
        DataSourcesSettings(satelliteUrls = emptyList(), transceiversUrls = emptyList())
    )
    override val radioControlSettings: StateFlow<RadioControlSettings> = MutableStateFlow(
        RadioControlSettings(false, RadioControlSettings.MODEL_YAESU_FT817, "", "", "", "", 9600)
    )

    override val wavelogUploadSettings: StateFlow<WavelogUploadSettings> = MutableStateFlow(WavelogUploadSettings())
    override fun updateWavelogUploadSettings(settings: WavelogUploadSettings) = Unit
    override val lotwSettings: StateFlow<LoTWSettings> = MutableStateFlow(LoTWSettings())

    override fun setSelectedIds(ids: List<Int>) = TODO()
    override fun setSelectedTypes(types: List<String>) = TODO()
    override fun setPassesSettings(settings: PassesSettings) = TODO()
    override fun setStationPosition(latitude: Double, longitude: Double, altitude: Double): Boolean = TODO()
    override fun setStationPosition(): Boolean = TODO()
    override fun setStationPosition(locator: String): Boolean = TODO()
    override fun getCurrentGrid(): String? = TODO()
    override fun getSatelliteTypesIds(types: List<String>): List<Int> = TODO()
    override fun setSatelliteTypeIds(type: String, ids: List<Int>) = TODO()
    override fun updateDatabaseState(state: DatabaseState) = TODO()
    override fun updateRCSettings(settings: RCSettings) = TODO()
    override fun updateRotatorSettings(
        settings: com.rtbishop.look4sat.core.domain.rotator.RotatorSettings
    ) = TODO()
    override fun updateOtherSettings(transform: (OtherSettings) -> OtherSettings) = TODO()
    override fun updateFt4Settings(transform: (Ft4Settings) -> Ft4Settings) = TODO()
    override fun updateDataSourcesSettings(settings: DataSourcesSettings) = TODO()

    override val dataSourcesStatus: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())

    override fun updateDataSourcesStatus(status: Map<String, Int>) = TODO()

    override fun updateRadioControlSettings(settings: RadioControlSettings) = TODO()
    override fun getSatelliteOffset(catnum: Int): String = ""
    override fun setSatelliteOffset(catnum: Int, offset: String) = TODO()
    override fun getSatelliteMode(catnum: Int): String = ""
    override fun setSatelliteMode(catnum: Int, mode: String) = TODO()
    override fun getAmSatCallsign(): String = ""
    override fun setAmSatCallsign(callsign: String) = TODO()

    override fun getWorkedGrids(): Set<String> = TODO()
    override fun setWorkedGrids(grids: Set<String>) = TODO()
    override fun getWorkedGridQsos(): Map<String, List<GridQso>> = TODO()
    override fun setWorkedGridQsos(qsos: Map<String, List<GridQso>>) = TODO()
    override fun getRoamedGrids(): Set<String> = TODO()
    override fun setRoamedGrids(grids: Set<String>) = TODO()
    override fun getMarkedGridStations(): Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>> = emptyMap()
    override fun setMarkedGridStations(stations: Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>>) = Unit
    override fun updateLoTWSettings(settings: LoTWSettings) = TODO()
    override fun getLastLotwSyncDate(): String = ""
    override fun setLastLotwSyncDate(date: String) = Unit
    override fun getLastLotwSyncCallsign(): String = ""
    override fun setLastLotwSyncCallsign(callsign: String) = Unit
    override fun getWavelogStations(): List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo> = emptyList()
    override fun setWavelogStations(stations: List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo>) = Unit
    override fun getWavelogSyncCursors(): Map<String, Long> = emptyMap()
    override fun setWavelogSyncCursors(cursors: Map<String, Long>) = Unit
    override fun getWavelogSyncUrl(): String = ""
    override fun setWavelogSyncUrl(url: String) = Unit
    override fun getLastWavelogSyncEpochMs(): Long = 0L
    override fun setLastWavelogSyncEpochMs(value: Long) = Unit
    override fun getLastWavelogFullSyncEpochMs(): Long = 0L
    override fun setLastWavelogFullSyncEpochMs(value: Long) = Unit
}
