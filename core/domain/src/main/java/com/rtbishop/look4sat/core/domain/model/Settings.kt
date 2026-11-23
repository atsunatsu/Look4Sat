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
    val tiandituKey: String = "",
    /** Persisted APK update source, independent from ephemeris data sources. */
    val updateSource: UpdateSource = UpdateSource.AUTO
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
    val stationGrid: String = "",
    val confirmationToken: String = ""
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

enum class UsbPttPort { CAT, SEPARATE }
enum class UsbPttLine { RTS, DTR }

data class UsbPttSettings(
    val enabled: Boolean = false,
    val port: UsbPttPort = UsbPttPort.SEPARATE,
    val address: String = "",
    val name: String = "",
    val line: UsbPttLine = UsbPttLine.RTS,
    val activeHigh: Boolean = true
)

fun RadioControlSettings.usbPttConfigurationError(): String? {
    if (!usbPtt.enabled) return null
    if (usbPtt.port == UsbPttPort.CAT) {
        if (catTransport != RadioControlSettings.TRANSPORT_USB) return "CAT_NOT_USB"
        if (usbPtt.line == UsbPttLine.DTR && radioModel in setOf(RadioControlSettings.MODEL_QUANSHENG_UVK1, RadioControlSettings.MODEL_QUANSHENG_UVK5V3)) return "HANDSHAKE"
        if (if (usbPtt.line == UsbPttLine.RTS) catRts else catDtr) return "LINE_RESERVED"
    } else {
        if (usbPtt.address.isBlank()) return "PORT_REQUIRED"
        if (catTransport == RadioControlSettings.TRANSPORT_USB &&
            (usbSerialPortsOverlap(usbPtt.address, txRadioAddress) ||
                !splitMode && usbSerialPortsOverlap(usbPtt.address, rxRadioAddress))) return "PORT_IN_USE"
    }
    return null
}

/** Interface identity keeps separate ports on one multi-port adapter usable. */
fun usbSerialPortsOverlap(first: String, second: String): Boolean {
    val a = first.split(':')
    val b = second.split(':')
    if (a.size < 3 || b.size < 3 || a[0] != b[0]) return false
    return a.subList(1, 3).any { it in b.subList(1, 3) }
}

data class RadioControlSettings(
    val enabled: Boolean,
    val radioModel: String,
    val txRadioAddress: String,
    val rxRadioAddress: String,
    val txRadioName: String,
    val rxRadioName: String,
    val baudRate: Int,
    /** One radio: automatic split where supported, otherwise a manually preset half-duplex radio. */
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
    val l4sDopplerStepHz: Long = 100L,
    val l4sCorrectionEditStepHz: Long = 10L,
    val n76BluetoothAudio: Boolean = false,
    val n76Options: N76ControlOptions = N76ControlOptions(),
    /** Independently configurable physical USB CAT control lines (not PTT). */
    val catRts: Boolean = false,
    val catDtr: Boolean = false,
    val usbPtt: UsbPttSettings = UsbPttSettings()
) {
    companion object {
        const val MODEL_YAESU_FT847 = "Yaesu FT-847"
        const val MODEL_YAESU_FT991 = "Yaesu FT-991/991A"
        const val MODEL_ICOM_IC821 = "Icom IC-821H"
        const val MODEL_ICOM_IC9100 = "Icom IC-9100"
        const val MODEL_ICOM_IC7300 = "Icom IC-7300"
        const val MODEL_ICOM_IC905 = "Icom IC-905"
        const val MODEL_ICOM_IC7100 = "Icom IC-7100"
        const val MODEL_ICOM_IC706 = "Icom IC-706"
        const val MODEL_ICOM_IC706MKII = "Icom IC-706MKII"
        const val MODEL_ICOM_IC706MKIIG = "Icom IC-706MKIIG"
        const val MODEL_SDR_RIGCTLD = "SDR (rigctld)"
        const val MODEL_KENWOOD_TH_D74 = "Kenwood TH-D74"
        const val MODEL_KENWOOD_TH_D75 = "Kenwood TH-D75"
        const val MODEL_KENWOOD_TS_2000 = "Kenwood TS-2000"
        const val MODEL_KENWOOD_TS_790 = "Kenwood TS-790A/E"
        const val MODEL_KENWOOD_TS_990 = "Kenwood TS-990S"
        const val MODEL_KENWOOD_TS_890 = "Kenwood TS-890S"
        const val MODEL_KENWOOD_TS_590 = "Kenwood TS-590S"
        const val MODEL_KENWOOD_TS_590SG = "Kenwood TS-590SG"
        const val MODEL_FLEX_SMARTSDR = "FlexRadio SmartSDR"
        const val MODEL_YAESU_FT817 = "Yaesu FT-817/818"
        const val MODEL_YAESU_FT857 = "Yaesu FT-857/897"
        const val MODEL_YAESU_FTX1 = "Yaesu FTX-1"
        const val MODEL_ICOM_IC705 = "Icom IC-705"
        const val MODEL_ICOM_IC9700 = "Icom IC-9700"
        const val MODEL_ICOM_IC910 = "Icom IC-910/D/H"
        const val MODEL_ICOM_IC820 = "Icom IC-820"
        const val MODEL_QUANSHENG_UVK1 = "Quansheng UV-K1 (L4S)"
        const val MODEL_QUANSHENG_UVK5V3 = "Quansheng UV-K5 V3 (L4S)"
        const val MODEL_VERO_N76 = "Vero VR-N76"
        const val TRANSPORT_BLUETOOTH = "BLUETOOTH"
        const val TRANSPORT_USB = "USB"
        const val TRANSPORT_TCP = "TCP"
        /** Audio-driven operation without CAT frequency or PTT commands. */
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

data class RadioLanLogin(val username: String, val password: String, val administrator: Boolean = false) {
    override fun toString(): String = "RadioLanLogin(redacted)"
}

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
