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
    val updateTimestamp: Long,
    val contentVersion: Long = 0L
)

data class PassesSettings(
    val showDeepSpace: Boolean = true,
    val hoursAhead: Int,
    val minElevation: Double,
    val aosStartMinute: Int = 0,
    val aosEndMinute: Int = 23 * 60 + 59,
    val invertAosTimeWindow: Boolean = false,
    val selectedModes: List<String>,
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

data class Ft4Settings(
    val operatorCallsign: String = "",
    val decodeEnabled: Boolean = true,
    val decodeDepth: Int = 3,
    val ntpSynchronizationEnabled: Boolean = false,
    val gnssSynchronizationEnabled: Boolean = false,
    val audioInputDeviceKey: String = ""
)

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
    /** Use one Icom radio with VFO-A/B split or supported satellite MAIN/SUB control. */
    val splitMode: Boolean = false,
    val catTransport: String = TRANSPORT_BLUETOOTH,
    /** Dedicated satellite MAIN/SUB when supported; ordinary VFO-A/B split otherwise. */
    val duplexMode: String = DUPLEX_MODE_SPLIT,
    /** Optional override for the radio's default CI-V address (0x00..0xFF). */
    val civAddress: Int? = null,
    /** TCP payload protocol: direct binary CAT or Hamlib rigctld text commands. */
    val tcpProtocol: String = TCP_PROTOCOL_RAW_CAT,
    /** Time that a manually moved dial must remain stable before it becomes the new reference. */
    val dialSettleMillis: Long = 1_500L,
    /** Manual-tuning detection threshold for linear transponders. */
    val linearDialDeadbandHz: Long = 20L,
    /** Manual-tuning detection threshold for FM transponders. */
    val fmDialDeadbandHz: Long = 200L,
    /** Optional spacing between commands sharing one physical CAT connection. */
    val sharedBusCommandDelayMillis: Long = 0L,
    /** L4S tracking/control quantum, distinct from the radio's 10 Hz hardware resolution. */
    val l4sDopplerStepHz: Long = 250L,
    val l4sCorrectionEditStepHz: Long = 10L
) {
    companion object {
        const val MODEL_YAESU_FT817 = "Yaesu FT-817/818"
        const val MODEL_YAESU_FT857 = "Yaesu FT-857/897"
        const val MODEL_YAESU_FTX1 = "Yaesu FTX-1"
        const val MODEL_ICOM_IC705 = "Icom IC-705"
        const val MODEL_ICOM_IC9700 = "Icom IC-9700"
        const val MODEL_ICOM_IC910 = "Icom IC-910/D/H"
        const val MODEL_ICOM_IC820 = "Icom IC-820"
        const val MODEL_QUANSHENG_UVK1 = "Quansheng UV-K1 (L4S)"
        const val MODEL_QUANSHENG_UVK5V3 = "Quansheng UV-K5 V3 (L4S)"

        // Kept for the frozen basic-edition CAT settings UI; the jsuenhanced edition reads
        // baud rates from its own transport descriptors.
        val BAUD_RATES_YAESU = listOf(4800, 9600, 38400)
        val BAUD_RATES_ICOM = listOf(4800, 9600, 19200, 38400, 57600, 115200)
        const val TRANSPORT_BLUETOOTH = "BLUETOOTH"
        const val TRANSPORT_USB = "USB"
        const val TRANSPORT_TCP = "TCP"
        /** Audio-driven transmit; FT4 does not issue CAT frequency or PTT commands. */
        const val TRANSPORT_VOX = "VOX"
        const val TCP_PROTOCOL_RAW_CAT = "RAW_CAT"
        const val TCP_PROTOCOL_HAMLIB = "HAMLIB_RIGCTLD"
        const val DUPLEX_MODE_SPLIT = "SPLIT"
        const val DUPLEX_MODE_SATELLITE = "SATELLITE"

        val SUPPORTED_RADIOS = RadioModelCatalog.supportedModels
        val SUPPORTED_TRANSPORTS = listOf(TRANSPORT_BLUETOOTH, TRANSPORT_USB, TRANSPORT_TCP, TRANSPORT_VOX)
        val SUPPORTED_TCP_PROTOCOLS = listOf(TCP_PROTOCOL_RAW_CAT, TCP_PROTOCOL_HAMLIB)

    }
}

fun supportedRadioBaudRates(model: String): List<Int> = radioModelDescriptor(model).baudRates

data class RadioTcpEndpoint(val host: String, val port: Int)

/** Parses host:port, requiring brackets around IPv6 so USB selectors cannot be mistaken for hosts. */
fun parseRadioTcpEndpoint(value: String): RadioTcpEndpoint? {
    val input = value.trim()
    if (input.isEmpty()) return null
    val host: String
    val portText: String
    if (input.startsWith('[')) {
        val closingBracket = input.indexOf(']')
        if (
            closingBracket <= 1 ||
            closingBracket + 1 >= input.length ||
            input[closingBracket + 1] != ':' ||
            input.indexOf('[', startIndex = 1) >= 0 ||
            input.indexOf(']', startIndex = closingBracket + 1) >= 0
        ) return null
        host = input.substring(1, closingBracket)
        portText = input.substring(closingBracket + 2)
    } else {
        if (input.count { it == ':' } != 1) return null
        val separator = input.indexOf(':')
        if (separator <= 0 || separator == input.lastIndex) return null
        host = input.substring(0, separator).trim()
        portText = input.substring(separator + 1)
    }
    val port = portText.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
    if (host.isBlank() || host.any(Char::isWhitespace)) return null
    return RadioTcpEndpoint(host, port)
}
