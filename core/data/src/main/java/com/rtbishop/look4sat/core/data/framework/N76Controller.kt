/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingDisplay
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioCapabilities
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioState
import com.rtbishop.look4sat.core.domain.model.N76ControlOptions
import com.rtbishop.look4sat.core.domain.model.RadioRecordingAction
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** GAIA over Android RFCOMM, as used by HTCommander's Android backend. */
internal class N76Controller(
    private val transport: RadioTransport,
    private val scope: CoroutineScope,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics,
    private val replyTimeoutMillis: Long = 1_000L,
    private val audio: N76Audio? = null,
    private val options: () -> N76ControlOptions = { N76ControlOptions() },
    private val recorder: N76Recording? = null
) : IRadioController {
    private val state = MutableStateFlow(RemoteRadioState(transmitStateKnown = false))
    override val remoteState: StateFlow<RemoteRadioState> = state
    override val isConnected get() = state.value.connected && transport.isConnected
    override val supportsAtomicPair = true
    override val supportsPttReadback = true
    override val commandTimeoutMillis = 8_000L
    private val mutex = Mutex()
    private val decoder = N76Protocol.Decoder()
    private var monitor: Job? = null
    private var pair: N76Protocol.Frequencies? = null
    private var display: RadioTrackingDisplay? = null
    private var txTone: Double? = null
    private var requestedTone: Double? = null
    private var contextKey: String? = null
    private var updates = 0
    private var stopPending = false
    @Volatile private var closing = false
    @Volatile private var ownsPtt = false
    @Volatile private var controlPtt = false
    private var firmwareVersion = 0
    private var radioMonitor = false
    private var monitorByTracking = false
    private var audioRelayChanged = false
    private var statusFailures = 0
    private var recordingOperationError: String? = null
    init { audio?.setRadioPcmSink { recorder?.feedRadio(it) } }

    override suspend fun connect(): Boolean = io(false) {
        if (isConnected) return@io true
        reset()
        closing = false
        state.update { it.copy(error = null) }
        transport.configureBluetoothConnection(secureFirst = false, timeoutMillis = 8_000L)
        if (!transport.connect()) {
            report("connect/transport", transport.lastIoFailure ?: "Bluetooth connection failed")
            return@io false
        }
        var ready = false
        do {
            decoder.clear()
            // Identification is read-only. No channel, mode or transmit changes here.
            val info = request(N76Protocol.GET_DEVICE_INFO, byteArrayOf(3), attempts = 3)
            if (info != null) {
                if (info.size < 10) {
                    report("connect/device", "Malformed N76 device information; length=${info.size}")
                    break
                }
                val product = ((info[1].toInt() and 255) shl 8) or (info[2].toInt() and 255)
                val supportsVfo = info[7].toInt() and 2 != 0
                firmwareVersion = ((info[4].toInt() and 255) shl 8) or (info[5].toInt() and 255)
                diagnose("connect/device-info", "product=$product firmware=$firmwareVersion supportsVfo=$supportsVfo " +
                    "capabilities=${info.copyOfRange(6, 10).joinToString("") { "%02x".format(it.toInt() and 255) }}")
                // Some N76 variants clear the VFO flag but implement remote frequency mode.
                // Verify its read-only opcode instead of treating a model flag as a command refusal.
                if (!supportsVfo) {
                    val frequencyStatus = request(N76Protocol.GET_FREQUENCY, attempts = 2)
                    if (frequencyStatus == null || frequencyStatus.size < 4) {
                        if (frequencyStatus != null) report("connect/frequency", "Malformed N76 frequency status; length=${frequencyStatus.size}")
                        break
                    }
                    // Zero is valid here: frequency mode need not be active when connecting.
                    diagnose("connect/frequency", "Read-only frequency-mode query verified; product=$product")
                }
                if (readStatus() != null) {
                    state.update { it.copy(connected = true,
                        firmware = "${info[4].toInt() and 255}.${info[5].toInt() and 255}",
                        capabilities = CAPABILITIES, error = null, isN76 = true,
                        recordingSupported = recorder != null,
                        recordingNeedsMicrophone = options().recordMicrophone) }
                    ready = true
                    break
                }
            }
        } while (transport.canTryAlternativeConnection && transport.connectAlternative())
        if (!ready) {
            dropConnection(state.value.error ?: "N76 read-only handshake failed")
            return@io false
        }
        // Notification registration is session-local; it does not write channel memory.
        if (!transport.write(N76Protocol.frame(N76Protocol.REGISTER_NOTIFICATION, byteArrayOf(1, 14)))) {
            dropConnection("N76 status notification registration failed")
            return@io false
        }
        if (audio != null) {
            val readyAudio = prepareAudioRelay() && audio.connect()
            state.update { it.copy(capabilities = CAPABILITIES,
                phoneMicrophone = true, bluetoothAudioConnected = readyAudio,
                bluetoothAudioError = if (readyAudio) null else audio.state.value.error ?: "Bluetooth audio is unavailable") }
        }
        if (!isConnected) return@io false
        monitor = scope.launch(Dispatchers.IO) {
            while (isActive && state.value.connected && !closing) {
                delay(500L)
                io(false) {
                    if (closing) return@io false
                    if (!transport.isConnected) {
                        dropConnection("N76 Bluetooth connection lost")
                        return@io false
                    }
                    audio?.state?.value?.let { snapshot -> state.update { it.copy(
                        bluetoothAudioConnected = snapshot.connected,
                        bluetoothAudioError = if (snapshot.connected) null else snapshot.error ?: it.bluetoothAudioError) } }
                    syncRecordingState()
                    val transmitting = readStatus() ?: return@io false
                    if (!transmitting && stopPending) stopFrequencyMode()
                    if (!transmitting && audioRelayChanged && audio?.state?.value?.connected != true) restoreAudioRelay()
                    true
                }
            }
        }
        diagnose("connect/ready", "N76 GAIA identified; native satellite VFO available")
        true
    }

    override suspend fun disconnect() = withContext(NonCancellable + Dispatchers.IO) {
        closing = true
        monitor?.join(); monitor = null
        mutex.withLock {
            try {
                releaseOwnedPtt()
                // Do not retune an externally keyed transmitter even during teardown.
                if (isConnected && pair != null && readStatus() == false) stopFrequencyMode()
            } finally {
                stopRecordingSafely()
                audio?.disconnect()
                if (isConnected) restoreAudioRelay()
                if (isConnected && radioMonitor) setMonitorLocked(false)
                transport.disconnect()
                reset()
                state.update { it.copy(connected = false, transmitStateKnown = false) }
                diagnose("disconnect", "origin=app")
            }
        }
    }

    override suspend fun prepareTrackingContext(contextKey: String): Boolean = io(false) {
        if (!isConnected || readStatus() != false) return@io false
        if (this.contextKey != contextKey) {
            if (pair != null && !stopFrequencyMode()) return@io false
            this.contextKey = contextKey
            txTone = null; requestedTone = null; display = null; updates = 0
        }
        true
    }

    override suspend fun updateTrackingDisplay(display: RadioTrackingDisplay): Boolean = io(false) {
        if (runCatching { N76Protocol.satelliteInfo(display) }.isFailure) return@io false
        this.display = display
        isConnected
    }

    override suspend fun setTrackingFrequencies(rxHz: Long, txHz: Long?): FrequencyPairResult = io(FrequencyPairResult.FAILED) {
        applyPair(N76Protocol.Frequencies(rxHz, txHz), txTone)
    }

    private suspend fun applyPair(target: N76Protocol.Frequencies, tone: Double?): FrequencyPairResult {
        if (!isConnected || !N76Protocol.validFrequency(target.rxHz) ||
            target.txHz?.let { !N76Protocol.validFrequency(it) } == true) return FrequencyPairResult.FAILED
        val transmitting = readStatus() ?: return if (isConnected && !state.value.transmitStateKnown)
            FrequencyPairResult.HELD else FrequencyPairResult.FAILED
        if (transmitting) return FrequencyPairResult.HELD
        val settings = options().normalized()
        val satelliteFirmware = settings.satelliteFirmware(firmwareVersion)
        val info = display
        if (contextKey == null || (satelliteFirmware && settings.sendSatelliteInfo && info == null)) return FrequencyPairResult.FAILED.also {
            report("tracking/context", "Select a satellite before tuning N76")
        }
        if (satelliteFirmware && settings.sendSatelliteInfo && info != null &&
            request(N76Protocol.SET_SATELLITE_INFO, N76Protocol.satelliteInfo(info)) == null) return FrequencyPairResult.FAILED
        val actualTone = if (settings.forceTxCtcss) settings.txCtcssHz else tone
        val rxTone = if (settings.forceRxCtcss) settings.rxCtcssHz else null
        // No retry of mutating requests: an ACK timeout must not replay a stale tune.
        if (request(N76Protocol.SET_FREQUENCY, N76Protocol.frequencyParameters(target, actualTone, updates,
                satelliteFirmware, rxTone, settings.power.ordinal)) == null) return FrequencyPairResult.FAILED
        pair = target; txTone = tone; updates++; stopPending = false
        state.update { it.copy(rxFrequencyHz = target.rxHz, txFrequencyHz = target.txHz,
            rxMode = "FM", txMode = "FM", txEnabled = target.txHz != null,
            txCtcssHz = actualTone.takeIf { target.txHz != null }, rxCtcssHz = rxTone,
            appliedRevision = it.appliedRevision + 1, error = null) }
        if (updates == 1) {
            if (settings.openSquelchOnTrack && !radioMonitor && setMonitorLocked(true)) monitorByTracking = true
            if (settings.recordOnTrack && (settings.recordRadio || settings.recordMicrophone))
                performRecordingOperation { it.start(display?.satelliteName.orEmpty(), audio?.state?.value?.connected == true) }
        }
        diagnose("frequency/applied", "rx=${target.rxHz} tx=${target.txHz ?: "disabled"} ack=0")
        return FrequencyPairResult.APPLIED
    }

    override suspend fun setFrequency(frequencyHz: Long): Boolean = setWorkingFrequency(frequencyHz)
    override suspend fun setWorkingFrequency(frequencyHz: Long): Boolean = io(false) {
        val previous = pair ?: return@io false
        applyPair(previous.copy(rxHz = frequencyHz), txTone) == FrequencyPairResult.APPLIED
    }
    override suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean = io(false) {
        val previous = pair?.takeIf { it.txHz != null } ?: return@io false
        applyPair(previous.copy(txHz = frequencyHz), txTone) == FrequencyPairResult.APPLIED
    }

    override suspend fun setSplitModes(rxMode: String?, txMode: String?): Boolean = io(false) {
        // N76 CAT always operates in FM, independently of transponder metadata.
        val accepted = isConnected && readStatus() == false
        if (accepted) state.update { it.copy(rxMode = "FM", txMode = "FM") }
        accepted
    }
    override suspend fun setMode(mode: String): Boolean = setSplitModes(mode, mode)
    override suspend fun setVfo(vfoA: Boolean): Boolean = vfoA && isConnected
    override suspend fun setBand(frequencyHz: Long): Boolean = isConnected && N76Protocol.validFrequency(frequencyHz)

    override suspend fun setSplitMode(enabled: Boolean): Boolean = io(false) {
        if (!isConnected) return@io false
        if (!enabled && options().recordOnTrack) performRecordingOperation { it.stop() }
        if (enabled || pair == null) return@io true
        val transmitting = readStatus() ?: return@io false
        if (transmitting) { stopPending = true; return@io true }
        stopFrequencyMode()
    }

    private suspend fun stopFrequencyMode(): Boolean {
        if (request(N76Protocol.SET_FREQUENCY, ByteArray(if (options().satelliteFirmware(firmwareVersion)) 16 else 14)) == null) return false
        if (monitorByTracking) { setMonitorLocked(false); monitorByTracking = false }
        pair = null; updates = 0; stopPending = false
        state.update { it.copy(rxFrequencyHz = null, txFrequencyHz = null, txEnabled = false, error = null) }
        diagnose("tracking/stop", "Native channel state restored")
        return true
    }

    override suspend fun configureTxCtcss(toneHz: Double?): Boolean = io(false) {
        if (runCatching { N76Protocol.toneCode(toneHz) }.isFailure) {
            report("tone/invalid", "Invalid CTCSS tone")
            return@io false
        }
        if (!isConnected || readStatus() != false) return@io false
        val current = pair
        if (current != null && applyPair(current, toneHz) != FrequencyPairResult.APPLIED) return@io false
        txTone = toneHz; requestedTone = toneHz
        true
    }
    override suspend fun setCtcssTone(toneHz: Double): Boolean = configureTxCtcss(toneHz)
    override suspend fun setCtcssMode(enabled: Boolean): Boolean = configureTxCtcss(if (enabled) requestedTone else null)

    override suspend fun readFrequencyAndMode(): Pair<Long, String>? = io(null) {
        val bytes = request(N76Protocol.GET_FREQUENCY, attempts = 2) ?: return@io null
        val value = N76Protocol.frequency(bytes) ?: return@io null
        state.update { it.copy(rxFrequencyHz = value.first, rxMode = value.second) }
        value
    }
    override suspend fun readWorkingFrequency(): Long? = readFrequencyAndMode()?.first
    // TX is the last accepted pair (or a live notification), not an invented second GET_FREQUENCY field.
    override suspend fun readTxVfoFrequency(): Long? = state.value.txFrequencyHz
    override suspend fun readPttState(): Boolean? = io(null) { readStatus() }
    override suspend fun pttOn(): Boolean {
        if (ownsPtt) return isConnected && state.value.transmitStateKnown && state.value.txSource != 0
        var confirmed = false
        try {
            return startVoicePtt().also { confirmed = it }
        } finally {
            if (!confirmed && ownsPtt) withContext(NonCancellable) {
                io(false) { releaseOwnedPtt() }
            }
        }
    }

    private suspend fun startVoicePtt(): Boolean = io(false) {
        if (recorder != null && !performRecordingOperation { it.stopPlayback() }) return@io false
        val session = audio
        if (!isConnected || pair?.txHz == null || readStatus() != false) return@io false
        ownsPtt = true
        controlPtt = session == null
        val started = try {
            if (session == null) request(N76Protocol.DO_PROG_FUNC, N76Protocol.ptt(true)) != null
            else session.startTransmit()
        }
        catch (cancelled: CancellationException) {
            throw cancelled
        }
        if (!started) {
            report("ptt/start", session?.state?.value?.error ?: state.value.error ?: "N76 PTT did not start")
            return@io false
        }
        repeat(5) {
            if (readStatus() == true) return@io true
            delay(50)
        }
        releaseOwnedPtt()
        report("ptt/timeout", "N76 PTT ON was not confirmed")
        false
    }
    override suspend fun pttOff(): Boolean {
        // End the audio stream before waiting for any ongoing CAT read/tune lock.
        if (ownsPtt && !controlPtt) audio?.stopTransmit()
        return io(false) {
            if (!releaseOwnedPtt()) return@io false
            repeat(5) {
                if (readStatus() == false) return@io true
                delay(50)
            }
            report("ptt/timeout", "N76 PTT OFF was not confirmed")
            false
        }
    }

    private suspend fun releaseOwnedPtt(): Boolean {
        if (!ownsPtt) return true
        val accepted = if (controlPtt) {
            request(N76Protocol.DO_PROG_FUNC, N76Protocol.ptt(false)) != null
        } else audio?.stopTransmit() == true
        val released = accepted || (transport.isConnected && readStatus() == false)
        if (released) { ownsPtt = false; controlPtt = false }
        return released
    }

    override suspend fun setAudioForeground(enabled: Boolean) {
        if (!enabled) audio?.stopTransmit()
        io(false) {
            if (!enabled) stopRecordingSafely()
            val session = audio ?: return@io true
            if (enabled && isConnected) { if (prepareAudioRelay()) session.connect() }
            else { session.disconnect(); if (isConnected) restoreAudioRelay() }
            state.update { it.copy(bluetoothAudioConnected = session.state.value.connected,
                bluetoothAudioError = if (session.state.value.connected) null else session.state.value.error ?: it.bluetoothAudioError,
                capabilities = CAPABILITIES) }
            true
        }
    }

    override suspend fun setRadioMonitor(enabled: Boolean): Boolean = io(false) { setMonitorLocked(enabled) }
    private suspend fun setMonitorLocked(enabled: Boolean): Boolean {
        if (!isConnected || readStatus() != false) return false
        if (radioMonitor == enabled) return true
        if (request(N76Protocol.DO_PROG_FUNC, byteArrayOf(0, 0x0f)) == null) return false
        radioMonitor = enabled
        state.update { it.copy(radioMonitor = enabled) }
        return true
    }

    override suspend fun recordingAction(action: RadioRecordingAction): Boolean = io(false) {
        if (action == RadioRecordingAction.PLAY_LAST && (ownsPtt || readStatus() != false)) {
            recordingOperationError = "Stop transmission before playing a recording"
            syncRecordingState()
            return@io false
        }
        performRecordingOperation { recording -> when (action) {
            RadioRecordingAction.START -> recording.start(display?.satelliteName.orEmpty(), audio?.state?.value?.connected == true)
            RadioRecordingAction.STOP -> recording.stop()
            RadioRecordingAction.PLAY_LAST -> recording.playLast()
            RadioRecordingAction.STOP_PLAYBACK -> recording.stopPlayback()
        } }
    }

    override suspend fun saveRecording(destinationUri: String): Boolean = io(false) {
        performRecordingOperation { it.save(destinationUri) }
    }

    private suspend fun performRecordingOperation(block: suspend (N76Recording) -> Boolean): Boolean {
        val recording = recorder ?: return false
        return try {
            val accepted = block(recording)
            if (accepted) recordingOperationError = null
            syncRecordingState()
            accepted
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            recordingOperationError = error.message ?: "Recording operation failed"
            diagnostics.record(ControlDiagnosticSource.RADIO, "n76/recording/operation", "${error.javaClass.simpleName}: ${error.message}", ControlDiagnosticSeverity.ERROR)
            syncRecordingState()
            false
        }
    }

    private suspend fun stopRecordingSafely() {
        performRecordingOperation { it.stop() }
        performRecordingOperation { it.stopPlayback() }
    }

    private fun syncRecordingState() {
        val snapshot = recorder?.state?.value ?: return
        state.update { it.copy(recording = snapshot.recording, playingRecording = snapshot.playing,
            lastRecording = snapshot.lastFile, recordingError = snapshot.error ?: recordingOperationError,
            recordingExportError = snapshot.exportError, recordingExporting = snapshot.exporting,
            recordingExported = snapshot.lastFile != null && snapshot.lastFile == snapshot.exportedFile) }
    }

    private suspend fun prepareAudioRelay(): Boolean {
        if (readStatus() != false) return false
        val settings = request(33, disconnectOnTimeout = false) ?: return false
        if (settings.size < 3) {
            state.update { it.copy(bluetoothAudioError = "Bluetooth audio is unavailable") }
            diagnose("audio/relay", "Malformed BSS settings; length=${settings.size}")
            return false
        }
        if (settings[2].toInt() and 2 != 0) return true
        settings[2] = (settings[2].toInt() or 2).toByte()
        audioRelayChanged = true
        if (request(34, settings, disconnectOnTimeout = false) == null) return false
        return request(33, disconnectOnTimeout = false)?.let { it.size >= 3 && it[2].toInt() and 2 != 0 } == true
    }

    private suspend fun restoreAudioRelay() {
        if (!audioRelayChanged || readStatus() != false) return
        val current = request(33, disconnectOnTimeout = false) ?: return
        if (current.size < 3) return
        if (current[2].toInt() and 2 == 0) { audioRelayChanged = false; return }
        current[2] = (current[2].toInt() and 2.inv()).toByte()
        if (request(34, current, disconnectOnTimeout = false) != null) audioRelayChanged = false
    }

    private suspend fun readStatus(): Boolean? {
        val bytes = request(N76Protocol.GET_HT_STATUS, attempts = 2, disconnectOnTimeout = ownsPtt)
        if (bytes == null || bytes.size < 4) {
            state.update { it.copy(transmitStateKnown = false) }
            statusFailures++
            if (state.value.connected) {
                if (bytes != null || !transport.isConnected || statusFailures >= 3)
                    dropConnection(if (bytes != null) "N76 malformed transmit status" else "N76 status timed out after $statusFailures failed polls")
                else state.update { it.copy(error = "N76 status temporarily unavailable") }
            }
            return null
        }
        if (statusFailures != 0) diagnose("status/recovered", "Status query recovered after $statusFailures failed polls")
        statusFailures = 0
        val tx = bytes[0].toInt() and 0x40 != 0
        state.update { it.copy(txSource = if (tx) 1 else 0, transmitStateKnown = true,
            error = it.error.takeUnless { message -> message == "N76 status temporarily unavailable" }) }
        return tx
    }

    private suspend fun request(command: Int, payload: ByteArray = byteArrayOf(), attempts: Int = 1,
        disconnectOnTimeout: Boolean = true): ByteArray? {
        currentCoroutineContext().ensureActive()
        // GAIA has no sequence number. Finish this bounded transaction before allowing
        // cancellation to issue teardown, so its late ACK cannot acknowledge a stop.
        return withContext(NonCancellable) { requestTransaction(command, payload, attempts, disconnectOnTimeout) }
    }

    private suspend fun requestTransaction(command: Int, payload: ByteArray, attempts: Int, disconnectOnTimeout: Boolean): ByteArray? {
        if (!transport.isConnected) return null
        drain()
        repeat(attempts) { attempt ->
            if (!transport.write(N76Protocol.frame(command, payload))) throw IOException(transport.lastIoFailure ?: "Bluetooth write failed")
            val deadline = System.nanoTime() + replyTimeoutMillis * 1_000_000
            while (transport.isConnected && System.nanoTime() < deadline) {
                var response: N76Protocol.Packet? = null
                for (packet in decoder.feed(transport.readAvailable(1024))) {
                    handleNotification(packet)
                    if (packet.group == N76Protocol.GROUP && packet.reply && packet.command == command) response = packet
                }
                response?.let { packet ->
                    val status = packet.payload.firstOrNull()?.toInt()?.and(255)
                    if (status != 0) {
                        if (disconnectOnTimeout || command == N76Protocol.GET_HT_STATUS)
                            report("reply/rejected", "command=$command status=${status ?: "missing"}")
                        else diagnostics.record(ControlDiagnosticSource.RADIO, "n76/audio/rejected",
                            "command=$command status=${status ?: "missing"}", ControlDiagnosticSeverity.WARNING)
                        return null
                    }
                    return packet.payload.copyOfRange(1, packet.payload.size)
                }
                delay(5L)
            }
            diagnose("reply/retry", "command=$command attempt=${attempt + 1}")
        }
        if (disconnectOnTimeout) {
            report("reply/timeout", "command=$command attempts=$attempts")
            if (state.value.connected) dropConnection("N76 reply timed out; reconnect")
        } else diagnostics.record(ControlDiagnosticSource.RADIO,
            if (command == N76Protocol.GET_HT_STATUS) "n76/status/retry" else "n76/audio/retry",
            "command=$command attempts=$attempts; retaining CAT connection", ControlDiagnosticSeverity.WARNING)
        return null
    }

    private suspend fun drain() {
        repeat(8) {
            val bytes = transport.readAvailable(1024)
            if (bytes.isEmpty()) { decoder.clear(); return }
            decoder.feed(bytes).forEach(::handleNotification)
        }
        throw IOException("N76 input did not become quiet")
    }

    private fun handleNotification(packet: N76Protocol.Packet) {
        if (packet.group != N76Protocol.GROUP || packet.command != N76Protocol.EVENT_NOTIFICATION) return
        val bytes = packet.payload
        when (bytes.firstOrNull()?.toInt()?.and(255)) {
            1 -> if (bytes.size >= 5) state.update {
                it.copy(txSource = if (bytes[1].toInt() and 0x40 != 0) 1 else 0, transmitStateKnown = true)
            }
            14 -> if (bytes.size >= 15 && bytes[14] != 0.toByte()) {
                val rx = N76Protocol.frequency(bytes, 1) ?: return
                val tx = N76Protocol.frequency(bytes, 5)
                state.update { it.copy(rxFrequencyHz = rx.first, rxMode = rx.second,
                    txFrequencyHz = tx?.first, txMode = tx?.second ?: "FM") }
            }
        }
    }

    private fun reset() {
        decoder.clear(); pair = null; display = null; contextKey = null
        txTone = null; requestedTone = null; updates = 0; stopPending = false
        radioMonitor = false; monitorByTracking = false; audioRelayChanged = false
        statusFailures = 0
        recordingOperationError = null
    }
    private suspend fun dropConnection(message: String) {
        diagnostics.record(ControlDiagnosticSource.RADIO, "n76/connection/closed",
            "origin=controller reason=$message", ControlDiagnosticSeverity.ERROR)
        if (ownsPtt && controlPtt && transport.isConnected) {
            runCatching { transport.write(N76Protocol.frame(N76Protocol.DO_PROG_FUNC, N76Protocol.ptt(false))) }
        }
        audio?.disconnect(); ownsPtt = false
        stopRecordingSafely()
        controlPtt = false
        transport.disconnect()
        reset()
        state.update { it.copy(connected = false, transmitStateKnown = false, error = message) }
    }
    private suspend fun <T> io(fallback: T, operation: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { operation() }
            catch (cancelled: CancellationException) {
                if (!state.value.connected) withContext(NonCancellable) { dropConnection("N76 connection cancelled") }
                throw cancelled
            } catch (error: Exception) {
                report("io/failed", "${error.javaClass.simpleName}: ${error.message}")
                withContext(NonCancellable) { dropConnection(error.message ?: "N76 connection lost") }
                fallback
            }
        }
    }
    private fun diagnose(stage: String, message: String) =
        diagnostics.record(ControlDiagnosticSource.RADIO, "n76/$stage", message)
    private fun report(stage: String, message: String) {
        state.update { it.copy(error = message) }
        diagnostics.record(ControlDiagnosticSource.RADIO, "n76/$stage", message, ControlDiagnosticSeverity.ERROR)
    }
    private companion object {
        val CAPABILITIES = RemoteRadioCapabilities(
            frequencyStepHz = 1, maxCwText = 0, minimumWpm = 0, maximumWpm = 0, watchdogMillis = 0,
            minimumRxHz = 136_000_000, maximumRxHz = 480_000_000, rxModeMask = 1, txModeMask = 1,
            atomicPair = true, voicePtt = true, cwText = false, ctcss = true,
            liveFmRetune = false, liveCwRetune = false, trackingDisplay = true
        )
    }
}
