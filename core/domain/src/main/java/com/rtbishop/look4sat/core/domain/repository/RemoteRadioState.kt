package com.rtbishop.look4sat.core.domain.repository

enum class FrequencyPairResult { UNSUPPORTED, APPLIED, HELD, FAILED }
enum class RadioTransmitOwner { NONE, VOICE, CW }
enum class CwTransmitState { IDLE, QUEUED, SENDING, DONE, CANCELLED, FAILED }

data class RemoteRadioCapabilities(
    val frequencyStepHz: Long,
    val maxCwText: Int,
    val minimumWpm: Int,
    val maximumWpm: Int,
    val watchdogMillis: Int,
    val minimumRxHz: Long,
    val maximumRxHz: Long,
    val rxModeMask: Int,
    val txModeMask: Int,
    val atomicPair: Boolean,
    val voicePtt: Boolean,
    val cwText: Boolean,
    val ctcss: Boolean,
    val liveFmRetune: Boolean,
    val liveCwRetune: Boolean,
    val trackingDisplay: Boolean = false,
    val nativeSettings: Boolean = false,
    val satelliteProfiles: Boolean = false,
    val transactionalProfileStore: Boolean = false,
    val cwOptions: Boolean = false,
    val handsetMacros: Boolean = false,
    val nativeDiagnostics: Boolean = false,
    val passClock: Boolean = false
)

data class RadioTrackingDisplay(val satelliteName: String, val azimuthDegrees: Double, val elevationDegrees: Double,
    val contextKey: String? = null, val distanceKm: Double = 0.0, val altitudeKm: Double = 0.0,
    val secondsUntilAos: Long = 0, val secondsUntilLos: Long = 0, val passPhase: Int = 0)

data class NativeCwOptions(val wpm: Int = 20, val pitch: Int = 70, val volume: Int = 3,
    val hang: Int = 30, val keyer: Int = 1, val spacing: Int = 0, val modified: Boolean = false) {
    fun valid() = wpm in 10..45 && pitch in 45..120 && pitch % 5 == 0 && volume in 0..6 &&
        hang in 1..200 && keyer in 0..3 && (spacing == 0 || spacing in 5..44)
}

data class HandsetMacroStatus(val phase: Int, val error: Int, val generation: Long, val readOnly: Boolean,
    val slot: Int = 255, val text: String = "") {
    val busy get() = phase in 1..7
    val waitingForConfirmation get() = phase == 2
}

data class NativeRadioDiagnostics(val stopReason: Int, val lastRejection: Int, val rssi: Int,
    val requests: Long, val rejected: Long, val aborts: Long, val usbLosses: Long, val replyDrops: Long,
    val maximumGapMillis: Long, val crcErrors: Long, val lengthErrors: Long) {
    fun summary() = "stop=$stopReason lastRejection=$lastRejection rssi=$rssi requests=$requests rejected=$rejected " +
        "aborts=$aborts usbLosses=$usbLosses replyDrops=$replyDrops maxGapMs=$maximumGapMillis " +
        "crcErrors=$crcErrors lengthErrors=$lengthErrors"
}

fun radioContextKey(uuid: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(uuid.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it.toInt() and 255) }

fun radioSatelliteProfileKey(name: String, transponderUuid: String): String =
    radioContextKey("${name.length}:$name:$transponderUuid")

data class RadioBaseFrequencies(val rxHz: Long, val txHz: Long?)
data class RawTrackingTargets(val rxHz: Long, val txHz: Long?)
data class AppliedRadioFrequencies(val rxHz: Long, val txHz: Long?)

fun normalizeL4sStep(stepHz: Long): Long = stepHz.coerceIn(10L,10_000L)/10*10

fun l4sTrackingTarget(hz: Long, correctionHz: Long, stepHz: Long): Long {
    require(hz in 1..0xfffffffaL) { "L4S frequency outside unsigned 32-bit Hz range" }
    val step=normalizeL4sStep(stepHz)
    val corrected=Math.addExact((hz+step/2)/step*step,correctionHz)
    val target=(corrected+5)/10*10
    require(target in 1..0xfffffffaL) { "L4S quantized frequency outside unsigned 32-bit Hz range" }
    return target
}

