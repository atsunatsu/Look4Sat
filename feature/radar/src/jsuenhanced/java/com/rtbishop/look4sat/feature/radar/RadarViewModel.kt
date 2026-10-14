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
package com.rtbishop.look4sat.feature.radar

import com.rtbishop.look4sat.core.domain.model.RadioControlSettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.audio.AudioConsumer
import com.rtbishop.look4sat.core.domain.audio.IAudioHub
import com.rtbishop.look4sat.core.domain.predict.CelestialComputer
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.rotator.RotatorConnectionState
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.IRadioTrackingService
import com.rtbishop.look4sat.core.domain.repository.IReporter
import com.rtbishop.look4sat.core.domain.repository.IRotatorTrackingService
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISensorsRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.TrackingPhase
import com.rtbishop.look4sat.core.domain.sstv.LineRecoveryStrategy
import com.rtbishop.look4sat.core.domain.sstv.SstvDecoder
import com.rtbishop.look4sat.core.domain.cw.CwDecoder
import com.rtbishop.look4sat.core.domain.usecase.IAddToCalendar
import com.rtbishop.look4sat.core.domain.usecase.ISaveImage
import com.rtbishop.look4sat.core.domain.usecase.IShowToast
import com.rtbishop.look4sat.core.domain.utility.toTimerString
import com.rtbishop.look4sat.core.presentation.formatFrequency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class RadarViewModel(
    private val bluetoothReporter: IReporter,
    private val networkReporter: IReporter,
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo,
    private val sensorsRepo: ISensorsRepo,
    private val addToCalendar: IAddToCalendar,
    private val trackingService: IRadioTrackingService,
    private val rotatorTrackingService: IRotatorTrackingService,
    private val audioHub: IAudioHub,
    private val saveImage: ISaveImage,
    private val showToast: IShowToast
) : ViewModel() {

    private val stationPos = settingsRepo.stationPosition.value
    private val magDeclination = sensorsRepo.getMagDeclination(stationPos)
    private var transponders: List<SatRadio> = emptyList()
    private var calculatorCatnum: Int? = null
    private var sstvDecoder: SstvDecoder? = null
    private var sstvSampleRate: Int? = null
    private var sstvFrameJob: Job? = null
    private var sstvDiagnosticsJob: Job? = null
    private var sstvRecordingJob: Job? = null
    private var cwDecoder: CwDecoder? = null
    private var cwListeningJob: Job? = null
    private var lastRotatorConnectionState: RotatorConnectionState? = null

    // Celestial positions change slowly, recompute at most once per minute
    private var lastCelestialUpdateMs = 0L
    private var cachedSunPos: CelestialComputer.SunPosition? = null
    private var cachedMoonPos: CelestialComputer.MoonPosition? = null

    private val _uiState = MutableStateFlow(
        RadarState(
            isUtc = settingsRepo.otherSettings.value.stateOfUtc,
            orientationValues = correctedOrientation(sensorsRepo.sensorData.value),
            shouldShowSweep = settingsRepo.otherSettings.value.stateOfSweep,
            shouldUseCompass = settingsRepo.otherSettings.value.stateOfSensors,
            sstv = SstvSubState(selectedMode = settingsRepo.otherSettings.value.sstvMode),
            radioControl = RadioControlSubState(cwPresets=settingsRepo.getRemoteCwPresets()),
            radioTransport = settingsRepo.radioControlSettings.value.catTransport,
            rotatorEnabled = settingsRepo.rotatorSettings.value.enabled,
            rotatorPosition = if (settingsRepo.rotatorSettings.value.enabled) {
                preferredRotatorPosition()
            } else {
                null
            }
        )
    )
    val uiState: StateFlow<RadarState> = _uiState

    init {
        collectCompassSensor()
        collectSettingsChanges()
        collectPassAndStartTickLoop()
        collectRadioTrackingState()
        collectRotatorTrackingState()
    }

    private fun collectCompassSensor() {
        if (!settingsRepo.otherSettings.value.stateOfSensors) return
        viewModelScope.launch {
            sensorsRepo.enableSensor()
            sensorsRepo.sensorData.collect { data ->
                val orientationValues = correctedOrientation(data)
                _uiState.update { it.copy(orientationValues = orientationValues) }
            }
        }
    }

    private fun correctedOrientation(data: Pair<Float, Float>): Pair<Float, Float> {
        val offset = settingsRepo.otherSettings.value.compassOffsetDegrees
        val azimuth = ((data.first + magDeclination + offset) % 360f + 360f) % 360f
        return azimuth to data.second
    }

    private fun collectSettingsChanges() {
        viewModelScope.launch {
            settingsRepo.otherSettings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        isUtc = settings.stateOfUtc,
                        shouldShowSweep = settings.stateOfSweep,
                        shouldUseCompass = settings.stateOfSensors
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.radioControlSettings.collectLatest { settings ->
                _uiState.update { it.copy(radioTransport = settings.catTransport,
                    radioControl = it.radioControl.copy(isL4sModel = settings.radioModel in setOf(
                        RadioControlSettings.MODEL_QUANSHENG_UVK1, RadioControlSettings.MODEL_QUANSHENG_UVK5V3))) }
            }
        }
        viewModelScope.launch {
            settingsRepo.rotatorSettings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        rotatorEnabled = settings.enabled,
                        rotatorPosition = preferredRotatorPosition().takeIf { settings.enabled }
                    )
                }
                syncRotatorTracking(settings.enabled, _uiState.value.currentPass)
            }
        }
    }

    // --- Pass loading split into focused functions ---

    private fun collectPassAndStartTickLoop() {
        viewModelScope.launch {
            val pass = findCurrentPass() ?: return@launch
            val allRadios = loadPassData(pass)
            while (isActive) {
                tickPass(pass, allRadios)
                delay(1000.milliseconds)
            }
        }
    }

    private fun findCurrentPass(): OrbitalPass? {
        val passes = satelliteRepo.passes.value
        val (catNum, aosTime) = satelliteRepo.selectedPass.value
        return passes.find { it.catNum == catNum && it.aosTime == aosTime }
            ?: passes.find { it.catNum == catNum && aosTime in it.aosTime..it.losTime }
            ?: passes.find { it.catNum == catNum }
            ?: passes.firstOrNull()
    }

    // Loads transmitters and satellite track for pass, sets initial state, returns full radio list
    private suspend fun loadPassData(pass: OrbitalPass): List<SatRadio> {
        _uiState.update { it.copy(currentPass = pass) }
        syncRotatorTracking(settingsRepo.rotatorSettings.value.enabled, pass)
        val allRadios = satelliteRepo.getRadiosWithId(pass.catNum)
        transponders = allRadios.filter { it.downlinkLow != null }
        if (allRadios.isNotEmpty()) {
            val firstUuid = allRadios.first().uuid
            _uiState.update {
                it.copy(transceivers = it.transceivers.copy(selectedUuid = firstUuid))
            }
            transponders.find { it.uuid == firstUuid }?.let { trackingService.setTransponder(it) }
        }
        if (!pass.isDeepSpace) {
            val track = satelliteRepo.getTrack(pass.orbitalObject, stationPos, pass.aosTime, pass.losTime)
            _uiState.update { it.copy(satTrack = track) }
        }
        return allRadios
    }

    // --- Per-second tick ---

    private suspend fun tickPass(pass: OrbitalPass, allRadios: List<SatRadio>) {
        val timeNow = System.currentTimeMillis()
        val pos = satelliteRepo.getPosition(pass.orbitalObject, stationPos, timeNow)

        // Recompute celestial positions at most once per minute (they move very slowly)
        if (timeNow - lastCelestialUpdateMs >= 60_000L) {
            cachedSunPos = CelestialComputer.getSunPosition(stationPos, timeNow)
            cachedMoonPos = CelestialComputer.getMoonPosition(stationPos, timeNow)
            lastCelestialUpdateMs = timeNow
        }

        val (time, isAos) = computeTimer(pass.isDeepSpace, pass.aosTime, pass.losTime, timeNow)
        _uiState.update {
            it.copy(
                currentTime = time, isTimeAos = isAos,
                orbitalPos = pos, sunPosition = cachedSunPos, moonPosition = cachedMoonPos
            )
        }
        processRadios(allRadios, pass.orbitalObject, timeNow)
        sendFrequencyData()
    }

    private fun collectRadioTrackingState() {
        viewModelScope.launch {
            trackingService.state.collect { svc ->
                _uiState.update { state ->
                    state.copy(
                        radioControl = state.radioControl.copy(
                            txPanel = RadioPanelState(
                                label = "TX (Uplink)",
                                isConnected = svc.txConnected,
                                frequencyHz = svc.txFrequencyHz,
                                frequencyDisplay = svc.txFrequencyHz?.let { formatFrequency(it) } ?: "---",
                                mode = svc.txMode
                            ),
                            rxPanel = RadioPanelState(
                                label = "RX (Downlink)",
                                isConnected = svc.rxConnected,
                                frequencyHz = svc.rxFrequencyHz,
                                frequencyDisplay = svc.rxFrequencyHz?.let { formatFrequency(it) } ?: "---",
                                mode = svc.rxMode
                            ),
                            txBaseFrequencyHz = svc.txBaseFrequencyHz,
                            ctcssTone = svc.ctcssTone,
                            isTracking = svc.isActive || svc.trackingPhase == TrackingPhase.INITIALIZING,
                            isReady = svc.isActive && svc.trackingPhase == TrackingPhase.READY,
                            selectedTransponderUuid = svc.selectedTransponder?.uuid,
                            errorMessage = svc.errorMessage,
                            remoteRadio = svc.remoteRadio,
                            transmitOwner = svc.transmitOwner,
                            pttState = svc.pttState,
                            targetTxHz = svc.targetTxFrequencyHz,
                            targetRxHz = svc.targetRxFrequencyHz,
                            referenceRxHz=svc.nominalRxFrequencyHz,
                            calculatorLinked=svc.calculatorLinked,
                            txDopplerHz=svc.txDopplerCorrectionHz,rxDopplerHz=svc.rxDopplerCorrectionHz,
                            referenceDirty=svc.referenceDirty,
                            serviceEditingLocked=svc.frequencyEditingLocked
                        )
                    )
                }
            }
        }
    }

    private fun collectRotatorTrackingState() {
        viewModelScope.launch {
            rotatorTrackingService.state.collect { serviceState ->
                val connectedNow = serviceState.connectionState == RotatorConnectionState.CONNECTED &&
                    lastRotatorConnectionState != RotatorConnectionState.CONNECTED
                lastRotatorConnectionState = serviceState.connectionState
                _uiState.update { state ->
                    state.copy(
                        rotatorPosition = serviceState.reportedPosition
                            .takeIf { serviceState.feedbackStatus == com.rtbishop.look4sat.core.domain.rotator.RotatorFeedbackStatus.FRESH }
                            .takeIf { state.rotatorEnabled }
                        )
                }
                if (connectedNow) {
                    syncRotatorTracking(_uiState.value.rotatorEnabled, _uiState.value.currentPass)
                }
            }
        }
    }

    private fun syncRotatorTracking(enabled: Boolean, pass: OrbitalPass?) {
        val serviceState = rotatorTrackingService.state.value
        if (serviceState.trackingBlocked) return
        if (!enabled) {
            if (serviceState.isTrackingRequested) rotatorTrackingService.stopTracking(park = false)
            return
        }
        if (pass == null) return
        if (!serviceState.isTrackingRequested || serviceState.satelliteCatalogNumber != pass.catNum || serviceState.passAosTimeMillis != pass.aosTime) {
            rotatorTrackingService.startTracking(pass)
        }
    }

    private fun preferredRotatorPosition() = rotatorTrackingService.state.value.let {
        it.reportedPosition.takeIf { _ -> it.feedbackStatus == com.rtbishop.look4sat.core.domain.rotator.RotatorFeedbackStatus.FRESH }
    }

    override fun onCleared() {
        trackingService.cancelRemoteTransmit()
        sensorsRepo.disableSensor()
        viewModelScope.launch { audioHub.stopAll() }
    }

    fun onAction(action: RadarAction) {
        if(_uiState.value.radioControl.frequencyEditingLocked && when(action) {
            is RadarAction.SetBaseFrequencies -> true
            is RadarAction.ChangeCalculatorOffset -> calculatorCatnum==trackingService.state.value.currentPass?.catNum &&
                (_uiState.value.radioControl.txPanel.isConnected || _uiState.value.radioControl.rxPanel.isConnected)
            is RadarAction.SetCtcssTone,is RadarAction.TuneCalculator,
            is RadarAction.SelectTransmitter -> true
            else->false
        })return
        when (action) {
            is RadarAction.AddToCalendar -> addToCalendar(action.name, action.aosTime, action.losTime)
            is RadarAction.SelectTransmitter -> {
                // Compute toggle state before the update so we don't read post-update value
                val isTogglingOff = _uiState.value.transceivers.selectedUuid == action.uuid
                val newUuid = if (isTogglingOff) null else action.uuid
                _uiState.update {
                    it.copy(transceivers = it.transceivers.copy(selectedUuid = newUuid))
                }
                // Only update the tracking service when selecting a different transponder to
                // avoid resetting a user-adjusted TX base on re-expand
                if (!isTogglingOff) {
                    transponders.find { it.uuid == action.uuid }?.let { trackingService.setTransponder(it) }
                }
            }
            is RadarAction.SetBaseFrequencies -> trackingService.setBaseFrequencies(action.bases)
            is RadarAction.SetCtcssTone -> trackingService.setCtcssTone(action.toneHz)
            RadarAction.ToggleTracking -> {
                val svc = trackingService.state.value
                if (svc.isActive || svc.trackingPhase == TrackingPhase.INITIALIZING) {
                    trackingService.stopTracking()
                } else {
                    val pass = _uiState.value.currentPass ?: return
                    val transponder = svc.selectedTransponder ?: return
                    trackingService.startTracking(pass, transponder, svc.txBaseFrequencyHz)
                }
            }
            RadarAction.ConnectRadios -> viewModelScope.launch { trackingService.connectRadios() }
            RadarAction.DisconnectRadios -> viewModelScope.launch { trackingService.disconnectRadios() }
            is RadarAction.VoicePtt -> trackingService.setVoicePtt(action.pressed)
            is RadarAction.SendCw -> trackingService.sendCw(action.text, action.wpm)
            is RadarAction.SaveCwPreset -> {
                settingsRepo.updateRemoteCwPreset(action.index,action.text)
                _uiState.update { it.copy(radioControl=it.radioControl.copy(cwPresets=settingsRepo.getRemoteCwPresets())) }
            }
            RadarAction.StopRemoteTx -> trackingService.cancelRemoteTransmit()

            // SSTV actions
            is RadarAction.SstvPermissionResult -> {
                _uiState.update { it.copy(sstv = it.sstv.copy(hasPermission = action.granted)) }
                if (!action.granted) stopSstvRecording()
            }
            RadarAction.SstvStartRecording -> startSstvRecording()
            RadarAction.SstvStopRecording -> stopSstvRecording()
            RadarAction.SstvSaveImage -> {
                val frame = _uiState.value.sstv.currentFrame ?: return
                val pixels = frame.imagePixels ?: return
                _uiState.update { it.copy(sstv = it.sstv.copy(isSaving = true)) }
                viewModelScope.launch {
                    saveImage(pixels, frame.imageWidth, frame.imageHeight, frame.modeName)
                    _uiState.update { it.copy(sstv = it.sstv.copy(isSaving = false)) }
                    showToast("Image saved")
                }
            }
            is RadarAction.SstvSelectMode -> {
                sstvDecoder?.lockMode(action.modeName)
                _uiState.update { it.copy(sstv = it.sstv.copy(selectedMode = action.modeName)) }
                settingsRepo.updateOtherSettings { it.copy(sstvMode = action.modeName) }
            }
            RadarAction.SstvReset -> {
                sstvDecoder?.clearPixels()
                _uiState.update { it.copy(sstv = it.sstv.copy(currentFrame = null)) }
            }

            // CW actions
            is RadarAction.CwPermissionResult -> {
                _uiState.update { it.copy(cw = it.cw.copy(hasPermission = action.granted)) }
                if (!action.granted) stopCwListening()
            }
            RadarAction.CwStartListening -> startCwListening()
            RadarAction.CwStopListening -> stopCwListening()
            RadarAction.CwReset -> {
                cwDecoder?.resetDecoder()
                _uiState.update { it.copy(cw = it.cw.copy(decodedText = "")) }
            }
            is RadarAction.CwSetToneFreq -> {
                _uiState.update { it.copy(cw = it.cw.copy(cwToneFreq = action.freq)) }
                cwDecoder = null
            }
            is RadarAction.CwToggleExpanded -> {
                _uiState.update { it.copy(cw = it.cw.copy(isExpanded = action.expanded)) }
            }
            is RadarAction.CwNativeSessionChanged -> {
                if (action.active) {
                    stopSstvRecording()
                    stopCwListening()
                    _uiState.update { it.copy(cw = it.cw.copy(status = CwStatus.Listening)) }
                } else {
                    _uiState.update { it.copy(cw = it.cw.copy(status = CwStatus.Idle)) }
                }
            }
            is RadarAction.ChangeCalculatorOffset -> {
                // Keyed by the transponder driving the calculator, so the offset saves
                // even when no transceiver is selected (or selection was toggled off)
                val catnum = calculatorCatnum ?: _uiState.value.transceivers.selectedUuid?.let { uuid ->
                    transponders.find { it.uuid == uuid }?.catnum
                }
                if (catnum != null) {
                    settingsRepo.setSatelliteOffset(catnum, action.offsetKHz)
                }
                _uiState.update { it.copy(calculatorOffsetKHz = action.offsetKHz) }
            }
            is RadarAction.CalculatorTransponderChanged -> {
                calculatorCatnum = action.catnum
                val offsetKHz = action.catnum?.let { settingsRepo.getSatelliteOffset(it) } ?: ""
                _uiState.update { it.copy(calculatorOffsetKHz = offsetKHz) }
            }
            is RadarAction.UpdateCalculatorFrequency -> {
                _uiState.update {
                    if (it.calculatorTxHz == action.txHz && it.calculatorRxHz == action.rxHz) it
                    else it.copy(calculatorTxHz = action.txHz, calculatorRxHz = action.rxHz)
                }
            }
            is RadarAction.TuneCalculator -> {
                transponders.find { it.uuid == action.tuning.transponderUuid }?.let {
                    _uiState.update { state -> state.copy(transceivers = state.transceivers.copy(selectedUuid = it.uuid)) }
                    trackingService.tuneFromCalculator(it, action.tuning)
                }
            }
        }
    }

    private fun computeTimer(isDeepSpace: Boolean, aosTime: Long, losTime: Long, timeNow: Long): Pair<String, Boolean> {
        return when {
            isDeepSpace -> 0L.toTimerString() to false
            aosTime > timeNow -> (aosTime - timeNow).toTimerString() to true
            else -> (losTime - timeNow).toTimerString() to false
        }
    }

    private fun sendFrequencyData() {
        val rc = settingsRepo.rcSettings.value
        reportFrequency(networkReporter, rc.frequencyState, rc.frequencyFormat)
        reportFrequency(bluetoothReporter, rc.bluetoothFrequencyState, rc.bluetoothFrequencyFormat)
    }

    private fun reportFrequency(
        reporter: IReporter,
        frequencyEnabled: Boolean,
        frequencyFormat: String
    ) {
        if (frequencyEnabled) {
            _uiState.value.transceivers.selectedFrequency?.let { freq ->
                reporter.reportFrequency(frequencyFormat, freq)
            }
        }
    }

    private suspend fun processRadios(radios: List<SatRadio>, orbitalObject: OrbitalObject, time: Long) {
        val transmitters = satelliteRepo.getRadios(orbitalObject, stationPos, radios, time)
        _uiState.update { state ->
            val freq = if (state.transceivers.selectedUuid != null) {
                val selectedRadio = transmitters.firstOrNull { it.uuid == state.transceivers.selectedUuid }
                selectedRadio?.let { radio ->
                    val low = radio.downlinkLow
                    val high = radio.downlinkHigh
                    when {
                        low != null && high != null -> (low + high) / 2
                        low != null -> low
                        else -> null
                    }
                }
            } else null

            val current = state.transceivers
            if (current.transmitters == transmitters && current.selectedFrequency == freq) {
                return@update state
            }
            state.copy(transceivers = current.copy(transmitters = transmitters, selectedFrequency = freq))
        }
    }

    private fun initSstvDecoder(sampleRate: Int): SstvDecoder {
        if (sstvSampleRate != sampleRate) {
            sstvFrameJob?.cancel()
            sstvDiagnosticsJob?.cancel()
            val decoder = SstvDecoder(
                sampleRate = sampleRate,
                targetRmsLevel = SSTV_TARGET_RMS,
                includeScopeData = SSTV_INCLUDE_SCOPE,
                enableRmsNormalization = SSTV_ENABLE_RMS_NORMALIZATION,
                preFilterCutoffHz = SSTV_PREFILTER_CUTOFF_HZ,
                enablePreFilter = SSTV_ENABLE_PREFILTER,
                enableDiagnosticsHandle = SSTV_ENABLE_DIAGNOSTICS_HANDLE,
                lineRecoveryStrategy = SSTV_LINE_RECOVERY_STRATEGY
            )
            sstvDecoder = decoder
            sstvSampleRate = sampleRate
            decoder.lockMode(_uiState.value.sstv.selectedMode)
            _uiState.update { it.copy(sstv = it.sstv.copy(supportedModes = decoder.supportedModes)) }
            sstvFrameJob = viewModelScope.launch {
                decoder.frames.collect { frame ->
                    val metrics = decoder.getQualityMetrics()
                    _uiState.update {
                        it.copy(sstv = it.sstv.copy(currentFrame = frame, diagnosticsMetrics = metrics))
                    }
                }
            }
            decoder.getDiagnosticsHandle()?.let { handle ->
                sstvDiagnosticsJob = viewModelScope.launch {
                    handle.metrics.collectLatest { metrics ->
                        _uiState.update { it.copy(sstv = it.sstv.copy(diagnosticsMetrics = metrics)) }
                    }
                }
            }
        }
        return requireNotNull(sstvDecoder)
    }

    private fun startSstvRecording() {
        if (sstvRecordingJob?.isActive == true) return
        _uiState.update { it.copy(sstv = it.sstv.copy(status = SstvStatus.Recording)) }
        sstvRecordingJob = viewModelScope.launch {
            audioHub.audioFlow(AudioConsumer.SSTV)
                .onCompletion {
                    _uiState.update { it.copy(sstv = it.sstv.copy(status = SstvStatus.Idle)) }
                }
                .collect { chunk -> initSstvDecoder(chunk.sampleRate).feedSamples(chunk.samples) }
        }
    }

    private fun stopSstvRecording() {
        sstvRecordingJob?.cancel()
        sstvRecordingJob = null
        _uiState.update { it.copy(sstv = it.sstv.copy(status = SstvStatus.Idle)) }
    }

    private fun initCwDecoder(sampleRate: Int): CwDecoder {
        if (cwDecoder?.sampleRate != sampleRate) {
            cwDecoder = CwDecoder(
                sampleRate = sampleRate,
                cwToneFreq = _uiState.value.cw.cwToneFreq
            )
        }
        return requireNotNull(cwDecoder)
    }

    private fun startCwListening() {
        if (cwListeningJob?.isActive == true) return
        if (!_uiState.value.cw.hasPermission) {
            // Permission not yet granted — request it (launcher handles the result)
            return
        }
        // Stop SSTV if running (audio capture is shared)
        stopSstvRecording()
        cwDecoder?.resetDecoder()
        _uiState.update { it.copy(cw = it.cw.copy(status = CwStatus.Listening)) }
        cwListeningJob = viewModelScope.launch {
            audioHub.audioFlow(AudioConsumer.CW)
                .onCompletion {
                    _uiState.update { it.copy(cw = it.cw.copy(status = CwStatus.Idle)) }
                }
                .collect { chunk ->
                    val decoder = initCwDecoder(chunk.sampleRate)
                    withContext(Dispatchers.Default) { decoder.processBuffer(chunk.samples) }
                    _uiState.update {
                        it.copy(
                            cw = it.cw.copy(
                                decodedText = decoder.decodedTextFlow.value,
                                signalStrength = decoder.signalStrength.value
                            )
                        )
                    }
                }
        }
    }

    private fun stopCwListening() {
        cwListeningJob?.cancel()
        cwListeningJob = null
        _uiState.update { it.copy(cw = it.cw.copy(status = CwStatus.Idle)) }
    }

    companion object {
        // SSTV Decoder Tuning Parameters
        // ==============================
        // SSTV_TARGET_RMS: Normalized signal level before FM demodulation.
        //   - 0.25: Aggressive (original default), amplifies noise; use only for clean inputs
        //   - 0.35-0.40: RECOMMENDED for typical phone/mic inputs
        //   - 0.50-0.60: Conservative, best for noisy environments
        private const val SSTV_TARGET_RMS = 0.40f  // Changed from 0.25 to safer default

        // Enable/disable RMS normalization entirely. Set false for direct receiver outputs.
        private const val SSTV_ENABLE_RMS_NORMALIZATION = true

        // High-pass pre-filter to remove DC offset and subsonic noise before RMS normalization.
        // Improves weak signal robustness by cleaning the noise floor estimate.
        private const val SSTV_PREFILTER_CUTOFF_HZ = 500.0  // Removes everything below 500 Hz

        // Enable pre-filtering. Set false to skip (minimal CPU cost if disabled).
        private const val SSTV_ENABLE_PREFILTER = true

        // Diagnostics handle can be enabled temporarily to capture field reports.
        // Interpretation notes for noisy recordings:
        //   - syncHitRate < 0.30: weak/noisy signal path or mistuned gain
        //   - predictedLineBursts rising quickly: frequent sync loss and likely vertical collapse
        //   - maxPredictedStreak > 3 in Robot36 mode: noisy path and synthetic line flooding
        private const val SSTV_ENABLE_DIAGNOSTICS_HANDLE = false

        // Line recovery policy:
        //   - Look4SatLimited: caps predicted lines to reduce visible vertical collapse.
        //   - Robot36Compatible: unlimited predicted lines (legacy Robot36 behavior).
        // Ask users to compare both if they report line-skipping/compression artifacts.
        private val SSTV_LINE_RECOVERY_STRATEGY = LineRecoveryStrategy.Look4SatLimited

        // Debug: Return scope visualization in frames (increases per-frame memory usage).
        private const val SSTV_INCLUDE_SCOPE = false

        val CTCSS_TONES = com.rtbishop.look4sat.core.domain.utility.CtcssTones.tenths.map { it/10.0 }


        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                RadarViewModel(
                    bluetoothReporter = container.provideBluetoothReporter(),
                    networkReporter = container.provideNetworkReporter(),
                    satelliteRepo = container.satelliteRepo,
                    settingsRepo = container.settingsRepo,
                    sensorsRepo = container.provideSensorsRepo(),
                    addToCalendar = container.provideAddToCalendar(),
                    trackingService = container.radioTrackingService,
                    rotatorTrackingService = container.rotatorTrackingService,
                    audioHub = container.audioHub,
                    saveImage = container.provideSaveImage(),
                    showToast = container.provideShowToast()
                )
            }
        }
    }
}
