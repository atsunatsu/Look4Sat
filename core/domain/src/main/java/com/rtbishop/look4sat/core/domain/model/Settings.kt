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
package com.rtbishop.look4sat.core.domain.model

data class DatabaseState(
    val numberOfRadios: Int,
    val numberOfSatellites: Int,
    val updateTimestamp: Long
)

data class PassesSettings(
    val showDeepSpace: Boolean = true,
    val hoursAhead: Int,
    val minElevation: Double,
    val aosStartMinute: Int = 0,
    val aosEndMinute: Int = 23 * 60 + 59,
    val invertAosTimeWindow: Boolean = false,
    val selectedModes: List<String>,
    /** 历史过境回看窗口(小时): 过境列表从 now-hoursBefore 开始计算. */
    val hoursBefore: Int = 0
)

data class RCSettings(
    val rotatorState: Boolean,
    val rotatorAddress: String,
    val rotatorPort: String,
    val rotatorFormat: String,
    val frequencyState: Boolean,
    val frequencyAddress: String,
    val frequencyPort: String,
    val frequencyFormat: String,
    val frequencyOffsetHz: Long = 0L,
    val bluetoothRotatorState: Boolean,
    val bluetoothRotatorFormat: String,
    val bluetoothRotatorName: String,
    val bluetoothRotatorAddress: String,
    val bluetoothFrequencyState: Boolean,
    val bluetoothFrequencyFormat: String,
    val bluetoothFrequencyAddress: String
)

data class OtherSettings(
    val stateOfAutoUpdate: Boolean,
    val stateOfSensors: Boolean,
    val stateOfSweep: Boolean,
    val stateOfUtc: Boolean,
    val stateOfLightTheme: Boolean,
    val stateOfNightMode: Boolean = false,
    val stateOfMapGrid: Boolean = false,
    /** Grid mode: label worked (green) cells with the FIRST callsign worked
     *  in that grid instead of the Maidenhead code; non-worked cells get no
     *  label at all. */
    val stateOfMapFirstCall: Boolean = false,
    val shouldSeeWarning: Boolean,
    val shouldSeeWhatsNew: Boolean,
    val sstvMode: String = "Auto",
    val lowElevation: Double = 15.0,
    val highElevation: Double = 45.0,
    /** Independent auto-sync toggle for LoTW confirmed grids (separate from the
     *  ephemeris auto-update; only effective after at least one manual sync). */
    val stateOfAutoLotwSync: Boolean = true,
    /** 指南针手动航向偏置(度, -180..180): 雷达方位 = 传感器方位 + 磁偏角 + 该偏置. */
    val compassOffsetDegrees: Float = 0f,
    val mapSource: String = MapSource.OSM,
    val tiandituKey: String = ""
)

object MapSource {
    const val OSM = "osm"
    const val TIANDITU_VECTOR = "tianditu_vector"
    const val TIANDITU_IMAGE = "tianditu_image"
    private const val LEGACY_TIANDITU = "tianditu"

    fun normalize(source: String): String = when (source) {
        OSM, TIANDITU_VECTOR, TIANDITU_IMAGE -> source
        LEGACY_TIANDITU -> TIANDITU_VECTOR
        else -> OSM
    }
}
data class DataSourcesSettings(
    val satelliteUrls: List<String>,
    val transceiversUrls: List<String>,
    val satelliteEnabled: List<Boolean> = emptyList(),
    val transceiversEnabled: List<Boolean> = emptyList()
) {
    fun isSatelliteEnabled(index: Int): Boolean = satelliteEnabled.getOrElse(index) { true }
    fun isTransceiverEnabled(index: Int): Boolean = transceiversEnabled.getOrElse(index) { true }
}

/** Wavelog configuration — QSO upload both ways; one of LoTW or Wavelog (mutually exclusive). */
data class WavelogUploadSettings(
    val url: String = "",
    val apiKey: String = "",
    val stationId: String = "",
    val stationName: String = "",
    val stationCallsign: String = "",
    val stationGrid: String = ""
) {
    val isConfigured: Boolean get() = url.isNotBlank() && apiKey.isNotBlank()
    val isReady: Boolean get() = isConfigured && stationId.isNotBlank()
}

data class LoTWSettings(
    val callsign: String = "",
    val password: String = ""
) {
    val isConfigured: Boolean get() = callsign.isNotBlank() && password.isNotBlank()
}

data class RadioControlSettings(
    val enabled: Boolean,
    val radioModel: String,
    val txRadioAddress: String,
    val rxRadioAddress: String,
    val txRadioName: String,
    val rxRadioName: String,
    val baudRate: Int,
    /** IC-705 only: use single-radio split-VFO mode instead of two radios. */
    val splitMode: Boolean = false,
    val txTransport: String = "Bluetooth SPP",
    val rxTransport: String = "Bluetooth SPP"
) {
    companion object {
        const val TRANSPORT_SPP = "Bluetooth SPP"
        const val TRANSPORT_BLE = "Bluetooth BLE UART"
        const val TRANSPORT_USB = "USB serial (OTG)"
        val TRANSPORT_OPTIONS = listOf(TRANSPORT_SPP, TRANSPORT_BLE, TRANSPORT_USB)

        const val MODEL_YAESU_FT817   = "Yaesu FT-817/818"
        const val MODEL_YAESU_FT857   = "Yaesu FT-857/897"
        const val MODEL_ICOM_IC705    = "Icom IC-705"

        val SUPPORTED_RADIOS = listOf(MODEL_YAESU_FT817, MODEL_YAESU_FT857, MODEL_ICOM_IC705)

        /** Baud rates available for Yaesu radios. */
        val BAUD_RATES_YAESU = listOf(4800, 9600, 38400)
        /** Baud rates available for Icom IC-705 (higher speeds supported via CI-V USB/BT). */
        val BAUD_RATES_ICOM  = listOf(4800, 9600, 19200, 38400, 57600, 115200)
    }
}