data class RemoteRadioState(
    val connected: Boolean = false,
    val firmware: String = "",
    val capabilities: RemoteRadioCapabilities? = null,
    val session: Long = 0,
    val rxFrequencyHz: Long? = null,
    val txFrequencyHz: Long? = null,
    val appliedRevision: Long = 0,
    val pendingRevision: Long = 0,
    val rxMode: String = "FM",
    val txMode: String = "FM",
    val txEnabled: Boolean = false,
    val txSource: Int = 0,
    val cwState: CwTransmitState = CwTransmitState.IDLE,
    val cwPosition: Int = 0,
    val cwLength: Int = 0,
    val error: String? = null,
    val txCtcssHz: Double? = null,
    val rxCtcssHz: Double? = null,
    val activationCtcssHz: Double? = null,
    val nativeCwWpm: Int? = null,
    val nativePower: Int? = null,
    val nativeMenuOpen: Boolean = false,
    val settingsSequence: Int? = null,
    val nativeSettingsModified: Boolean = false,
    val nativeProfileValid: Boolean = false,
    val nativeProfileKey: String? = null,
    val nativeSavedMode: Int? = null,
    val nativeSavedTxCtcssHz: Double? = null,
    val nativeBaseFrequencies: RadioBaseFrequencies? = null,
    val nativeRxCorrectionHz: Long = 0,
    val nativeTxCorrectionHz: Long = 0,
    val nativeSquelch: Int? = null,
    val nativeStepHz: Long = 250,
    val nativeProfileGeneration: Long? = null,
    val nativeProfileCapacity: Int? = null,
    val profileStore:RadioProfileStoreStatus?=null,
    val transmitStateKnown:Boolean=true,
    val phoneMicrophone: Boolean = false,
    val bluetoothAudioConnected: Boolean = false,
    val bluetoothAudioError: String? = null,
    val radioMonitor: Boolean = false,
    val recording: Boolean = false,
    val playingRecording: Boolean = false,
    val lastRecording: String? = null,
    val recordingNeedsMicrophone: Boolean = false,
    val recordingSupported: Boolean = false,
    val recordingError: String? = null,
    val recordingExportError: String? = null,
    val recordingExporting: Boolean = false,
    val recordingExported: Boolean = false,
    val isN76: Boolean = false,
    val nativeCwOptions: NativeCwOptions? = null,
    val handsetMacroStatus: HandsetMacroStatus? = null,
    val nativeDiagnostics: NativeRadioDiagnostics? = null
)

object CwTransmitText {
    private const val ALLOWED = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 /?.,=-+(&"
    fun normalize(text: String): String = text.uppercase(java.util.Locale.ROOT)
        .replace(Regex("\\s+"), " ").trim().also { value ->
            require(value.isNotEmpty() && value.length <= 4096) { "CW text must contain 1–4096 characters" }
            require(value.all { it in ALLOWED }) { "Unsupported CW character (use A–Z, 0–9, space, / ? . , = - + ( &)" }
        }

    fun chunks(text: String, maximum: Int): List<String> {
        require(maximum >= 1)
        var remaining = normalize(text)
        require(remaining.split(' ').all { it.length <= maximum }) { "A CW word exceeds the firmware text buffer" }
        val chunks = mutableListOf<String>()
        while (remaining.length > maximum) {
            val boundary = remaining.lastIndexOf(' ', maximum).takeIf { it > 0 } ?: maximum
            chunks += remaining.take(boundary)
            remaining = remaining.drop(boundary).trimStart()
        }
        if (remaining.isNotEmpty()) chunks += remaining
        return chunks
    }
}

object CwTransmitPresets {
    val defaults=listOf("CQ CQ CQ", "DE", "K", "RR", "599", "TNX", "73", "SK")
    fun normalize(text: String): String = if(text.isBlank()) "" else CwTransmitText.normalize(text)
}
