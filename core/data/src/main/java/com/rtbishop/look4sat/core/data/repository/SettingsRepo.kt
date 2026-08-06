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

import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import androidx.core.content.edit
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.domain.utility.qthToPosition
import com.rtbishop.look4sat.core.domain.utility.round
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class SettingsRepo(
    private val context: android.content.Context,
    private val locationManager: LocationManager,
    private val preferences: SharedPreferences,
    override val appVersionName: String
) : ISettingsRepo, LocationListenerCompat {

    private val keyBluetoothRotatorAddress = "bluetoothAddress"
    private val keyBluetoothRotatorName = "bluetoothName"
    private val keyBluetoothRotatorFormat = "bluetoothFormat"
    private val keyBluetoothRotatorState = "bluetoothState"
    private val keyBluetoothFrequencyState = "bluetoothFrequencyState"
    private val keyBluetoothFrequencyAddress = "bluetoothFrequencyAddress"
    private val keyBluetoothFrequencyFormat = "bluetoothFrequencyFormat"
    private val keyFilterShowDeepSpace = "filterShowDeepSpace"
    private val keyFilterHoursAhead = "filterHoursAhead"
    private val keyFilterMinElevation = "filterMinElevation"
    private val keyFilterAosStartMinute = "filterAosStartMinute"
    private val keyFilterAosEndMinute = "filterAosEndMinute"
    private val keyFilterAosInvert = "filterAosInvert"
    private val keyNumberOfRadios = "numberOfRadios"
    private val keyNumberOfSatellites = "numberOfSatellites"
    private val keyRotatorAddress = "rotatorAddress"
    private val keyRotatorPort = "rotatorPort"
    private val keyRotatorState = "rotatorState"
    private val keyRotatorFormat = "rotatorFormat"
    private val keyFrequencyState = "frequencyState"
    private val keyFrequencyAddress = "frequencyAddress"
    private val keyFrequencyPort = "frequencyPort"
    private val keyFrequencyFormat = "frequencyFormat"
    private val keySelectedIds = "selectedIds"
    private val keySelectedTypes = "selectedTypes"
    private val keySelectedModes = "selectedModes"
    private val keyStateOfAutoUpdate = "stateOfAutoUpdate"
    private val keyStateOfSensors = "stateOfSensors"
    private val keyStateOfSweep = "stateOfSweep"
    private val keyStateOfUtc = "stateOfUtc"
    private val keyStateOfLightTheme = "stateOfLightTheme"
    private val keyStateOfNightMode = "stateOfNightMode"
    private val keyHiddenScreens = "hiddenScreens"
    private val keyScreenOrder = "screenOrder"
    private val keyStationAltitude = "stationAltitude"
    private val keyStationLatitude = "stationLatitude"
    private val keyStationLongitude = "stationLongitude"
    private val keyStationQth = "stationQth"
    private val keyStationTimestamp = "stationTimestamp"
    private val keyUpdateTimestamp = "updateTimestamp"
    private val keyShouldSeeWarning = "shouldSeeWarning"
    private val keyShouldSeeWhatsNew = "shouldSeeWhatsNew_v$appVersionName"
    private val keySstvMode = "sstvMode"
    private val keyLowElevation = "lowElevation"
    private val keyHighElevation = "highElevation"
    private val keyUseCustomTle = "useCustomTle"
    private val keyUseCustomTransceivers = "useCustomTransceivers"
    private val keyTleUrl = "tleUrl"
    private val keyTransceiversUrl = "transceiversUrl"
    private val keySubMenuOrder = "subMenuOrder"
    private val keyWavelogUrl = "wavelogUrl"
    private val keyWavelogApiKey = "wavelogApiKey"
    private val keyWavelogStationId = "wavelogStationId"
    private val keyWavelogAutoUpload = "wavelogAutoUpload"
    private val separatorComma = ","

    //region # Satellites selection settings
    private val _satelliteSelection = MutableStateFlow(getSelectedIds())
    private val _typesSelection = MutableStateFlow(getSelectedTypes())
    override val selectedIds: StateFlow<List<Int>> = _satelliteSelection
    override val selectedTypes: StateFlow<List<String>> = _typesSelection

    override fun setSelectedIds(ids: List<Int>) {
        val selectionString = ids.joinToString(separatorComma)
        preferences.edit { putString(keySelectedIds, selectionString) }
        _satelliteSelection.value = ids
    }

    override fun setSelectedTypes(types: List<String>) {
        val typesString = types.joinToString(separatorComma)
        preferences.edit { putString(keySelectedTypes, typesString) }
        _typesSelection.value = types
    }

    private fun getSelectedIds(): List<Int> {
        val selectionString = preferences.getString(keySelectedIds, null)
        if (selectionString.isNullOrEmpty()) return emptyList()
        return selectionString.split(separatorComma).map { it.toInt() }
    }

    private fun getSelectedTypes(): List<String> {
        val typesString = preferences.getString(keySelectedTypes, "Amateur")
        if (typesString.isNullOrEmpty()) return emptyList()
        return typesString.split(separatorComma)
    }
    //endregion

    //region # Passes filter settings
    private val _passesSettings = MutableStateFlow(getPassesSettings())
    override val passesSettings: StateFlow<PassesSettings> = _passesSettings

    override fun setPassesSettings(settings: PassesSettings) = preferences.edit {
        putBoolean(keyFilterShowDeepSpace, settings.showDeepSpace)
        putInt(keyFilterHoursAhead, settings.hoursAhead)
        putLong(keyFilterMinElevation, settings.minElevation.toRawBits())
        putInt(keyFilterAosStartMinute, settings.aosStartMinute)
        putInt(keyFilterAosEndMinute, settings.aosEndMinute)
        putBoolean(keyFilterAosInvert, settings.invertAosTimeWindow)
        putString(keySelectedModes, settings.selectedModes.joinToString(separatorComma))
        _passesSettings.value = settings
    }

    private fun getPassesSettings(): PassesSettings {
        val showDeepSpace = preferences.getBoolean(keyFilterShowDeepSpace, true)
        val hoursAhead = preferences.getInt(keyFilterHoursAhead, 24)
        val minElevation = Double.fromBits(preferences.getLong(keyFilterMinElevation, 16.0.toRawBits()))
        val aosStartMinute = preferences.getInt(keyFilterAosStartMinute, 0).coerceIn(0, 23 * 60 + 59)
        val aosEndMinute = preferences.getInt(keyFilterAosEndMinute, 23 * 60 + 59).coerceIn(0, 23 * 60 + 59)
        val invertAosTimeWindow = preferences.getBoolean(keyFilterAosInvert, false)
        val selectedModesString = preferences.getString(keySelectedModes, null)
        val selectedModes = selectedModesString?.split(separatorComma)?.sorted() ?: emptyList()
        return PassesSettings(
            showDeepSpace,
            hoursAhead,
            minElevation,
            aosStartMinute,
            aosEndMinute,
            invertAosTimeWindow,
            selectedModes
        )
    }
    //endregion

    //region # Station position settings
    private val _stationPosition = MutableStateFlow(getStationPosition())
    private val providerDef = LocationManager.PASSIVE_PROVIDER
    private val providerGps = LocationManager.GPS_PROVIDER
    private val providerNet = LocationManager.NETWORK_PROVIDER
    override val stationPosition: StateFlow<GeoPos> = _stationPosition

    override fun onLocationChanged(location: Location) {
        setStationPosition(location.latitude, location.longitude, location.altitude)
    }

    override fun setStationPosition(latitude: Double, longitude: Double, altitude: Double): Boolean {
        val newLongitude = if (longitude > 180.0) longitude - 180 else longitude
        val locator = positionToQth(latitude, newLongitude) ?: return false
        setStationPosition(latitude, newLongitude, altitude, locator)
        return true
    }

    /** GPS 定位: 一次性 getCurrentLocation(GPS 优先, 15 秒超时), 拿到位置才返回 true */
    override suspend fun setStationPosition(): Boolean {
        // 权限前置: 无定位权限直接失败(不吞异常)
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            println("GPS: no fine location permission")
            return false
        }
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) return false
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val signal = android.os.CancellationSignal()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val executor = java.util.concurrent.Executor { handler.post(it) }
            // 15 秒超时
            val timeout = handler.postDelayed({
                signal.cancel()
                if (cont.isActive) cont.resume(false) { }
            }, 15_000L)
            val listener = androidx.core.util.Consumer<Location> { location ->
                handler.removeCallbacksAndMessages(null)
                setStationPosition(location.latitude, location.longitude, location.altitude)
                if (cont.isActive) cont.resume(true) { }
            }
            val hasGps = LocationManagerCompat.hasProvider(locationManager, providerGps)
            val provider = if (hasGps) providerGps else providerNet
            try {
                LocationManagerCompat.getCurrentLocation(locationManager, provider, signal, executor, listener)
            } catch (exception: SecurityException) {
                handler.removeCallbacksAndMessages(null)
                if (cont.isActive) cont.resume(false) { }
            }
            cont.invokeOnCancellation { signal.cancel(); handler.removeCallbacksAndMessages(null) }
        }
    }

    override fun setStationPosition(locator: String): Boolean {
        val position = qthToPosition(locator) ?: return false
        setStationPosition(position.latitude, position.longitude, 0.0, locator)
        return true
    }

    private fun getStationPosition(): GeoPos {
        val latitude = (preferences.getString(keyStationLatitude, null) ?: "0.0").toDouble()
        val longitude = (preferences.getString(keyStationLongitude, null) ?: "0.0").toDouble()
        val altitude = (preferences.getString(keyStationAltitude, null) ?: "0.0").toDouble()
        val qthLocator = preferences.getString(keyStationQth, null) ?: "JJ00aa"
        val timestamp = preferences.getLong(keyStationTimestamp, 0L)
        return GeoPos(latitude, longitude, altitude, qthLocator, timestamp)
    }

    private fun setStationPosition(latitude: Double, longitude: Double, altitude: Double, locator: String) {
        val newLat = latitude.round(5)
        val newLon = longitude.round(5)
        val newAlt = altitude.round(1)
        val timestamp = System.currentTimeMillis()
        println("Received new Position($newLat, $newLon, $newAlt) & Locator $locator")
        setStationPosition(GeoPos(newLat, newLon, newAlt, locator, timestamp))
    }

    private fun setStationPosition(stationPos: GeoPos) = preferences.edit {
        putString(keyStationLatitude, stationPos.latitude.toString())
        putString(keyStationLongitude, stationPos.longitude.toString())
        putString(keyStationAltitude, stationPos.altitude.toString())
        putString(keyStationQth, stationPos.qthLocator)
        putLong(keyStationTimestamp, stationPos.timestamp)
        _stationPosition.value = stationPos
    }
    //endregion

    //region # Database update settings
    private val _databaseState = MutableStateFlow(getDatabaseState())
    override val databaseState: StateFlow<DatabaseState> = _databaseState

    override fun getSatelliteTypesIds(types: List<String>): List<Int> {
        val idsSet = mutableSetOf<Int>()
        types.forEach { type ->
            val typeString = preferences.getString("type$type", null)
            val typeIds = if (typeString.isNullOrBlank()) {
                emptyList()
            } else {
                typeString.split(separatorComma).map { it.toInt() }
            }
            idsSet.addAll(typeIds)
        }
        return idsSet.toList()
    }

    override fun setSatelliteTypeIds(type: String, ids: List<Int>) {
        if (type == "All") return
        val typesString = ids.joinToString(separatorComma)
        preferences.edit { putString("type$type", typesString) }
    }

    override fun updateDatabaseState(state: DatabaseState) = preferences.edit {
        putInt(keyNumberOfSatellites, state.numberOfSatellites)
        putInt(keyNumberOfRadios, state.numberOfRadios)
        putLong(keyUpdateTimestamp, state.updateTimestamp)
        _databaseState.value = state
    }

    private fun getDatabaseState(): DatabaseState {
        val numberOfRadios = preferences.getInt(keyNumberOfRadios, 0)
        val numberOfSatellites = preferences.getInt(keyNumberOfSatellites, 0)
        val updateTimestamp = preferences.getLong(keyUpdateTimestamp, 0L)
        return DatabaseState(numberOfRadios, numberOfSatellites, updateTimestamp)
    }
    //endregion

    //region # RC settings
    init {
        migrateRCFormats()
    }

    // TODO: Remove after a few releases (added in v4.2.0)
    private val keyRCFormatsMigrated = "rcFormatsMigrated"

    private fun migrateRCFormats() {
        if (preferences.getBoolean(keyRCFormatsMigrated, false)) return
        val formatKeys = listOf(
            keyRotatorFormat, keyFrequencyFormat, keyBluetoothRotatorFormat, keyBluetoothFrequencyFormat
        )
        preferences.edit {
            for (key in formatKeys) {
                val value = preferences.getString(key, null) ?: continue
                if (value.contains("_") && !value.startsWith("\\")) {
                    putString(key, "\\$value")
                }
            }
            putBoolean(keyRCFormatsMigrated, true)
        }
    }

    private val _rcSettings = MutableStateFlow(getRCSettings())
    override val rcSettings: StateFlow<RCSettings> = _rcSettings

    override fun updateRCSettings(settings: RCSettings) {
        preferences.edit {
            putBoolean(keyRotatorState, settings.rotatorState)
            putString(keyRotatorAddress, settings.rotatorAddress)
            putString(keyRotatorPort, settings.rotatorPort)
            putString(keyRotatorFormat, settings.rotatorFormat)
            putBoolean(keyFrequencyState, settings.frequencyState)
            putString(keyFrequencyAddress, settings.frequencyAddress)
            putString(keyFrequencyPort, settings.frequencyPort)
            putString(keyFrequencyFormat, settings.frequencyFormat)
            putBoolean(keyBluetoothRotatorState, settings.bluetoothRotatorState)
            putString(keyBluetoothRotatorFormat, settings.bluetoothRotatorFormat)
            putString(keyBluetoothRotatorName, settings.bluetoothRotatorName)
            putString(keyBluetoothRotatorAddress, settings.bluetoothRotatorAddress)
            putBoolean(keyBluetoothFrequencyState, settings.bluetoothFrequencyState)
            putString(keyBluetoothFrequencyFormat, settings.bluetoothFrequencyFormat)
            putString(keyBluetoothFrequencyAddress, settings.bluetoothFrequencyAddress)
        }
        _rcSettings.value = settings
    }

    private fun getRCSettings(): RCSettings = RCSettings(
        rotatorState = preferences.getBoolean(keyRotatorState, false),
        rotatorAddress = preferences.getString(keyRotatorAddress, null) ?: "127.0.0.1",
        rotatorPort = preferences.getString(keyRotatorPort, null) ?: "4533",
        rotatorFormat = preferences.getString(keyRotatorFormat, null) ?: $$"P $AZ $EL",
        frequencyState = preferences.getBoolean(keyFrequencyState, false),
        frequencyAddress = preferences.getString(keyFrequencyAddress, null) ?: "127.0.0.1",
        frequencyPort = preferences.getString(keyFrequencyPort, null) ?: "4532",
        frequencyFormat = preferences.getString(keyFrequencyFormat, null) ?: $$"F $FREQ",
        bluetoothRotatorState = preferences.getBoolean(keyBluetoothRotatorState, false),
        bluetoothRotatorFormat = preferences.getString(keyBluetoothRotatorFormat, null) ?: $$"P $AZ $EL",
        bluetoothRotatorName = preferences.getString(keyBluetoothRotatorName, null) ?: "Default",
        bluetoothRotatorAddress = preferences.getString(keyBluetoothRotatorAddress, null) ?: "00:0C:BF:13:80:5D",
        bluetoothFrequencyState = preferences.getBoolean(keyBluetoothFrequencyState, false),
        bluetoothFrequencyAddress = preferences.getString(keyBluetoothFrequencyAddress, null) ?: "00:0C:BF:13:80:5D",
        bluetoothFrequencyFormat = preferences.getString(keyBluetoothFrequencyFormat, null) ?: $$"F $FREQ"
    )
    //endregion

    //region # Other settings
    private val _otherSettings = MutableStateFlow(getOtherSettings())
    override val otherSettings: StateFlow<OtherSettings> = _otherSettings

    override fun updateOtherSettings(transform: (OtherSettings) -> OtherSettings) {
        _otherSettings.update { current ->
            val new = transform(current)
            preferences.edit {
                putBoolean(keyStateOfAutoUpdate, new.stateOfAutoUpdate)
                putBoolean(keyStateOfSensors, new.stateOfSensors)
                putBoolean(keyStateOfSweep, new.stateOfSweep)
                putBoolean(keyStateOfUtc, new.stateOfUtc)
                putBoolean(keyStateOfLightTheme, new.stateOfLightTheme)
                putBoolean(keyStateOfNightMode, new.stateOfNightMode)
                putBoolean(keyShouldSeeWarning, new.shouldSeeWarning)
                putBoolean(keyShouldSeeWhatsNew, new.shouldSeeWhatsNew)
                putString(keySstvMode, new.sstvMode)
                putLong(keyLowElevation, new.lowElevation.toRawBits())
                putLong(keyHighElevation, new.highElevation.toRawBits())
                putStringSet(keyHiddenScreens, new.hiddenScreens.toSet())
                putString(keyScreenOrder, new.screenOrder.joinToString(","))
                putString(keySubMenuOrder, new.subMenuOrder.joinToString(","))
                putString(keyWavelogUrl, new.wavelogUrl)
                putString(keyWavelogApiKey, new.wavelogApiKey)
                putString(keyWavelogStationId, new.wavelogStationId)
                putBoolean(keyWavelogAutoUpload, new.wavelogAutoUpload)
            }
            new
        }
    }

    private fun getOtherSettings(): OtherSettings = OtherSettings(
        stateOfAutoUpdate = preferences.getBoolean(keyStateOfAutoUpdate, true),
        stateOfSensors = preferences.getBoolean(keyStateOfSensors, true),
        stateOfSweep = preferences.getBoolean(keyStateOfSweep, true),
        stateOfUtc = preferences.getBoolean(keyStateOfUtc, false),
        stateOfLightTheme = preferences.getBoolean(keyStateOfLightTheme, false),
        stateOfNightMode = preferences.getBoolean(keyStateOfNightMode, false),
        shouldSeeWarning = preferences.getBoolean(keyShouldSeeWarning, true),
        shouldSeeWhatsNew = preferences.getBoolean(keyShouldSeeWhatsNew, true),
        sstvMode = preferences.getString(keySstvMode, null) ?: "Auto",
        hiddenScreens = preferences.getStringSet(keyHiddenScreens, emptySet())?.toList() ?: emptyList(),
        screenOrder = preferences.getString(keyScreenOrder, null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
        subMenuOrder = preferences.getString(keySubMenuOrder, null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
        lowElevation = Double.fromBits(preferences.getLong(keyLowElevation, 15.0.toRawBits())),
        highElevation = Double.fromBits(preferences.getLong(keyHighElevation, 45.0.toRawBits())),
        wavelogUrl = preferences.getString(keyWavelogUrl, null) ?: "",
        wavelogApiKey = preferences.getString(keyWavelogApiKey, null) ?: "",
        wavelogStationId = preferences.getString(keyWavelogStationId, null) ?: "",
        wavelogAutoUpload = preferences.getBoolean(keyWavelogAutoUpload, false)
    )
    //endregion

    //region # Data sources settings
    private val _dataSourcesSettings = MutableStateFlow(getDataSourcesSettings())
    override val dataSourcesSettings: StateFlow<DataSourcesSettings> = _dataSourcesSettings

    override fun updateDataSourcesSettings(settings: DataSourcesSettings) {
        preferences.edit {
            putBoolean(keyUseCustomTle, settings.useCustomTLE)
            putBoolean(keyUseCustomTransceivers, settings.useCustomTransceivers)
            putString(keyTleUrl, settings.tleUrl)
            putString(keyTransceiversUrl, settings.transceiversUrl)
        }
        _dataSourcesSettings.value = settings
    }

    private fun getDataSourcesSettings(): DataSourcesSettings {
        // 4.4.8 修复: 旧版 example.com 占位 URL 视为未配置 -> 替换为真实默认 URL 且开关强制关闭,
        // 否则在线更新的 All/SatNOGS 源会指向错误地址导致更新失败
        val storedTleUrl = preferences.getString(keyTleUrl, Sources.defaultTleUrl) ?: Sources.defaultTleUrl
        val storedTxUrl = preferences.getString(keyTransceiversUrl, Sources.defaultTransceiversUrl) ?: Sources.defaultTransceiversUrl
        val tleUrl = if (storedTleUrl == "https://example.com/tle.txt") Sources.defaultTleUrl else storedTleUrl
        val txUrl = if (storedTxUrl == "https://example.com/radio.json") Sources.defaultTransceiversUrl else storedTxUrl
        return DataSourcesSettings(
            useCustomTLE = preferences.getBoolean(keyUseCustomTle, false) && tleUrl != Sources.defaultTleUrl,
            useCustomTransceivers = preferences.getBoolean(keyUseCustomTransceivers, false) && txUrl != Sources.defaultTransceiversUrl,
            tleUrl = tleUrl,
            transceiversUrl = txUrl
        )
    }
    //endregion

    //region # Radio control settings
    private val keyRadioControlEnabled = "radioControlEnabled"
    private val keyRadioModel = "radioModel"
    private val keyTxRadioAddress = "txRadioAddress"
    private val keyRxRadioAddress = "rxRadioAddress"
    private val keyTxRadioName = "txRadioName"
    private val keyRxRadioName = "rxRadioName"
    private val keyRadioBaudRate = "radioBaudRate"
    private val keyRadioSplitMode = "radioSplitMode"

    private val _radioControlSettings = MutableStateFlow(getRadioControlSettings())
    override val radioControlSettings: StateFlow<RadioControlSettings> = _radioControlSettings

    override fun updateRadioControlSettings(settings: RadioControlSettings) {
        preferences.edit {
            putBoolean(keyRadioControlEnabled, settings.enabled)
            putString(keyRadioModel, settings.radioModel)
            putString(keyTxRadioAddress, settings.txRadioAddress)
            putString(keyRxRadioAddress, settings.rxRadioAddress)
            putString(keyTxRadioName, settings.txRadioName)
            putString(keyRxRadioName, settings.rxRadioName)
            putInt(keyRadioBaudRate, settings.baudRate)
            putBoolean(keyRadioSplitMode, settings.splitMode)
        }
        _radioControlSettings.value = settings
    }

    private fun getRadioControlSettings(): RadioControlSettings = RadioControlSettings(
        enabled = preferences.getBoolean(keyRadioControlEnabled, false),
        radioModel = preferences.getString(keyRadioModel, null) ?: RadioControlSettings.MODEL_YAESU_FT817,
        txRadioAddress = preferences.getString(keyTxRadioAddress, null) ?: "",
        rxRadioAddress = preferences.getString(keyRxRadioAddress, null) ?: "",
        txRadioName = preferences.getString(keyTxRadioName, null) ?: "TX Radio",
        rxRadioName = preferences.getString(keyRxRadioName, null) ?: "RX Radio",
        baudRate = preferences.getInt(keyRadioBaudRate, 4800),
        splitMode = preferences.getBoolean(keyRadioSplitMode, false)
    )
    //endregion
}
