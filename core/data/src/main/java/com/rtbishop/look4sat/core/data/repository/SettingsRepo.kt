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
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.domain.utility.qthToPosition
import com.rtbishop.look4sat.core.domain.utility.round
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.Locale
import com.rtbishop.look4sat.core.domain.model.Constants
import com.rtbishop.look4sat.core.domain.source.Sources

class SettingsRepo(
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
    private val keyFrequencyOffsetHz = "frequencyOffsetHz"
    private val keySelectedIds = "selectedIds"
    private val keySelectedTypes = "selectedTypes"
    private val keySelectedModes = "selectedModes"
    private val keyStateOfAutoUpdate = "stateOfAutoUpdate"
    private val keyStateOfSensors = "stateOfSensors"
    private val keyStateOfSweep = "stateOfSweep"
    private val keyStateOfUtc = "stateOfUtc"
    private val keyStateOfLightTheme = "stateOfLightTheme"
    private val keyStateOfNightMode = "stateOfNightMode"
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
    private val keySatelliteUrls = "satelliteUrls"
    private val keyTransceiversUrls = "transceiversUrls"
    private val keySatelliteEnabled = "satelliteEnabled"
    private val keyTransceiversEnabled = "transceiversEnabled"
    private val keySatnogsTleSourceMigration = "satnogsTleSourceMigration"
    private val separatorComma = ","
    private val separatorUrl = "\n"
    private val legacyCelestrakSatnogsUrl =
        "https://celestrak.org/NORAD/elements/gp.php?GROUP=satnogs&FORMAT=csv"

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

    override fun setStationPosition(): Boolean {
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) return false
        try {
            val hasGps = LocationManagerCompat.hasProvider(locationManager, providerGps)
            val hasNet = LocationManagerCompat.hasProvider(locationManager, providerNet)
            val provider = if (hasGps) providerGps else if (hasNet) providerNet else providerDef
            val location = locationManager.getLastKnownLocation(providerDef)
            if (location == null || System.currentTimeMillis() - location.time > 600_000L) {
                println("Requesting location for $provider provider")
                locationManager.requestLocationUpdates(provider, 0L, 0f, this)
            } else {
                setStationPosition(location.latitude, location.longitude, location.altitude)
            }
        } catch (exception: SecurityException) {
            println("No permissions were given - $exception")
        }
        return true
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
        val newLat = latitude.round(4)
        val newLon = longitude.round(4)
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
        val clampedFreqOffsetHz = settings.frequencyOffsetHz.coerceIn(
            Constants.FREQ_OFFSET_MIN_HZ,
            Constants.FREQ_OFFSET_MAX_HZ
        )
        preferences.edit {
            putBoolean(keyRotatorState, settings.rotatorState)
            putString(keyRotatorAddress, settings.rotatorAddress)
            putString(keyRotatorPort, settings.rotatorPort)
            putString(keyRotatorFormat, settings.rotatorFormat)
            putBoolean(keyFrequencyState, settings.frequencyState)
            putString(keyFrequencyAddress, settings.frequencyAddress)
            putString(keyFrequencyPort, settings.frequencyPort)
            putString(keyFrequencyFormat, settings.frequencyFormat)
            putLong(keyFrequencyOffsetHz, clampedFreqOffsetHz)
            putBoolean(keyBluetoothRotatorState, settings.bluetoothRotatorState)
            putString(keyBluetoothRotatorFormat, settings.bluetoothRotatorFormat)
            putString(keyBluetoothRotatorName, settings.bluetoothRotatorName)
            putString(keyBluetoothRotatorAddress, settings.bluetoothRotatorAddress)
            putBoolean(keyBluetoothFrequencyState, settings.bluetoothFrequencyState)
            putString(keyBluetoothFrequencyFormat, settings.bluetoothFrequencyFormat)
            putString(keyBluetoothFrequencyAddress, settings.bluetoothFrequencyAddress)
        }
        _rcSettings.value = settings.copy(frequencyOffsetHz = clampedFreqOffsetHz)
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
        frequencyOffsetHz = preferences.getLong(keyFrequencyOffsetHz, 0L)
            .coerceIn(Constants.FREQ_OFFSET_MIN_HZ, Constants.FREQ_OFFSET_MAX_HZ),
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
        lowElevation = Double.fromBits(preferences.getLong(keyLowElevation, 15.0.toRawBits())),
        highElevation = Double.fromBits(preferences.getLong(keyHighElevation, 45.0.toRawBits()))
    )
    //endregion

    //region # Data sources settings
    private val _dataSourcesSettings = MutableStateFlow(getDataSourcesSettings())
    override val dataSourcesSettings: StateFlow<DataSourcesSettings> = _dataSourcesSettings

    override fun updateDataSourcesSettings(settings: DataSourcesSettings) {
        // Normalize the enabled lists so they are positionally aligned with the URL lists.
        // Missing entries default to enabled (true), keeping the persisted "one flag per URL"
        // invariant intact even when a default empty list is used to construct the model.
        val normalized = settings.copy(
            satelliteEnabled = alignFlags(settings.satelliteUrls, settings.satelliteEnabled),
            transceiversEnabled = alignFlags(settings.transceiversUrls, settings.transceiversEnabled)
        )
        preferences.edit {
            putString(keySatelliteUrls, normalized.satelliteUrls.joinToString(separatorUrl))
            putString(keyTransceiversUrls, normalized.transceiversUrls.joinToString(separatorUrl))
            putString(keySatelliteEnabled, normalized.satelliteEnabled.joinToString(separatorComma))
            putString(keyTransceiversEnabled, normalized.transceiversEnabled.joinToString(separatorComma))
            putBoolean(keySatnogsTleSourceMigration, true)
        }
        _dataSourcesSettings.value = normalized
    }

    private fun getDataSourcesSettings(): DataSourcesSettings = DataSourcesSettings(
        satelliteUrls = getDataSourceUrls(
            key = keySatelliteUrls,
            defaultUrls = Sources.satelliteDataUrls.values.filter { it.isNotBlank() },
            legacyEnabledKey = keyUseCustomTle,
            legacyUrlKey = keyTleUrl
        ).migrateSatnogsTleSource(),
        transceiversUrls = getDataSourceUrls(
            key = keyTransceiversUrls,
            defaultUrls = Sources.transceiversDataUrls.values.filter { it.isNotBlank() },
            legacyEnabledKey = keyUseCustomTransceivers,
            legacyUrlKey = keyTransceiversUrl
        ),
        satelliteEnabled = readEnabledFlags(keySatelliteEnabled),
        transceiversEnabled = readEnabledFlags(keyTransceiversEnabled)
    )

    /** Read the persisted per-source enabled flags (empty when never stored). */
    private fun readEnabledFlags(key: String): List<Boolean> {
        return preferences.getString(key, null)
            ?.split(separatorComma)
            ?.mapNotNull { it.trim() }
            ?.filter { it == "true" || it == "false" }
            ?.map { it == "true" }
            ?: emptyList()
    }

    /** Keep the flags positionally aligned with the URL list, defaulting to enabled. */
    private fun alignFlags(urls: List<String>, flags: List<Boolean>): List<Boolean> {
        if (flags.size >= urls.size) return flags.take(urls.size)
        return flags + List(urls.size - flags.size) { true }
    }

    private fun getDataSourceUrls(
        key: String,
        defaultUrls: List<String>,
        legacyEnabledKey: String,
        legacyUrlKey: String
    ): List<String> {
        val stored = preferences.getString(key, null)
            ?.split(separatorUrl)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
        if (stored != null) return stored
        val legacyCustomUrl = preferences.getString(legacyUrlKey, null)?.trim().orEmpty()
        return if (preferences.getBoolean(legacyEnabledKey, false) && legacyCustomUrl.isNotBlank()) {
            (defaultUrls + legacyCustomUrl).distinct()
        } else {
            defaultUrls
        }
    }

    private fun List<String>.migrateSatnogsTleSource(): List<String> {
        if (preferences.getBoolean(keySatnogsTleSourceMigration, false)) return this
        val satnogsTleUrl = Sources.satelliteDataUrls["SatNOGS"].orEmpty()
        if (satnogsTleUrl.isBlank() || containsSourceUrl(satnogsTleUrl)) return this
        val celestrakSatnogsIndex = indexOfFirst { isSameSourceUrl(it, legacyCelestrakSatnogsUrl) }
        if (celestrakSatnogsIndex < 0) return this

        val migrated = toMutableList().apply { add(celestrakSatnogsIndex + 1, satnogsTleUrl) }
        preferences.edit {
            putString(keySatelliteUrls, migrated.joinToString(separatorUrl))
            putBoolean(keySatnogsTleSourceMigration, true)
        }
        return migrated
    }

    private fun List<String>.containsSourceUrl(url: String): Boolean = any { isSameSourceUrl(it, url) }

    private fun isSameSourceUrl(first: String, second: String): Boolean =
        normalizeSourceUrl(first).equals(normalizeSourceUrl(second), ignoreCase = true)

    private fun normalizeSourceUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
    }
    //endregion

    //region # Data sources status
    private val _dataSourcesStatus = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val dataSourcesStatus: StateFlow<Map<String, Int>> = _dataSourcesStatus

    override fun updateDataSourcesStatus(status: Map<String, Int>) {
        _dataSourcesStatus.value = status
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

    //region # Per-satellite calculator offset settings
    private val keySatelliteOffsets = "satelliteOffsets"
    private val keyLegacySatelliteOffsetPrefix = "offset_khz_"

    override fun getSatelliteOffset(catnum: Int): String {
        val json = preferences.getString(keySatelliteOffsets, "{}") ?: "{}"
        val stored = try {
            JSONObject(json).optString(catnum.toString(), "")
        } catch (_: Exception) {
            ""
        }
        if (stored.isNotEmpty()) return stored

        // Lazy migration from the fork's old UI-layer implementation, which stored
        // one SharedPreferences entry per satellite directly from TransceiversPage.
        val legacyKey = "$keyLegacySatelliteOffsetPrefix$catnum"
        val legacy = preferences.getString(legacyKey, "").orEmpty()
        if (legacy.isNotEmpty()) {
            setSatelliteOffset(catnum, legacy)
        }
        return legacy
    }

    override fun setSatelliteOffset(catnum: Int, offset: String) {
        val json = preferences.getString(keySatelliteOffsets, "{}") ?: "{}"
        val updated = try {
            val obj = JSONObject(json)
            if (offset.isEmpty()) obj.remove(catnum.toString()) else obj.put(catnum.toString(), offset)
            obj.toString()
        } catch (_: Exception) {
            if (offset.isEmpty()) "{}" else """{"$catnum": "$offset"}"""
        }
        preferences.edit {
            putString(keySatelliteOffsets, updated)
            remove("$keyLegacySatelliteOffsetPrefix$catnum")
        }
    }
    //endregion

    //region # AMSAT status report settings
    private val keyAmSatCallsign = "amSatCallsign"

    override fun getAmSatCallsign(): String {
        return preferences.getString(keyAmSatCallsign, "").orEmpty()
    }

    override fun setAmSatCallsign(callsign: String) {
        preferences.edit { putString(keyAmSatCallsign, callsign.trim().uppercase(Locale.US)) }
    }
    //endregion
}
