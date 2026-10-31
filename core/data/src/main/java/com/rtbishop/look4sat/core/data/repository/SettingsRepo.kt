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
import com.rtbishop.look4sat.core.domain.model.MapSource
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.supportedRadioBaudRates
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings

import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
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
    private val keyFilterHoursBefore = "filterHoursBefore"
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
    private val keyStateOfAutoLotwSync = "stateOfAutoLotwSync"
    private val keyStateOfSensors = "stateOfSensors"
    private val keyCompassOffsetDegrees = "compassOffsetDegrees"
    private val keyStateOfSweep = "stateOfSweep"
    private val keyStateOfUtc = "stateOfUtc"
    private val keyStateOfLightTheme = "stateOfLightTheme"
    private val keyStateOfNightMode = "stateOfNightMode"
    private val keyStateOfMapGrid = "stateOfMapGrid"
    private val keyStateOfMapFirstCall = "stateOfMapFirstCall"
    private val keyMapElevationAngles = "mapElevationAngles"
    private val keyMapElevationContoursEnabled = "mapElevationContoursEnabled"
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
    private val keyMapSource = "mapSource"
    private val keyTiandituKey = "tiandituKey"
    private val keyUseCustomTle = "useCustomTle"
    private val keyUseCustomTransceivers = "useCustomTransceivers"
    private val keyTleUrl = "tleUrl"
    private val keyTransceiversUrl = "transceiversUrl"
    private val keySatelliteUrls = "satelliteUrls"
    private val keyTransceiversUrls = "transceiversUrls"
    private val keySatelliteEnabled = "satelliteEnabled"
    private val keyTransceiversEnabled = "transceiversEnabled"
    private val keySatnogsTleSourceMigration = "satnogsTleSourceMigration"
    private val keyAutoTleSourceMigration = "autoTleSourceMigration"
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
        val validTypes = sanitizeSelectedTypes(types)
        preferences.edit { putString(keySelectedTypes, validTypes.joinToString(separatorComma)) }
        _typesSelection.value = validTypes
    }

    /** Remove retired source labels before persisting any user selection. */
    private fun sanitizeSelectedTypes(types: List<String>): List<String> {
        if (types.isEmpty()) return emptyList() // An explicit empty selection also shows all.
        val supported = (Sources.virtualTypeNames +
            Sources.satelliteDataUrls.keys.filterNot { it == "Other" }).toSet()
        val valid = types.map { it.trim() }.filter { it in supported }.distinct()
        // "All" overrides every narrower source or activity filter.
        return when {
            Sources.allSourceType in valid -> listOf(Sources.allSourceType)
            valid.isNotEmpty() -> valid
            else -> listOf(Sources.allSourceType)
        }
    }

    private fun getSelectedIds(): List<Int> {
        val selectionString = preferences.getString(keySelectedIds, null)
        if (selectionString.isNullOrEmpty()) return emptyList()
        return selectionString.split(separatorComma).map { it.toInt() }
    }

    private fun getSelectedTypes(): List<String> {
        val stored = preferences.getString(keySelectedTypes, null)
        val types = when {
            stored == null -> listOf(Sources.allSourceType) // New installs start with all satellites.
            stored.isEmpty() -> emptyList() // Preserve a user's deliberately empty filter.
            else -> sanitizeSelectedTypes(stored.split(separatorComma))
        }
        // Migrate old invisible "Amateur" selections, and drop their obsolete
        // cached catnums. Never touch selectedIds (the satellites used for passes).
        val normalized = types.joinToString(separatorComma)
        if (stored != normalized || preferences.contains("typeAmateur")) {
            preferences.edit {
                putString(keySelectedTypes, normalized)
                remove("typeAmateur")
            }
        }
        return types
    }
    //endregion

    //region # Worked-grid data (written by the LoTW / Wavelog syncs)
    private val keyWorkedGrids = "workedGrids"

    override fun getWorkedGrids(): Set<String> {
        val json = preferences.getString(keyWorkedGrids, null).orEmpty()
        if (json.isBlank()) return emptySet()
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    override fun setWorkedGrids(grids: Set<String>) {
        val array = org.json.JSONArray()
        grids.sorted().forEach { array.put(it) }
        preferences.edit { putString(keyWorkedGrids, array.toString()) }
    }

    // Confirmed satellite QSOs per worked gridsquare, persisted as a single JSON
    // object: {"OL62":[{"c":call,"t":epochMs,"s":sat,"m":mode,"bu":up,"bd":down}]}.
    private val keyWorkedGridQsos = "workedGridQsos"

    override fun getWorkedGridQsos(): Map<String, List<com.rtbishop.look4sat.core.domain.model.GridQso>> {
        val json = preferences.getString(keyWorkedGridQsos, null).orEmpty()
        if (json.isBlank()) return emptyMap()
        return try {
            val root = org.json.JSONObject(json)
            val result = mutableMapOf<String, List<com.rtbishop.look4sat.core.domain.model.GridQso>>()
            for (grid in root.keys()) {
                val array = root.optJSONArray(grid) ?: continue
                val list = (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    com.rtbishop.look4sat.core.domain.model.GridQso(
                        call = o.optString("c"),
                        epochMs = o.optLong("t"),
                        satName = o.optString("s"),
                        mode = o.optString("m"),
                        bandUp = o.optString("bu"),
                        bandDown = o.optString("bd"),
                        // New award fields: absent in pre-award data -> null.
                        dxcc = o.optInt("dx", 0).takeIf { it > 0 },
                        country = o.optString("cty").ifBlank { null },
                        cqz = o.optInt("cq", 0).takeIf { it > 0 },
                        state = o.optString("st").ifBlank { null },
                        iota = o.optString("io").ifBlank { null },
                        myIota = o.optString("mio").ifBlank { null },
                        // Own-grid field: absent in pre-myGrid data -> null.
                        myGrid = o.optString("mg").ifBlank { null },
                        // Multi-grid 台址 fields (v4.4.7-ba7opf.16+): "mgs" is
                        // the full grid set (MY_GRIDSQUARE + MY_VUCC_GRIDS);
                        // pre-multi-grid data has only "mg" and falls back to a
                        // single-element set so every consumer sees myGrids.
                        myGrids = o.optJSONArray("mgs")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optString(i).takeIf { it.isNotBlank() }
                            }.toSet()
                        }?.takeIf { it.isNotEmpty() }
                            ?: o.optString("mg").ifBlank { null }?.let { setOf(it) }
                            ?: emptySet(),
                        myCallsign = o.optString("mc").ifBlank { null },
                        stationKey = o.optString("sk").ifBlank { null },
                        theirFineGrids = o.optJSONArray("tg6")?.let { arr ->
                            (0 until arr.length()).mapNotNull { index ->
                                arr.optString(index).takeIf { it.isNotBlank() }
                            }
                        }.orEmpty(),
                        myFineGrids = o.optJSONArray("mgf")?.let { arr ->
                            (0 until arr.length()).mapNotNull { index ->
                                arr.optString(index).takeIf { it.isNotBlank() }
                            }
                        }.orEmpty()
                    )
                }
                if (list.isNotEmpty()) result[grid] = list
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun setWorkedGridQsos(qsos: Map<String, List<com.rtbishop.look4sat.core.domain.model.GridQso>>) {
        val root = org.json.JSONObject()
        for ((grid, list) in qsos) {
            val array = org.json.JSONArray()
            for (q in list) {
                array.put(
                    org.json.JSONObject()
                        .put("c", q.call)
                        .put("t", q.epochMs)
                        .put("s", q.satName)
                        .put("m", q.mode)
                        .put("bu", q.bandUp)
                        .put("bd", q.bandDown)
                        .put("dx", q.dxcc ?: 0)
                        .put("cty", q.country ?: "")
                        .put("cq", q.cqz ?: 0)
                        .put("st", q.state ?: "")
                        .put("io", q.iota ?: "")
                        .put("mio", q.myIota ?: "")
                        .put("mg", q.myGrid ?: "")
                        .put("mgs", org.json.JSONArray(q.myGrids.sorted()))
                        .put("mc", q.myCallsign ?: "")
                        .put("sk", q.stationKey ?: "")
                        .put("tg6", org.json.JSONArray(q.theirFineGrids))
                        .put("mgf", org.json.JSONArray(q.myFineGrids))
                )
            }
            root.put(grid, array)
        }
        preferences.edit { putString(keyWorkedGridQsos, root.toString()) }
    }

    // Distinct 4-char gridsquares the account operated from (LoTW <MY_GRIDSQUARE>,
    // satellite QSOs only). Stored as a JSONArray like workedGrids.
    private val keyRoamedGrids = "roamedGrids"

    override fun getRoamedGrids(): Set<String> {
        val json = preferences.getString(keyRoamedGrids, null).orEmpty()
        if (json.isBlank()) return emptySet()
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    override fun setRoamedGrids(grids: Set<String>) {
        val array = org.json.JSONArray()
        grids.sorted().forEach { array.put(it) }
        preferences.edit { putString(keyRoamedGrids, array.toString()) }
    }

    // Shared across every map entry point, including the radar pass / matching map.
    // Use JSON so values can be decimals and the chosen ring order is preserved.
    private val _mapElevationAngles = MutableStateFlow(readMapElevationAngles())
    override val mapElevationAngles: StateFlow<List<Double>> = _mapElevationAngles

    // Kept alongside the contour angles: every map observes the same StateFlow.
    // Missing preference deliberately means off so upgrading does not surprise users.
    private val _mapElevationContoursEnabled =
        MutableStateFlow(preferences.getBoolean(keyMapElevationContoursEnabled, false))
    override val mapElevationContoursEnabled: StateFlow<Boolean> = _mapElevationContoursEnabled

    override fun setMapElevationContoursEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(keyMapElevationContoursEnabled, enabled) }
        _mapElevationContoursEnabled.value = enabled
    }

    private fun readMapElevationAngles(): List<Double> {
        val json = preferences.getString(keyMapElevationAngles, null) ?: return listOf(10.0)
        return try {
            val array = org.json.JSONArray(json)
            val values = (0 until array.length()).mapNotNull { index ->
                array.optDouble(index, Double.NaN).takeIf { it.isFinite() && it in 0.0..90.0 }
            }.distinct()
            values.ifEmpty { listOf(10.0) }
        } catch (_: Exception) {
            listOf(10.0)
        }
    }

    override fun setMapElevationAngles(angles: List<Double>) {
        val values = angles.filter { it.isFinite() && it in 0.0..90.0 }.distinct()
            .ifEmpty { listOf(10.0) }
        val array = org.json.JSONArray()
        values.forEach { array.put(it) }
        preferences.edit { putString(keyMapElevationAngles, array.toString()) }
        _mapElevationAngles.value = values
    }

    // VUCC operated-station selection. Store JSON because a station-set key can
    // contain commas (e.g. "OL62,OL63"); splitting on commas would corrupt it.
    // An unset or empty selection represents All on the first visit.
    private val keyVuccSelectedStationIds = "vuccSelectedStationIds"

    override fun getVuccSelectedStationIds(): Set<String> {
        val json = preferences.getString(keyVuccSelectedStationIds, null).orEmpty()
        if (json.isBlank()) return emptySet()
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).trim().takeIf { it.isNotEmpty() }
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    override fun setVuccSelectedStationIds(ids: Set<String>) {
        val array = org.json.JSONArray()
        ids.sorted().forEach { array.put(it) }
        preferences.edit { putString(keyVuccSelectedStationIds, array.toString()) }
    }

    // Marked stations in unworked gridsquares, persisted as a single JSON
    // object: {"OL62":[{"c":call,"t":epochMs}, ...]}. Each grid holds an
    // ordered list (first = pinned/top). v14.1 stored a single object per
    // grid; reads transparently migrate that old shape to a one-element list.
    private val keyMarkedGridStations = "markedGridStations"

    override fun getMarkedGridStations(): Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>> {
        val json = preferences.getString(keyMarkedGridStations, null).orEmpty()
        if (json.isBlank()) return emptyMap()
        return try {
            val root = org.json.JSONObject(json)
            val result = mutableMapOf<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>>()
            for (grid in root.keys()) {
                val value = root.opt(grid)
                val list = when (value) {
                    // v14.2+ shape: JSON array of station objects.
                    is org.json.JSONArray -> (0 until value.length()).mapNotNull { i ->
                        val o = value.optJSONObject(i) ?: return@mapNotNull null
                        val call = o.optString("c").ifBlank { return@mapNotNull null }
                        com.rtbishop.look4sat.core.domain.model.MarkedStation(call = call, epochMs = o.optLong("t"))
                    }
                    // v14.1 legacy shape: a single station object per grid.
                    is org.json.JSONObject -> {
                        val call = value.optString("c").ifBlank { null }
                        if (call == null) emptyList()
                        else listOf(com.rtbishop.look4sat.core.domain.model.MarkedStation(call = call, epochMs = value.optLong("t")))
                    }
                    else -> emptyList()
                }
                if (list.isNotEmpty()) result[grid] = list
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun setMarkedGridStations(stations: Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>>) {
        val root = org.json.JSONObject()
        for ((grid, list) in stations) {
            val array = org.json.JSONArray()
            for (station in list) {
                array.put(
                    org.json.JSONObject()
                        .put("c", station.call)
                        .put("t", station.epochMs)
                )
            }
            root.put(grid, array)
        }
        preferences.edit { putString(keyMarkedGridStations, root.toString()) }
    }

    // LoTW credentials (stored locally on the device only)
    private val keyLoTWCall = "lotwCallsign"
    private val keyLoTWPass = "lotwPassword"

    override val lotwSettings: kotlinx.coroutines.flow.StateFlow<com.rtbishop.look4sat.core.domain.model.LoTWSettings>
        get() = _lotwSettings
    private val _lotwSettings = MutableStateFlow(getLoTWSettings())

    override fun updateLoTWSettings(settings: com.rtbishop.look4sat.core.domain.model.LoTWSettings) {
        preferences.edit {
            putString(keyLoTWCall, settings.callsign.trim().uppercase())
            putString(keyLoTWPass, settings.password)
        }
        _lotwSettings.value = settings.copy(
            callsign = settings.callsign.trim().uppercase(),
            password = settings.password
        )
    }

    private fun getLoTWSettings(): com.rtbishop.look4sat.core.domain.model.LoTWSettings =
        com.rtbishop.look4sat.core.domain.model.LoTWSettings(
            callsign = preferences.getString(keyLoTWCall, null).orEmpty(),
            password = preferences.getString(keyLoTWPass, null).orEmpty()
        )

    // Last successful sync bookkeeping: date ("yyyyMMdd") and callsign. Used to
    // decide incremental (same callsign) vs full (first time / callsign change)
    // report requests and to merge increments into the stored grid data.
    private val keyLastLotwSyncDate = "lotwLastSyncDate"
    private val keyLastLotwSyncCallsign = "lotwLastSyncCallsign"
    private val keyLastLotwQslCursor = "lotwLastQslCursor"

    override fun getLastLotwSyncDate(): String =
        preferences.getString(keyLastLotwSyncDate, null).orEmpty()

    override fun setLastLotwSyncDate(date: String) =
        preferences.edit { putString(keyLastLotwSyncDate, date) }

    override fun getLastLotwQslCursor(): String =
        preferences.getString(keyLastLotwQslCursor, null).orEmpty()

    override fun setLastLotwQslCursor(cursor: String) =
        preferences.edit { putString(keyLastLotwQslCursor, cursor) }

    override fun getLastLotwSyncCallsign(): String =
        preferences.getString(keyLastLotwSyncCallsign, null).orEmpty()

    override fun setLastLotwSyncCallsign(callsign: String) =
        preferences.edit { putString(keyLastLotwSyncCallsign, callsign.trim().uppercase()) }
    //endregion

    //region # Wavelog upload settings
    private val keyWavelogUploadUrl = "wavelogUploadUrl"
    private val keyWavelogUploadApiKey = "wavelogUploadApiKey"
    private val keyWavelogUploadStationId = "wavelogUploadStationId"
    private val keyWavelogUploadStationName = "wavelogUploadStationName"
    private val keyWavelogUploadStationCallsign = "wavelogUploadStationCallsign"
    private val keyWavelogUploadStationGrid = "wavelogUploadStationGrid"

    private val _wavelogUploadSettings = MutableStateFlow(getWavelogUploadSettings())
    override val wavelogUploadSettings: StateFlow<WavelogUploadSettings> = _wavelogUploadSettings

    override fun updateWavelogUploadSettings(settings: WavelogUploadSettings) {
        val trimmed = settings.copy(url = settings.url.trim(), apiKey = settings.apiKey.trim())
        preferences.edit {
            putString(keyWavelogUploadUrl, trimmed.url)
            putString(keyWavelogUploadApiKey, trimmed.apiKey)
            putString(keyWavelogUploadStationId, trimmed.stationId)
            putString(keyWavelogUploadStationName, trimmed.stationName)
            putString(keyWavelogUploadStationCallsign, trimmed.stationCallsign)
            putString(keyWavelogUploadStationGrid, trimmed.stationGrid)
        }
        _wavelogUploadSettings.value = trimmed
    }

    private fun getWavelogUploadSettings(): WavelogUploadSettings = WavelogUploadSettings(
        url = preferences.getString(keyWavelogUploadUrl, null).orEmpty(),
        apiKey = preferences.getString(keyWavelogUploadApiKey, null).orEmpty(),
        stationId = preferences.getString(keyWavelogUploadStationId, null).orEmpty(),
        stationName = preferences.getString(keyWavelogUploadStationName, null).orEmpty(),
        stationCallsign = preferences.getString(keyWavelogUploadStationCallsign, null).orEmpty(),
        stationGrid = preferences.getString(keyWavelogUploadStationGrid, null).orEmpty()
    )
    //endregion

    //region # Wavelog sync bookkeeping
    private val keyWavelogStations = "wavelogStations"
    private val keyWavelogSyncCursors = "wavelogSyncCursors"
    private val keyWavelogSyncUrl = "wavelogSyncUrl"
    private val keyLastWavelogSyncEpochMs = "wavelogLastSyncEpochMs"
    private val keyLastWavelogFullSyncEpochMs = "wavelogLastFullSyncEpochMs"

    override fun getWavelogStations(): List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo> {
        val json = preferences.getString(keyWavelogStations, null).orEmpty()
        if (json.isBlank()) return emptyList()
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo(
                    id = id,
                    name = o.optString("name"),
                    callsign = o.optString("call"),
                    grid = o.optString("grid"),
                    active = o.optBoolean("active", true)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun setWavelogStations(stations: List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo>) {
        val array = org.json.JSONArray()
        stations.forEach { station ->
            array.put(
                org.json.JSONObject()
                    .put("id", station.id)
                    .put("name", station.name)
                    .put("call", station.callsign)
                    .put("grid", station.grid)
                    .put("active", station.active)
            )
        }
        preferences.edit { putString(keyWavelogStations, array.toString()) }
    }

    override fun getWavelogSyncCursors(): Map<String, Long> {
        val json = preferences.getString(keyWavelogSyncCursors, null).orEmpty()
        if (json.isBlank()) return emptyMap()
        return try {
            val root = org.json.JSONObject(json)
            root.keys().asSequence().associateWith { key -> root.optLong(key, 0L) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun setWavelogSyncCursors(cursors: Map<String, Long>) {
        val root = org.json.JSONObject()
        cursors.forEach { (stationId, cursor) -> root.put(stationId, cursor) }
        preferences.edit { putString(keyWavelogSyncCursors, root.toString()) }
    }

    override fun getWavelogSyncUrl(): String =
        preferences.getString(keyWavelogSyncUrl, null).orEmpty()

    override fun setWavelogSyncUrl(url: String) =
        preferences.edit { putString(keyWavelogSyncUrl, url) }

    override fun getLastWavelogSyncEpochMs(): Long =
        preferences.getLong(keyLastWavelogSyncEpochMs, 0L)

    override fun setLastWavelogSyncEpochMs(value: Long) =
        preferences.edit { putLong(keyLastWavelogSyncEpochMs, value) }

    override fun getLastWavelogFullSyncEpochMs(): Long =
        preferences.getLong(keyLastWavelogFullSyncEpochMs, 0L)

    override fun setLastWavelogFullSyncEpochMs(value: Long) =
        preferences.edit { putLong(keyLastWavelogFullSyncEpochMs, value) }
    //endregion

    //region # Transceivers settings
    private val _passesSettings = MutableStateFlow(getPassesSettings())
    override val passesSettings: StateFlow<PassesSettings> = _passesSettings

    override fun setPassesSettings(settings: PassesSettings) = preferences.edit {
        putBoolean(keyFilterShowDeepSpace, settings.showDeepSpace)
        putInt(keyFilterHoursAhead, settings.hoursAhead)
        putInt(keyFilterHoursBefore, settings.hoursBefore)
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
        val hoursBefore = preferences.getInt(keyFilterHoursBefore, 0).coerceIn(0, 240)
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
            selectedModes,
            hoursBefore
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

    override fun getCurrentGrid(): String? {
        val station = _stationPosition.value
        val now = System.currentTimeMillis()
        // Only trust a location fix that is both recent (≤ 24 h) and newer than the stored
        // station, so a stale fix can never override a position the operator just set.
        val fix = lastKnownGridFix()?.takeIf { now - it.second <= 24 * 3_600_000L }
        val chosen = if (fix != null && fix.second >= station.timestamp) fix.first else station.qthLocator
        return chosen.takeIf(String::isNotBlank)
    }

    /** Last known GPS/NETWORK fix converted to a locator; read-only, null without permission. */
    private fun lastKnownGridFix(): Pair<String, Long>? {
        return try {
            val provider = when {
                LocationManagerCompat.hasProvider(locationManager, providerGps) -> providerGps
                LocationManagerCompat.hasProvider(locationManager, providerNet) -> providerNet
                else -> return null
            }
            val location = locationManager.getLastKnownLocation(provider) ?: return null
            val locator = positionToQth(location.latitude, location.longitude) ?: return null
            locator to location.time
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
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
        // "All" is a real TLE source type (CelesTrak active group) whose ids
        // must be persisted like any other type — skipping it made the "All"
        // filter resolve to an empty set and show nothing.
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
            val new = transform(current).let {
                it.copy(
                    mapSource = MapSource.normalize(it.mapSource),
                    tiandituKey = it.tiandituKey.trim()
                )
            }
            preferences.edit {
                putBoolean(keyStateOfAutoUpdate, new.stateOfAutoUpdate)
                putBoolean(keyStateOfAutoLotwSync, new.stateOfAutoLotwSync)
                putBoolean(keyStateOfSensors, new.stateOfSensors)
                putFloat(keyCompassOffsetDegrees, new.compassOffsetDegrees.coerceIn(-180f, 180f))
                putBoolean(keyStateOfSweep, new.stateOfSweep)
                putBoolean(keyStateOfUtc, new.stateOfUtc)
                putBoolean(keyStateOfLightTheme, new.stateOfLightTheme)
                putBoolean(keyStateOfNightMode, new.stateOfNightMode)
                putBoolean(keyStateOfMapGrid, new.stateOfMapGrid)
                putBoolean(keyStateOfMapFirstCall, new.stateOfMapFirstCall)
                putBoolean(keyShouldSeeWarning, new.shouldSeeWarning)
                putBoolean(keyShouldSeeWhatsNew, new.shouldSeeWhatsNew)
                putString(keySstvMode, new.sstvMode)
                putLong(keyLowElevation, new.lowElevation.toRawBits())
                putLong(keyHighElevation, new.highElevation.toRawBits())
                putString(keyMapSource, new.mapSource)
                putString(keyTiandituKey, new.tiandituKey)
            }
            new
        }
    }

    private fun getOtherSettings(): OtherSettings = OtherSettings(
        stateOfAutoUpdate = preferences.getBoolean(keyStateOfAutoUpdate, true),
        stateOfAutoLotwSync = preferences.getBoolean(keyStateOfAutoLotwSync, true),
        stateOfSensors = preferences.getBoolean(keyStateOfSensors, true),
        compassOffsetDegrees = preferences.getFloat(keyCompassOffsetDegrees, 0f).coerceIn(-180f, 180f),
        stateOfSweep = preferences.getBoolean(keyStateOfSweep, true),
        stateOfUtc = preferences.getBoolean(keyStateOfUtc, false),
        stateOfLightTheme = preferences.getBoolean(keyStateOfLightTheme, false),
        stateOfNightMode = preferences.getBoolean(keyStateOfNightMode, false),
        stateOfMapGrid = preferences.getBoolean(keyStateOfMapGrid, false),
        stateOfMapFirstCall = preferences.getBoolean(keyStateOfMapFirstCall, false),
        shouldSeeWarning = preferences.getBoolean(keyShouldSeeWarning, true),
        shouldSeeWhatsNew = preferences.getBoolean(keyShouldSeeWhatsNew, true),
        sstvMode = preferences.getString(keySstvMode, null) ?: "Auto",
        lowElevation = Double.fromBits(preferences.getLong(keyLowElevation, 15.0.toRawBits())),
        highElevation = Double.fromBits(preferences.getLong(keyHighElevation, 45.0.toRawBits())),
        mapSource = MapSource.normalize(preferences.getString(keyMapSource, null).orEmpty()),
        tiandituKey = preferences.getString(keyTiandituKey, null).orEmpty().trim()
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
            putBoolean(keyAutoTleSourceMigration, true)
        }
        _dataSourcesSettings.value = normalized
    }

    private fun getDataSourcesSettings(): DataSourcesSettings = DataSourcesSettings(
        satelliteUrls = getDataSourceUrls(
            key = keySatelliteUrls,
            defaultUrls = Sources.satelliteDataUrls.values.filter { it.isNotBlank() },
            legacyEnabledKey = keyUseCustomTle,
            legacyUrlKey = keyTleUrl
        ).migrateSatnogsTleSource().migrateAutoTleSources(),
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

    private fun List<String>.migrateAutoTleSources(): List<String> {
        if (preferences.getBoolean(keyAutoTleSourceMigration, false)) return this
        val autoTleUrls = listOfNotNull(
            Sources.satelliteDataUrls["BI4PYM AutoTLE (GitHub)"],
            Sources.satelliteDataUrls["BI4PYM AutoTLE (Mirror)"]
        ).filter { it.isNotBlank() }
        val missingUrls = autoTleUrls.filterNot { containsSourceUrl(it) }
        if (missingUrls.isEmpty()) {
            preferences.edit { putBoolean(keyAutoTleSourceMigration, true) }
            return this
        }

        val migrated = this + missingUrls
        val enabled = alignFlags(migrated, readEnabledFlags(keySatelliteEnabled))
        preferences.edit {
            putString(keySatelliteUrls, migrated.joinToString(separatorUrl))
            putString(keySatelliteEnabled, enabled.joinToString(separatorComma))
            putBoolean(keyAutoTleSourceMigration, true)
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
    private val keyRadioCatTransport = "radioCatTransport"
    private val keyRadioDuplexMode = "radioDuplexMode"
    private val keyRadioCivAddress = "radioCivAddress"
    private val keyRadioTcpProtocol = "radioTcpProtocol"
    private val keyRadioDialSettle = "radioDialSettleMillis"
    private val keyRadioLinearDeadband = "radioLinearDialDeadbandHz"
    private val keyRadioFmDeadband = "radioFmDialDeadbandHz"
    private val keyRadioSharedBusDelay = "radioSharedBusCommandDelayMillis"
    private val keyRadioL4sStep = "radioL4sDopplerStepHz"
    private val keyRadioCorrectionStep = "radioL4sCorrectionEditStepHz"
    private val keyN76BluetoothAudio = "radioN76BluetoothAudio"
    private val keyN76Options = "radioN76Options"

    override fun getRemoteCwPresets(): List<String> = readRemoteCwPresets(preferences)

    override fun updateRemoteCwPreset(index: Int, text: String) {
        writeRemoteCwPreset(preferences,index,text)
    }
    private val _radioControlSettings = MutableStateFlow(getRadioControlSettings())
    override val radioControlSettings: StateFlow<RadioControlSettings> = _radioControlSettings

    override fun updateRadioControlSettings(settings: RadioControlSettings) {
        val supportedBaudRates = supportedRadioBaudRates(settings.radioModel)
        val normalized = settings.copy(
            baudRate = settings.baudRate.takeIf { it in supportedBaudRates } ?: supportedBaudRates.first(),
            dialSettleMillis = settings.dialSettleMillis.coerceIn(0L, 10_000L),
            linearDialDeadbandHz = settings.linearDialDeadbandHz.coerceIn(1L, 10_000L),
            fmDialDeadbandHz = settings.fmDialDeadbandHz.coerceIn(10L, 100_000L),
            sharedBusCommandDelayMillis = settings.sharedBusCommandDelayMillis.coerceIn(0L, 2_000L),
            l4sDopplerStepHz = com.rtbishop.look4sat.core.domain.repository.normalizeL4sStep(settings.l4sDopplerStepHz),
            l4sCorrectionEditStepHz = settings.l4sCorrectionEditStepHz.coerceIn(10,1000)/10*10,
            n76Options = settings.n76Options.normalized()
        )
        preferences.edit {
            putBoolean(keyRadioControlEnabled, normalized.enabled)
            putString(keyRadioModel, normalized.radioModel)
            putString(keyTxRadioAddress, normalized.txRadioAddress)
            putString(keyRxRadioAddress, normalized.rxRadioAddress)
            putString(keyTxRadioName, normalized.txRadioName)
            putString(keyRxRadioName, normalized.rxRadioName)
            putInt(keyRadioBaudRate, normalized.baudRate)
            putBoolean(keyRadioSplitMode, normalized.splitMode)
            putString(keyRadioCatTransport, normalized.catTransport)
            putString(keyRadioDuplexMode, normalized.duplexMode)
            putInt(keyRadioCivAddress, normalized.civAddress ?: -1)
            putString(keyRadioTcpProtocol, normalized.tcpProtocol)
            putLong(keyRadioDialSettle, normalized.dialSettleMillis)
            putLong(keyRadioLinearDeadband, normalized.linearDialDeadbandHz)
            putLong(keyRadioFmDeadband, normalized.fmDialDeadbandHz)
            putLong(keyRadioSharedBusDelay, normalized.sharedBusCommandDelayMillis)
            putLong(keyRadioL4sStep, normalized.l4sDopplerStepHz)
            putLong(keyRadioCorrectionStep, normalized.l4sCorrectionEditStepHz)
            putBoolean(keyN76BluetoothAudio, normalized.n76BluetoothAudio)
            putString(keyN76Options, normalized.n76Options.toPreferenceJson())
        }
        _radioControlSettings.value = normalized
    }

    private fun getRadioControlSettings(): RadioControlSettings {
        val model = preferences.getString(keyRadioModel, null) ?: RadioControlSettings.MODEL_YAESU_FT817
        val supportedBaudRates = supportedRadioBaudRates(model)
        return RadioControlSettings(
            enabled = preferences.getBoolean(keyRadioControlEnabled, false),
            radioModel = model,
            txRadioAddress = preferences.getString(keyTxRadioAddress, null) ?: "",
            rxRadioAddress = preferences.getString(keyRxRadioAddress, null) ?: "",
            txRadioName = preferences.getString(keyTxRadioName, null) ?: "TX Radio",
            rxRadioName = preferences.getString(keyRxRadioName, null) ?: "RX Radio",
            baudRate = preferences.getInt(keyRadioBaudRate, 4800)
                .takeIf { it in supportedBaudRates } ?: supportedBaudRates.first(),
            splitMode = preferences.getBoolean(keyRadioSplitMode, false),
            catTransport = preferences.getString(keyRadioCatTransport, null)
                ?: RadioControlSettings.TRANSPORT_BLUETOOTH,
            duplexMode = preferences.getString(keyRadioDuplexMode, null)
                ?.takeIf {
                    it == RadioControlSettings.DUPLEX_MODE_SATELLITE ||
                        it == RadioControlSettings.DUPLEX_MODE_SPLIT
                }
                ?: RadioControlSettings.DUPLEX_MODE_SPLIT,
            civAddress = preferences.getInt(keyRadioCivAddress, -1).takeIf { it in 0..0xFF },
            tcpProtocol = preferences.getString(keyRadioTcpProtocol, null)
                ?.takeIf { it in RadioControlSettings.SUPPORTED_TCP_PROTOCOLS }
                ?: RadioControlSettings.TCP_PROTOCOL_RAW_CAT,
            dialSettleMillis = preferences.getLong(keyRadioDialSettle, 1_500L).coerceIn(0L, 10_000L),
            linearDialDeadbandHz = preferences.getLong(keyRadioLinearDeadband, 20L).coerceIn(1L, 10_000L),
            fmDialDeadbandHz = preferences.getLong(keyRadioFmDeadband, 200L).coerceIn(10L, 100_000L),
            sharedBusCommandDelayMillis = preferences.getLong(keyRadioSharedBusDelay, 0L).coerceIn(0L, 2_000L),
            l4sDopplerStepHz = com.rtbishop.look4sat.core.domain.repository.normalizeL4sStep(preferences.getLong(keyRadioL4sStep,250L)),
            l4sCorrectionEditStepHz = preferences.getLong(keyRadioCorrectionStep,10).coerceIn(10,1000)/10*10,
            n76BluetoothAudio = preferences.getBoolean(keyN76BluetoothAudio, false),
            n76Options = n76OptionsFromJson(preferences.getString(keyN76Options, null))
        )
    }
    //endregion

    //region # Per-satellite calculator offset settings
    private val keySatelliteOffsets = "satelliteOffsets"
    override fun getRadioBaseFrequencies(transponderUuid: String) = readRadioBaseFrequencies(preferences, transponderUuid)

    override fun setRadioBaseFrequencies(transponderUuid: String, bases: com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies) =
        writeRadioBaseFrequencies(preferences, transponderUuid, bases)
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

    //region # Per-satellite logbook mode preset settings
    private val keySatelliteModes = "satelliteModes"

    override fun getSatelliteMode(catnum: Int): String {
        val json = preferences.getString(keySatelliteModes, "{}") ?: "{}"
        return try {
            JSONObject(json).optString(catnum.toString(), "")
        } catch (_: Exception) {
            ""
        }
    }

    override fun setSatelliteMode(catnum: Int, mode: String) {
        val json = preferences.getString(keySatelliteModes, "{}") ?: "{}"
        val updated = try {
            val obj = JSONObject(json)
            if (mode.isBlank()) obj.remove(catnum.toString()) else obj.put(catnum.toString(), mode.uppercase())
            obj.toString()
        } catch (_: Exception) {
            if (mode.isBlank()) "{}" else """{"$catnum": "${mode.uppercase()}"}"""
        }
        preferences.edit { putString(keySatelliteModes, updated) }
    }
    //endregion

    //region # Per-satellite favorite transponders
    private val keyFavoriteTransponders = "favoriteTransponders"

    override fun getFavoriteTransponders(catnum: Int): List<String> {
        val json = preferences.getString(keyFavoriteTransponders, "{}") ?: "{}"
        return try {
            val array = JSONObject(json).optJSONArray(catnum.toString()) ?: return emptyList()
            (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun setFavoriteTransponders(catnum: Int, uuids: List<String>) {
        val json = preferences.getString(keyFavoriteTransponders, "{}") ?: "{}"
        val obj = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
        val normalized = uuids.filter { it.isNotBlank() }.distinct()
        if (normalized.isEmpty()) {
            obj.remove(catnum.toString())
        } else {
            obj.put(catnum.toString(), org.json.JSONArray(normalized))
        }
        preferences.edit { putString(keyFavoriteTransponders, obj.toString()) }
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
