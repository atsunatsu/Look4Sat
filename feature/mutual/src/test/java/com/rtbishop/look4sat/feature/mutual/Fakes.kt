package com.rtbishop.look4sat.feature.mutual

import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
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
}

/**
 * Test fake for ISettingsRepo. Only [stationPosition] is backed by mutable
 * state; everything else the ViewModel never touches fails loudly.
 */
class FakeSettingsRepo(initialPosition: GeoPos = GeoPos(23.13, 113.26)) : ISettingsRepo {

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
    override val otherSettings: StateFlow<OtherSettings> = MutableStateFlow(
        OtherSettings(false, false, false, false, false, false, false, false)
    )
    override val dataSourcesSettings: StateFlow<DataSourcesSettings> = MutableStateFlow(
        DataSourcesSettings(satelliteUrls = emptyList(), transceiversUrls = emptyList())
    )
    override val radioControlSettings: StateFlow<RadioControlSettings> = MutableStateFlow(
        RadioControlSettings(false, RadioControlSettings.MODEL_YAESU_FT817, "", "", "", "", 9600)
    )

    override fun setSelectedIds(ids: List<Int>) = TODO()
    override fun setSelectedTypes(types: List<String>) = TODO()
    override fun setPassesSettings(settings: PassesSettings) = TODO()
    override fun setStationPosition(latitude: Double, longitude: Double, altitude: Double): Boolean = TODO()
    override fun setStationPosition(): Boolean = TODO()
    override fun setStationPosition(locator: String): Boolean = TODO()
    override fun getSatelliteTypesIds(types: List<String>): List<Int> = TODO()
    override fun setSatelliteTypeIds(type: String, ids: List<Int>) = TODO()
    override fun updateDatabaseState(state: DatabaseState) = TODO()
    override fun updateRCSettings(settings: RCSettings) = TODO()
    override fun updateOtherSettings(transform: (OtherSettings) -> OtherSettings) = TODO()
    override fun updateDataSourcesSettings(settings: DataSourcesSettings) = TODO()
    override fun updateRadioControlSettings(settings: RadioControlSettings) = TODO()
    override fun getSatelliteOffset(catnum: Int): String = ""
    override fun setSatelliteOffset(catnum: Int, offset: String) = TODO()
    override fun getAmSatCallsign(): String = ""
    override fun setAmSatCallsign(callsign: String) = TODO()
}