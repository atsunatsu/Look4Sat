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
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothManager
import android.util.Log
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.model.parseRadioTcpEndpoint
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.ft4.IFt4TransmitCoordinator
import com.rtbishop.look4sat.core.domain.ft4.TxLease
import com.rtbishop.look4sat.core.domain.ft4.TxRequest
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.SPEED_OF_LIGHT
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.IRadioTrackingService
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import com.rtbishop.look4sat.core.domain.repository.PttState
import com.rtbishop.look4sat.core.domain.repository.TrackingPhase
import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import com.rtbishop.look4sat.core.domain.repository.CwTransmitState
import com.rtbishop.look4sat.core.domain.repository.CwTransmitText
import com.rtbishop.look4sat.core.domain.repository.RadioTransmitOwner
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import com.rtbishop.look4sat.core.domain.utility.DialFollowPolicy
import com.rtbishop.look4sat.core.domain.utility.DialFollowState
import com.rtbishop.look4sat.core.domain.utility.DialLeg
import com.rtbishop.look4sat.core.domain.utility.TransponderMapper
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator
import com.rtbishop.look4sat.core.domain.utility.CalculatorTuning
import com.rtbishop.look4sat.core.domain.utility.frequencies
import com.rtbishop.look4sat.core.domain.utility.defaultTxCtcssHz
import com.rtbishop.look4sat.core.domain.repository.radioContextKey
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RadioTrackingService(
    private val appScope: CoroutineScope,
    private val bluetoothManager: BluetoothManager?,
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo,
    private val clock: IDisciplinedClock,
    private val controllerFactory: ((Boolean, String) -> IRadioController)? = null,
    private val transportFactory: ((String, String, Int, Int) -> RadioTransport)? = null,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics
) : IRadioTrackingService, IFt4TransmitCoordinator {

    private val tag = "RadioTracking"
    private val _state = MutableStateFlow(RadioTrackingState())
    override val state: StateFlow<RadioTrackingState> = _state

    private var txController: IRadioController? = null
    private var rxController: IRadioController? = null
    private var connectedCatPttSupported = true
    private var trackingJob: Job? = null
    private var remoteMonitorJob: Job? = null
    @Volatile private var calculatorTuning: CalculatorTuning? = null
    @Volatile private var nativeBaseOverride: com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies? = null
    @Volatile private var ctcssExplicit = false
    private var manualJob: Job? = null
    @Volatile private var manualOwner = RadioTransmitOwner.NONE
    private val manualEpoch = AtomicLong()
    private val tuningWake = Channel<Unit>(Channel.CONFLATED)
    private val connectionMutex = Mutex()
    private val transmitMutex = Mutex()
    private val tuningRevision = AtomicLong()
    private val pendingControls = AtomicInteger()
    @Volatile private var activeTxLease: TxLease? = null
    @Volatile private var transmitPreparing = false
    private var pttWatchdogJob: Job? = null
    private var dataModeController: IRadioController? = null
    private var dataModeBaseMode: String? = null
    private var dataModeUsesSplit = false
    private var nextLeaseId = 1L
    private val commandLatencyEstimator = RadioCommandLatencyEstimator()
    private val commandActor = RadioCommandActor(appScope) { busy, failure ->
        _state.update { current ->
            current.copy(
                commandBusy = busy,
                lastCommandError = failure ?: current.lastCommandError,
                errorMessage = failure ?: current.errorMessage
            )
        }
    }
    private val hasTransmitClaim: Boolean
        get() = transmitPreparing || activeTxLease != null || manualOwner != RadioTransmitOwner.NONE

    private val profiles=RadioProfileManager({txController},{_state.value},settingsRepo,satelliteRepo) {radio,data->
        transmitMutex.withLock {
            if(userFrequencyEditingBlocked() || txController!==radio) null else radio.profileStoreCommand(data)
        }
    }
    override val profileManagerState get()=profiles.state
    override suspend fun refreshRadioProfiles()=profiles.refresh()
    override suspend fun requestDeleteRadioProfile(slot:Int):Boolean {
        val key=profiles.state.value.profiles.find {it.slot==slot}?.key
        val result=profiles.delete(slot)
        if(result && key!=null && _state.value.remoteRadio?.nativeProfileKey==key)transmitMutex.withLock {
            nativeBaseOverride=null;calculatorTuning=null;txController?.invalidatePendingPttOn()
            _state.update {it.copy(txBaseFrequencyHz=null,nominalRxFrequencyHz=null,nominalTxFrequencyHz=null,
                targetRxFrequencyHz=null,targetTxFrequencyHz=null,calculatorLinked=false,isActive=false,trackingPhase=TrackingPhase.IDLE)}
        }
        return result
    }
    override suspend fun prepareRadioProfileImport(json:String)=profiles.prepareImport(json)
    override suspend fun stageRadioProfileImport(overwrite:Set<String>)=profiles.import(overwrite)
    override suspend fun exportRadioProfiles()=profiles.export()
    override suspend fun cancelRadioProfileOperation()=profiles.cancel()
    override fun setRadioProfileAlias(key:String,alias:String)=profiles.alias(key,alias)
    override suspend fun applyRadioControlSettings(settings:RadioControlSettings):Boolean=connectionMutex.withLock connection@ {
        var reconnectRequired=false
        val accepted=transmitMutex.withLock edit@ {
            if(userFrequencyEditingBlocked())return@edit false
            if(!authorizeFrequencyEdit(settings.l4sCorrectionEditStepHz))return@edit false
            val old=settingsRepo.radioControlSettings.value
            reconnectRequired=old.radioModel!=settings.radioModel || old.catTransport!=settings.catTransport ||
                old.txRadioAddress!=settings.txRadioAddress || old.rxRadioAddress!=settings.rxRadioAddress ||
                old.baudRate!=settings.baudRate || old.civAddress!=settings.civAddress ||
                old.splitMode!=settings.splitMode || old.duplexMode!=settings.duplexMode || old.tcpProtocol!=settings.tcpProtocol
            if(reconnectRequired) {
                pendingControls.incrementAndGet()
                manualEpoch.incrementAndGet()
                txController?.invalidatePendingPttOn()
            }
            try { settingsRepo.updateRadioControlSettings(settings) }
            catch(error:Throwable) { if(reconnectRequired)pendingControls.decrementAndGet();throw error }
            true
        }
        if(accepted && reconnectRequired) {
            try { disconnectRadiosLocked() } finally { pendingControls.decrementAndGet() }
        }
        accepted
    }

    // ── Connection ──────────────────────────────────────────────────────────

    override suspend fun connectRadios(): Unit = connectionMutex.withLock {
        cancelTrackingAndJoin()
        emergencyPttOff()
        txController?.disconnect()
        rxController?.disconnect()
        txController = null
        rxController = null
        remoteMonitorJob?.cancel()
        remoteMonitorJob = null
        nativeBaseOverride = null
        calculatorTuning = null
        _state.update { it.copy(nominalRxFrequencyHz=null, nominalTxFrequencyHz=null,
            targetRxFrequencyHz=null, targetTxFrequencyHz=null, calculatorLinked=false,
            txConnected=false,rxConnected=false,physicalConnectionCount=0,remoteRadio=null,
            catTransmitting=false, catTransmitStateKnown=true) }

        val rcSettings = settingsRepo.radioControlSettings.value
        val txAddr     = rcSettings.txRadioAddress
        val rxAddr     = rcSettings.rxRadioAddress
        val profile    = radioProfile(rcSettings.radioModel, rcSettings.civAddress)
        if (profile.isFtx1 && rcSettings.catTransport != RadioControlSettings.TRANSPORT_USB) {
            _state.update { it.copy(errorMessage = "FTX-1 requires USB CAT") }
            return
        }
        if (rcSettings.catTransport == RadioControlSettings.TRANSPORT_BLUETOOTH &&
            rcSettings.radioModel !in com.rtbishop.look4sat.core.domain.model.RadioModelCatalog.bluetoothModels) {
            _state.update { it.copy(errorMessage = "Select USB or TCP for this radio") }
            return
        }
        if (profile.isL4s && rcSettings.catTransport != RadioControlSettings.TRANSPORT_USB) {
            _state.update { it.copy(errorMessage = "L4S radios require USB CDC/OTG") }
            return
        }
        connectedCatPttSupported = profile.capabilities.ptt
        if (profile.icomVariant == IcomCivVariant.IC820 && txAddr.isNotBlank() && txAddr == rxAddr) {
            _state.update { it.copy(errorMessage = "IC-820 needs separate TX/RX radios; for receive-only tracking configure RX only") }
            return
        }
        val isIcom     = profile.isIcom
        val isSplit    = profile.capabilities.singleRadioSplit && (rcSettings.splitMode || profile.isL4s)
        if (!isSplit && txAddr.isNotBlank() && txAddr == rxAddr) {
            _state.update { it.copy(txConnected=false, rxConnected=false, physicalConnectionCount=0,
                errorMessage="Dual-radio operation requires separate TX/RX endpoints; select single-radio split when supported") }
            return
        }
        val usesHamlib = rcSettings.catTransport == RadioControlSettings.TRANSPORT_TCP &&
            rcSettings.tcpProtocol == RadioControlSettings.TCP_PROTOCOL_HAMLIB
        val isSatelliteMode = isSplit && profile.supportsSatelliteMode &&
            rcSettings.duplexMode == RadioControlSettings.DUPLEX_MODE_SATELLITE &&
            !usesHamlib
        diagnose(
            stage = "connect/start",
            message = "model=${profile.model} transport=${rcSettings.catTransport} " +
                "split=$isSplit satellite=$isSatelliteMode"
        )

        Log.i(tag, "connectRadios model=${rcSettings.radioModel} split=$isSplit TX=$txAddr RX=$rxAddr")
        _state.update {
            it.copy(
                splitMode = isSplit,
                satelliteMode = isSatelliteMode,
                txConnected = false,
                rxConnected = false,
                physicalConnectionCount = 0,
                pttState = PttState.OFF,
                lastCommandError = null,
                remoteRadio = null,
                transmitOwner = RadioTransmitOwner.NONE
            )
        }

        if (rcSettings.catTransport == RadioControlSettings.TRANSPORT_VOX) {
            Log.i(tag, "VOX transport selected; CAT radios are not connected")
            diagnose("connect/vox", "CAT bypassed for VOX")
            return
        }

        if (rcSettings.catTransport == RadioControlSettings.TRANSPORT_TCP) {
            val invalidEndpoint = when {
                txAddr.isNotBlank() && parseRadioTcpEndpoint(txAddr) == null -> "TX" to txAddr
                !isSplit && rxAddr.isNotBlank() && parseRadioTcpEndpoint(rxAddr) == null -> "RX" to rxAddr
                else -> null
            }
            if (invalidEndpoint != null) {
                diagnose(
                    "connect/validation",
                    "invalid ${invalidEndpoint.first} TCP endpoint",
                    ControlDiagnosticSeverity.ERROR
                )
                _state.update {
                    it.copy(
                        errorMessage = "Invalid ${invalidEndpoint.first} TCP endpoint " +
                            "(${invalidEndpoint.second}). Use host:port or [IPv6]:port"
                    )
                }
                return
            }
        }

        if (isSplit) {
            // Single-radio split mode: only TX slot is used
            if (txAddr.isBlank()) {
                _state.update { it.copy(errorMessage = "No radio address configured in Settings") }
                return
            }
            val tx = makeController(profile, txAddr)
            txController = tx
            rxController = null
            _state.update { it.copy(errorMessage = null) }
            val txOk = connectController(tx, "TX")
            if(txOk) _state.value.selectedTransponder?.let { selectProfileContext(tx,it) }
            _state.update {
                it.copy(
                    txConnected = txOk,
                    rxConnected = txOk,
                    physicalConnectionCount = if (txOk) 1 else 0,
                    errorMessage = if (!txOk) "Could not connect to radio ($txAddr)" else null
                )
            }
            Log.i(tag, "Icom single-radio mode connected: txOk=$txOk")
        } else {
            if (txAddr.isBlank() && rxAddr.isBlank()) {
                _state.update { it.copy(errorMessage = "No radio addresses configured in Settings") }
                return
            }
            val tx = makeController(profile, txAddr)
            val rx = makeController(profile, rxAddr)
            txController = tx
            rxController = rx
            _state.update { it.copy(errorMessage = null) }
            val txOk = if (txAddr.isNotBlank()) connectController(tx, "TX") else false
            val rxOk = if (rxAddr.isNotBlank()) connectController(rx, "RX") else false
            _state.update {
                it.copy(
                    txConnected = txOk,
                    rxConnected = rxOk,
                    physicalConnectionCount = listOf(txOk, rxOk).count { connected -> connected },
                    errorMessage = when {
                        !txOk && !rxOk -> "Could not connect to TX and RX radios"
                        !txOk && txAddr.isNotBlank() -> "Could not connect to TX radio ($txAddr)"
                        !rxOk && rxAddr.isNotBlank() -> "Could not connect to RX radio ($rxAddr)"
                        else           -> null
                    }
                )
            }
            Log.i(tag, "Dual-radio connected: txOk=$txOk rxOk=$rxOk")
        }
        txController?.remoteState?.let { remoteFlow ->
            remoteMonitorJob = appScope.launch {
                var previousTxSource = remoteFlow.value.txSource
                var previousSettingsSequence: Int? = null
                var previousProfileGeneration: Long? = null
                remoteFlow.collect { remote ->
                    profiles.observe(remote.profileStore)
                    val settingsChanged=remote.settingsSequence!=null && remote.settingsSequence!=previousSettingsSequence
                    val matchingProfile=remote.nativeProfileValid && _state.value.selectedTransponder?.let { profileKey(remote,it) }==remote.nativeProfileKey
                    _state.update { current -> current.copy(
                        remoteRadio = remote,
                        txConnected = remote.connected, rxConnected = remote.connected,
                        physicalConnectionCount = if (remote.connected) 1 else 0,
                        txFrequencyHz = remote.txFrequencyHz, rxFrequencyHz = remote.rxFrequencyHz,
                        txMode=remote.txMode,rxMode=remote.rxMode,
                        ctcssTone=if(settingsChanged && pendingControls.get()==0 && current.trackingPhase!=TrackingPhase.INITIALIZING &&
                            (current.isActive || !ctcssExplicit || matchingProfile))
                            (if(!current.isActive && matchingProfile) remote.nativeSavedTxCtcssHz else remote.txCtcssHz) else current.ctcssTone,
                        pttState = if (remote.txSource != 0) PttState.ON else if (current.pttState == PttState.ERROR) PttState.ERROR else PttState.OFF,
                        lastCommandError = remote.error ?: current.lastCommandError,
                        isActive = current.isActive && remote.connected,
                        trackingPhase = if (!remote.connected && current.isActive) TrackingPhase.ERROR else current.trackingPhase,
                        errorMessage = remote.error ?: current.errorMessage
                    ) }
                    if (previousTxSource!=0 && remote.txSource==0 && remote.connected) tuningWake.trySend(Unit)
                    previousTxSource=remote.txSource
                    previousSettingsSequence=remote.settingsSequence
                    if(remote.nativeProfileGeneration!=previousProfileGeneration && remote.nativeSettingsModified && matchingProfile &&
                        pendingControls.get()==0 && _state.value.trackingPhase!=TrackingPhase.INITIALIZING) {
                        nativeBaseOverride=remote.nativeBaseFrequencies;calculatorTuning=null
                        remote.nativeBaseFrequencies?.let { bases -> _state.update { it.copy(txBaseFrequencyHz=bases.txHz,
                            nominalRxFrequencyHz=bases.rxHz,nominalTxFrequencyHz=bases.txHz,calculatorLinked=false,referenceDirty=false) } }
                        tuningRevision.incrementAndGet();tuningWake.trySend(Unit)
                    }
                    previousProfileGeneration=remote.nativeProfileGeneration
                    if(settingsChanged && matchingProfile && pendingControls.get()==0 && _state.value.trackingPhase!=TrackingPhase.INITIALIZING) ctcssExplicit=true
                    if (!remote.connected && manualOwner != RadioTransmitOwner.NONE) {
                        manualEpoch.incrementAndGet()
                        transmitMutex.withLock { forcePttOffLocked(remote.error ?: "L4S disconnected") }
                    }
                }
            }
        }
        if (profile.icomVariant == IcomCivVariant.IC705) {
            val tx = txController
            val rx = rxController
            remoteMonitorJob = appScope.launch {
                while (isActive && txController === tx && rxController === rx) {
                    val txOk = tx?.isConnected == true
                    val rxOk = if (isSplit) txOk else rx?.isConnected == true
                    _state.update { current ->
                        if (current.txConnected == txOk && current.rxConnected == rxOk) current else current.copy(
                            txConnected = txOk, rxConnected = rxOk,
                            physicalConnectionCount = if (isSplit) (if (txOk) 1 else 0) else listOf(txOk, rxOk).count { it },
                            isActive = current.isActive && (txOk || rxOk),
                            trackingPhase = if (current.isActive && !txOk && !rxOk) TrackingPhase.ERROR else current.trackingPhase,
                            errorMessage = if (!txOk && !rxOk) "IC-705 connection lost" else current.errorMessage
                        )
                    }
                    delay(250L)
                }
            }
        }
        diagnose(
            "connect/result",
            "tx=${_state.value.txConnected} rx=${_state.value.rxConnected} " +
                "physical=${_state.value.physicalConnectionCount}",
            if (_state.value.txConnected || _state.value.rxConnected) {
                ControlDiagnosticSeverity.INFO
            } else {
                ControlDiagnosticSeverity.ERROR
            }
        )
    }

    private fun makeController(profile: RadioProfile, address: String): IRadioController {
        val settings = settingsRepo.radioControlSettings.value
        val usesHamlib = settings.catTransport == RadioControlSettings.TRANSPORT_TCP &&
            settings.tcpProtocol == RadioControlSettings.TCP_PROTOCOL_HAMLIB
        val dedicatedSatelliteMode = !usesHamlib && settings.splitMode && profile.supportsSatelliteMode &&
            settings.duplexMode == RadioControlSettings.DUPLEX_MODE_SATELLITE
        val injected = controllerFactory?.invoke(profile.isIcom, address)
        val transport = if (injected == null) {
            transportFactory?.invoke(
                settings.catTransport,
                address,
                settings.baudRate,
                profile.serialStopBits
            )
        } else null
        val controller = injected ?: if (profile.isL4s) {
            L4sController(checkNotNull(transport) { "L4S USB transport is unavailable" }, appScope, diagnostics=diagnostics,
                tuningStepHz={settingsRepo.radioControlSettings.value.l4sDopplerStepHz},
                correctionStepHz={settingsRepo.radioControlSettings.value.l4sCorrectionEditStepHz})
        } else if (profile.isFtx1) {
            Ftx1Controller(checkNotNull(transport) { "FTX-1 USB transport is unavailable" }, diagnostics)
        } else if (usesHamlib) {
            HamlibRigctldController(
                endpoint = address,
                transport = checkNotNull(transport),
                pttReadbackSupported = profile.capabilities.ptt
            )
        } else if (profile.isIcom) {
            if (transport == null) {
                Ic705Controller(
                    bluetoothManager = checkNotNull(bluetoothManager),
                    deviceAddress = address,
                    civAddress = checkNotNull(profile.civAddress),
                    variant = checkNotNull(profile.icomVariant),
                    dedicatedSatelliteMode = dedicatedSatelliteMode,
                    diagnostics = diagnostics
                )
            } else {
                Ic705Controller(
                    bluetoothManager = null,
                    deviceAddress = address,
                    civAddress = checkNotNull(profile.civAddress),
                    transport = transport,
                    variant = checkNotNull(profile.icomVariant),
                    dedicatedSatelliteMode = dedicatedSatelliteMode,
                    diagnostics = diagnostics
                )
            }
        } else {
            if (transport == null) {
                Ft817Controller(
                    bluetoothManager = checkNotNull(bluetoothManager),
                    deviceAddress = address,
                    variant = checkNotNull(profile.yaesuVariant)
                )
            } else {
                Ft817Controller(
                    bluetoothManager = null,
                    deviceAddress = address,
                    transport = transport,
                    variant = checkNotNull(profile.yaesuVariant)
                )
            }
        }
        return SerialRadioController(controller, commandActor)
    }

    private suspend fun connectController(controller: IRadioController, leg: String): Boolean = try {
        controller.connect()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        diagnose("connect/$leg", error.message ?: "CAT handshake failed", ControlDiagnosticSeverity.ERROR)
        withContext(NonCancellable) { runCatching { controller.disconnect() } }
        false
    }

    override suspend fun disconnectRadios(): Unit = connectionMutex.withLock {
        disconnectRadiosLocked()
    }

    private suspend fun disconnectRadiosLocked() {
        diagnose("disconnect/start", "disconnect requested")
        cancelTrackingAndJoin()
        emergencyPttOff()
        txController?.disconnect()
        rxController?.disconnect()
        txController = null
        rxController = null
        remoteMonitorJob?.cancel()
        remoteMonitorJob = null
        _state.update {
            it.copy(
                txConnected = false,
                rxConnected = false,
                isActive = false,
                trackingPhase = TrackingPhase.IDLE,
                pttState = PttState.OFF,
                commandBusy = false,
                txLeaseId = null,
                satelliteMode = false,
                physicalConnectionCount = 0,
                remoteRadio=null,catTransmitting=false,catTransmitStateKnown=true,
                nominalRxFrequencyHz=null,nominalTxFrequencyHz=null,targetRxFrequencyHz=null,targetTxFrequencyHz=null,
                calculatorLinked=false
            )
        }
        diagnose("disconnect/result", "radio controllers disconnected")
    }

    // ── FT4 transmit lease ──────────────────────────────────────────────────

    override suspend fun beginTransmit(request: TxRequest): TxLease = transmitMutex.withLock {
        val startedNanos = System.nanoTime()
        require(request.waveformDurationMillis in 1L..MAX_WAVEFORM_MILLIS) {
            "Invalid FT4 waveform duration"
        }
        require(request.maximumPttMillis >= request.waveformDurationMillis) {
            "PTT watchdog is shorter than the waveform"
        }
        check(!hasTransmitClaim) { "Another transmit lease is active" }
        manualEpoch.incrementAndGet()
        if (settingsRepo.radioControlSettings.value.catTransport == RadioControlSettings.TRANSPORT_VOX) {
            return@withLock beginVoxTransmit(request)
        }
        check(radioProfile(settingsRepo.radioControlSettings.value.radioModel).capabilities.ptt) {
            "This radio does not support CAT PTT; use an external PTT interface or VOX"
        }
        check(pendingControls.get() == 0) { "Radio settings are changing" }
        val safetyGeneration = txController?.pttSafetyGeneration()
        transmitPreparing = true
        _state.update { it.copy(commandBusy=true, transmitPreparing=true) }
        try {
            val current = _state.value
            val pass = checkNotNull(current.currentPass) { "No satellite pass is selected" }
            val transponder = checkNotNull(current.selectedTransponder) { "No transponder is selected" }
            check(current.isActive && current.trackingPhase == TrackingPhase.READY) { "Satellite tracking is not ready" }
            check(current.pttState == PttState.OFF) { "PTT OFF has not been confirmed" }
            val txMode = current.txMode
            val rxMode = current.rxMode
            check(txMode in setOf("USB", "LSB") && rxMode in setOf("USB", "LSB")) {
                "FT4 requires confirmed USB/LSB modes"
            }
            check(TransponderMapper.mapUplinkModeToDownlinkMode(checkNotNull(txMode), transponder.isInverted) == rxMode) {
                "FT4 sidebands do not match transponder inversion"
            }
            if (request.automatic) {
                check(current.trackingPhase == TrackingPhase.READY) { "Satellite tracking is not ready" }
                validateAutomaticWindow(request, pass)
            }
            check(pass.catNum == request.expectedSatelliteCatalogNumber) { "Satellite context changed" }
            check(transponder.uuid == request.expectedTransponderUuid) { "Transponder context changed" }
            val midpoint = request.waveformStartUtcMillis + request.waveformDurationMillis / 2L
            var nominal = current.txBaseFrequencyHz ?: transponder.uplinkCenterFrequency()
            checkNotNull(nominal) { "The selected transponder has no uplink frequency" }
            val position = satelliteRepo.getPosition(
                pass.orbitalObject,
                settingsRepo.stationPosition.value,
                midpoint
            )
            if (request.automatic) validateOrbitPosition(position)
            val calculatorPair = calculatorTuning?.frequencies(transponder, position,
                DopplerFrequencyCalculator.parseOffsetHz(settingsRepo.getSatelliteOffset(pass.catNum)))
            if (calculatorPair != null) nominal = calculatorPair.nominalTxHz
            val requestedFrequency = calculatorPair?.txHz ?: position.getUplinkFreq(nominal)
            val controller = checkNotNull(txController) { "TX radio is not connected" }
            check(controller.isConnected) { "TX radio is not connected" }
            check(refreshCatTransmitState()) { "CAT transmit state is unknown or the radio is transmitting" }

            val split = current.splitMode
            val frequencySet = if (split) {
                controller.setTxVfoFrequency(requestedFrequency)
            } else {
                controller.setFrequency(requestedFrequency)
            }
            check(frequencySet) { "TX frequency command was not acknowledged" }
            val readbackFrequency = if (split) {
                check(controller.setSplitModes(rxMode, txMode)) { "Duplex mode readback did not match" }
                controller.readTxVfoFrequency()
            } else {
                check(controller.setMode(checkNotNull(txMode))) { "TX mode command was not acknowledged" }
                val readback = controller.readFrequencyAndMode()
                check(readback?.second == txMode) { "TX mode readback did not match" }
                rxController?.takeIf { it.isConnected }?.let { receiver ->
                    check(receiver.setMode(checkNotNull(rxMode))) { "RX mode command was not acknowledged" }
                    check(receiver.readFrequencyAndMode()?.second == rxMode) { "RX mode readback did not match" }
                }
                readback.first
            }
            val effectiveFrequency = checkNotNull(readbackFrequency) { "TX frequency readback was unavailable" }
            // Legacy Yaesu CAT uses 10 Hz frequency resolution.
            check(kotlin.math.abs(effectiveFrequency - requestedFrequency) <= 10L) {
                "TX frequency readback did not match"
            }
            val profile = radioProfile(settingsRepo.radioControlSettings.value.radioModel)
            if (profile.capabilities.dataMode) {
                val dataModeEnabled = if (split) {
                    controller.setTxDataMode(enabled = true, baseMode = checkNotNull(txMode))
                } else {
                    controller.setDataMode(enabled = true, baseMode = checkNotNull(txMode))
                }
                check(dataModeEnabled) { "TX DATA mode was not acknowledged" }
                dataModeController = controller
                dataModeBaseMode = txMode
                dataModeUsesSplit = split
                diagnose("transmit/data", "DATA mode enabled split=$split")
            }
            check(pendingControls.get() == 0 && controller.pttSafetyGeneration() == safetyGeneration &&
                _state.value.isActive && _state.value.currentPass == pass &&
                _state.value.selectedTransponder == transponder) { "Radio context changed during transmit preparation" }

            val lease = TxLease(
                id = nextLeaseId++,
                sessionGeneration = request.sessionGeneration,
                effectiveTxFrequencyHz = effectiveFrequency,
                txDopplerCorrectionHz = effectiveFrequency - nominal,
                waveformStartUtcMillis = request.waveformStartUtcMillis,
                waveformMidpointUtcMillis = midpoint,
                waveformDurationMillis = request.waveformDurationMillis,
                maximumPttMillis = request.maximumPttMillis.coerceAtMost(MAX_PTT_MILLIS),
                expectedSatelliteCatalogNumber = request.expectedSatelliteCatalogNumber,
                expectedTransponderUuid = request.expectedTransponderUuid,
                pttSafetyGeneration = checkNotNull(safetyGeneration),
                automatic = request.automatic
            )
            activeTxLease = lease
            diagnose("transmit/prepared", "lease=${lease.id} midpoint=${lease.waveformMidpointUtcMillis}")
            _state.update {
                it.copy(
                    pttState = PttState.OFF,
                    txLeaseId = lease.id,
                    txLeaseGeneration = lease.sessionGeneration,
                    txFrequencyHz = effectiveFrequency,
                    nominalTxFrequencyHz = nominal,
                    txDopplerCorrectionHz = lease.txDopplerCorrectionHz,
                    lastCommandError = null
                )
            }
            lease
        } catch (error: Throwable) {
            withContext(NonCancellable) { restoreDataModeLocked() }
            updateCommandFailure(error)
            throw error
        } finally {
            commandLatencyEstimator.record((System.nanoTime() - startedNanos) / 1_000_000L)
            transmitPreparing = false
            _state.update { it.copy(transmitPreparing=false) }
            tuningRevision.incrementAndGet()
            updateCommandState(busy = false)
        }
    }

    private fun controlUplinkMode(transponder: SatRadio): String? {
        nativeProfileMode(transponder)?.let { return when(it) {0->"FM";3->"NFM";else->"CW"} }
        val mode = normalizeFmMode(transponder.resolvedUplinkMode())
        return if (radioProfile(settingsRepo.radioControlSettings.value.radioModel).isL4s &&
            mode in setOf("USB", "LSB", "SSB", "CW")) "CW" else mode
    }

    private fun controlDownlinkMode(transponder: SatRadio, txMode: String?): String? =
        if(nativeProfileMode(transponder)==3) "NFM"
        else if(nativeProfileMode(transponder)==2) "USB"
        else if (radioProfile(settingsRepo.radioControlSettings.value.radioModel).isL4s && txMode == "CW") "CW"
        else normalizeFmMode(transponder.downlinkMode?.uppercase()) ?: txMode?.let {
            TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
        }

    private suspend fun prepareRemotePair(controller: IRadioController) {
        val current = _state.value
        check(current.isActive && current.trackingPhase == TrackingPhase.READY) { "Start satellite tracking first" }
        val pass = checkNotNull(current.currentPass)
        check(clock.nowMillis() < pass.losTime) { "Satellite pass reached LOS" }
        val transponder = checkNotNull(current.selectedTransponder)
        val base = checkNotNull(if(nativeBaseOverride!=null)nativeBaseOverride?.txHz else current.txBaseFrequencyHz ?: transponder.uplinkCenterFrequency()) { "No uplink frequency" }
        val position = satelliteRepo.getPosition(pass.orbitalObject, settingsRepo.stationPosition.value, clock.nowMillis())
        val offset = if (DopplerFrequencyCalculator.isLinearTransponder(transponder)) {
            DopplerFrequencyCalculator.parseOffsetHz(settingsRepo.getSatelliteOffset(pass.catNum))
        } else 0L
        val nominalRx = nativeBaseOverride?.rxHz ?: (checkNotNull(TransponderMapper.mapUplinkToDownlink(base, transponder)) + offset)
        val calculated = calculatorTuning?.frequencies(transponder, position, offset)
        val tx = calculated?.txHz ?: position.getUplinkFreq(base)
        val rx = calculated?.rxHz ?: position.getDownlinkFreq(nominalRx)
        check(controller.updateTrackingBases(com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(
            calculated?.nominalRxHz ?: nominalRx,calculated?.nominalTxHz ?: base))) { "Radio base settings changed; retry after synchronization" }
        check(controller.setTrackingFrequencies(rx, tx) == FrequencyPairResult.APPLIED) {
            controller.remoteState?.value?.error ?: "RX/TX pair was not applied before TX"
        }
        _state.update { it.copy(targetTxFrequencyHz = trackingTarget(controller,tx,false), targetRxFrequencyHz = trackingTarget(controller,rx,true)) }
    }

    override fun setVoicePtt(enabled: Boolean) {
        if (!enabled) { cancelRemoteTransmit(); return }
        if (hasTransmitClaim || _state.value.pttState != PttState.OFF) {
            failTrackingCommand("Another transmission is active"); return
        }
        val epoch = manualEpoch.incrementAndGet()
        manualJob = appScope.launch {
            try {
                transmitMutex.withLock {
                    check(manualEpoch.get() == epoch) { "PTT request cancelled" }
                    check(!hasTransmitClaim && _state.value.pttState == PttState.OFF) { "Another transmission is active" }
                    val controller = checkNotNull(txController) { "Radio is not connected" }
                    check(controller.remoteState?.value?.capabilities?.voicePtt == true) { "Radio has no remote voice PTT" }
                    check(_state.value.txMode.isFmMode()) { "Voice PTT requires FM; use CW text for linear satellites" }
                    val generation = controller.pttSafetyGeneration()
                    manualOwner = RadioTransmitOwner.VOICE
                    _state.update { it.copy(transmitOwner = manualOwner, pttState = PttState.ARMING, lastCommandError = null) }
                    prepareRemotePair(controller)
                    check(manualEpoch.get() == epoch && controller.pttOnIfGeneration(generation)) {
                        controller.remoteState?.value?.error ?: "PTT ON was not confirmed"
                    }
                    _state.update { it.copy(pttState = PttState.ON) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                withContext(NonCancellable) {
                    transmitMutex.withLock {
                        if (manualOwner == RadioTransmitOwner.VOICE) forcePttOffLocked(error.message)
                        else _state.update { it.copy(lastCommandError = error.message, errorMessage = error.message) }
                    }
                }
            }
        }
    }

    override fun sendCw(text: String, wpm: Int) {
        if (hasTransmitClaim || _state.value.pttState != PttState.OFF) {
            failTrackingCommand("Another transmission is active"); return
        }
        val epoch = manualEpoch.incrementAndGet()
        manualJob = appScope.launch {
            var claimed = false
            try {
                val normalized = CwTransmitText.normalize(text)
                val controller = checkNotNull(txController) { "Radio is not connected" }
                val caps = checkNotNull(controller.remoteState?.value?.capabilities)
                check(caps.cwText && wpm in caps.minimumWpm..caps.maximumWpm) { "CW speed outside firmware limits" }
                transmitMutex.withLock {
                    check(manualEpoch.get() == epoch && !hasTransmitClaim && _state.value.pttState == PttState.OFF) {
                        "Another transmission is active or request cancelled"
                    }
                    check(_state.value.txMode == "CW") { "Select a CW/linear transponder for CW transmission" }
                    manualOwner = RadioTransmitOwner.CW
                    claimed = true
                    _state.update { it.copy(transmitOwner = manualOwner, cwMessagePosition = 0,
                        cwMessageLength = normalized.length, lastCommandError = null) }
                    prepareRemotePair(controller)
                }
                var sent = 0
                for (chunk in CwTransmitText.chunks(normalized, caps.maxCwText)) {
                    transmitMutex.withLock {
                        check(manualEpoch.get() == epoch && manualOwner == RadioTransmitOwner.CW)
                        check(controller.startCw(chunk, wpm)) {
                            controller.remoteState?.value?.error ?: "CW was not accepted"
                        }
                    }
                    val terminal = withTimeoutOrNull((chunk.length * 30L * 1200L / wpm) + 10_000L) {
                        controller.remoteState!!.first { !it.connected || it.cwState in
                            setOf(CwTransmitState.DONE, CwTransmitState.CANCELLED, CwTransmitState.FAILED) }
                    }
                    check(terminal?.connected == true && terminal.cwState == CwTransmitState.DONE) {
                        terminal?.error ?: "CW transmission cancelled or timed out"
                    }
                    sent += chunk.length
                    _state.update { it.copy(cwMessagePosition = sent) }
                }
                _state.update { it.copy(cwMessagePosition = normalized.length) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _state.update { it.copy(lastCommandError = error.message, errorMessage = error.message) } }
            finally {
                withContext(NonCancellable) {
                    transmitMutex.withLock {
                        if (claimed && manualOwner == RadioTransmitOwner.CW) forcePttOffLocked(null)
                    }
                }
            }
        }
    }

    override fun cancelRemoteTransmit() {
        manualEpoch.incrementAndGet()
        txController?.invalidatePendingPttOn()
        manualJob?.cancel()
        appScope.launch { emergencyPttOff() }
    }

    private fun beginVoxTransmit(request: TxRequest): TxLease {
        val midpoint = request.waveformStartUtcMillis + request.waveformDurationMillis / 2L
        val lease = TxLease(
            id = nextLeaseId++,
            sessionGeneration = request.sessionGeneration,
            effectiveTxFrequencyHz = 0L,
            txDopplerCorrectionHz = 0L,
            waveformStartUtcMillis = request.waveformStartUtcMillis,
            waveformMidpointUtcMillis = midpoint,
            waveformDurationMillis = request.waveformDurationMillis,
            maximumPttMillis = request.maximumPttMillis.coerceAtMost(MAX_PTT_MILLIS),
            expectedSatelliteCatalogNumber = request.expectedSatelliteCatalogNumber,
            expectedTransponderUuid = request.expectedTransponderUuid,
            pttSafetyGeneration = 0L,
            automatic = request.automatic,
            usesVox = true
        )
        activeTxLease = lease
        _state.update {
            it.copy(
                pttState = PttState.OFF,
                txLeaseId = lease.id,
                txLeaseGeneration = lease.sessionGeneration,
                lastCommandError = null
            )
        }
        return lease
    }

    override suspend fun confirmTransmitReady(lease: TxLease) = transmitMutex.withLock {
        if (lease.usesVox) {
            check(activeTxLease == lease) { "Transmit lease is stale" }
            return@withLock
        }
        val startedNanos = System.nanoTime()
        check(activeTxLease == lease) { "Transmit lease is stale" }
        updateCommandState(busy = true, pttState = PttState.ARMING)
        try {
            check(radioProfile(settingsRepo.radioControlSettings.value.radioModel).capabilities.ptt) {
                "This radio does not support CAT PTT"
            }
            check(pendingControls.get() == 0 && _state.value.isActive &&
                _state.value.trackingPhase == TrackingPhase.READY) { "Radio tracking context changed" }
            if (lease.automatic) validateAutomaticLease(lease)
            val controller = checkNotNull(txController) { "TX radio is not connected" }
            val confirmed = withTimeoutOrNull(PTT_COMMAND_TIMEOUT_MILLIS) {
                controller.pttOnIfGeneration(lease.pttSafetyGeneration)
            } == true
            check(confirmed) { "PTT ON was not confirmed" }
            _state.update { it.copy(pttState = PttState.ON, lastCommandError = null) }
            diagnose("transmit/ptt", "PTT confirmed lease=${lease.id}")
            pttWatchdogJob?.cancel()
            pttWatchdogJob = appScope.launch {
                delay(lease.maximumPttMillis)
                if (activeTxLease == lease) emergencyPttOff()
            }
        } catch (error: Throwable) {
            forcePttOffLocked(error.message ?: "PTT ON failed")
            throw error
        } finally {
            commandLatencyEstimator.record((System.nanoTime() - startedNanos) / 1_000_000L)
            updateCommandState(busy = false)
        }
    }

    override fun recommendedPrepareLeadMillis(): Long {
        if (settingsRepo.radioControlSettings.value.catTransport == RadioControlSettings.TRANSPORT_VOX) {
            return 350L
        }
        // Include a tracking cycle already in flight and duplex mode/frequency readbacks,
        // including the first transmission before measured latency samples are available.
        val minimum = if (_state.value.splitMode) 2_000L else 1_500L
        return commandLatencyEstimator.p95WithMargin().coerceAtLeast(minimum)
    }

    override suspend fun endTransmit(lease: TxLease) {
        if (lease.usesVox) {
            transmitMutex.withLock {
                if (activeTxLease == lease) releaseVoxLeaseLocked()
            }
            return
        }
        txController?.invalidatePendingPttOn()
        val urgentOffConfirmed = activeTxLease == lease && sendUrgentPttOff()
        transmitMutex.withLock {
            if (activeTxLease != lease) return@withLock
            updateCommandState(busy = true)
            try {
                forcePttOffLocked(null, urgentOffConfirmed.takeIf { it })
            } finally {
                updateCommandState(busy = false)
            }
        }
    }

    private suspend fun cancelTrackingAndJoin() {
        txController?.invalidatePendingPttOn()
        val job = trackingJob
        trackingJob = null
        job?.cancelAndJoin()
        _state.update { it.copy(isActive = false, trackingPhase = TrackingPhase.IDLE) }
    }

    override suspend fun emergencyPttOff() {
        manualEpoch.incrementAndGet()
        manualJob?.cancel()
        if (activeTxLease?.usesVox == true ||
            settingsRepo.radioControlSettings.value.catTransport == RadioControlSettings.TRANSPORT_VOX
        ) {
            transmitMutex.withLock {
                if (activeTxLease?.usesVox == true) releaseVoxLeaseLocked()
            }
            return
        }
        // No app-owned CAT transmission can exist on this radio. Do not pretend
        // that an unsupported PTT OFF command confirmed an external PTT state.
        if (!connectedCatPttSupported && !hasTransmitClaim) return
        txController?.invalidatePendingPttOn()
        val urgentOffConfirmed = sendUrgentPttOff()
        transmitMutex.withLock {
            updateCommandState(busy = true)
            try {
                forcePttOffLocked(null, urgentOffConfirmed.takeIf { it })
            } finally {
                updateCommandState(busy = false)
            }
        }
    }

    private fun releaseVoxLeaseLocked() {
        activeTxLease = null
        _state.update { it.copy(pttState = PttState.OFF, txLeaseId = null, lastCommandError = null) }
    }

    private suspend fun sendUrgentPttOff(): Boolean {
        // Reach the actor's urgent lane before waiting for a possibly suspended tracking cycle.
        return withContext(NonCancellable) {
            val controller = txController
            if (controller?.isConnected == true) {
                withTimeoutOrNull(PTT_COMMAND_TIMEOUT_MILLIS) { controller.pttOff() } == true
            } else activeTxLease == null
        }
    }

    private fun validateAutomaticWindow(request: TxRequest, pass: OrbitalPass) {
        val end = request.waveformStartUtcMillis + request.waveformDurationMillis
        check(end >= request.waveformStartUtcMillis) { "FT4 waveform window overflow" }
        check(request.waveformStartUtcMillis >= pass.aosTime && end <= pass.losTime) {
            "Automatic FT4 waveform is outside the selected pass"
        }
    }

    private suspend fun validateAutomaticLease(lease: TxLease) {
        val current = _state.value
        check(current.isActive && current.trackingPhase == TrackingPhase.READY) {
            "Satellite tracking is not ready"
        }
        val pass = checkNotNull(current.currentPass) { "No satellite pass is selected" }
        val transponder = checkNotNull(current.selectedTransponder) { "No transponder is selected" }
        check(pass.catNum == lease.expectedSatelliteCatalogNumber) { "Satellite context changed" }
        check(transponder.uuid == lease.expectedTransponderUuid) { "Transponder context changed" }
        validateAutomaticWindow(
            TxRequest(
                sessionGeneration = lease.sessionGeneration,
                waveformStartUtcMillis = lease.waveformStartUtcMillis,
                waveformDurationMillis = lease.waveformDurationMillis,
                expectedSatelliteCatalogNumber = lease.expectedSatelliteCatalogNumber,
                expectedTransponderUuid = lease.expectedTransponderUuid,
                automatic = true,
                maximumPttMillis = lease.maximumPttMillis
            ),
            pass
        )
        val now = clock.nowMillis()
        check(now <= lease.waveformStartUtcMillis + MAX_AUTOMATIC_START_LATENESS_MILLIS) {
            "Automatic FT4 transmit slot was missed"
        }
        val position = satelliteRepo.getPosition(
            pass.orbitalObject,
            settingsRepo.stationPosition.value,
            maxOf(now, lease.waveformStartUtcMillis)
        )
        validateOrbitPosition(position)
    }

    private fun validateOrbitPosition(position: com.rtbishop.look4sat.core.domain.predict.OrbitalPos) {
        check(
            position.aboveHorizon &&
                position.azimuth.isFinite() &&
                position.elevation.isFinite() &&
                position.distance.isFinite() &&
                position.distanceRate.isFinite()
        ) { "Satellite orbit position is not valid for automatic transmit" }
    }

    private suspend fun forcePttOffLocked(failure: String?, priorAcknowledged: Boolean? = null) {
        pttWatchdogJob?.cancel()
        pttWatchdogJob = null
        val hadActiveLease = activeTxLease != null || manualOwner != RadioTransmitOwner.NONE
        manualJob?.cancel()
        manualJob = null
        manualOwner = RadioTransmitOwner.NONE
        val controller = txController
        val controllerWasConnected = controller?.isConnected == true
        val acknowledged = priorAcknowledged ?: withContext(NonCancellable) {
            if (controller?.isConnected == true) {
                withTimeoutOrNull(PTT_COMMAND_TIMEOUT_MILLIS) { controller.pttOff() } == true
            } else {
                !hadActiveLease
            }
        }
        val dataRestoreRequired = dataModeController != null
        val dataModeRestored = withContext(NonCancellable) { restoreDataModeLocked() }
        activeTxLease = null
        tuningRevision.incrementAndGet()
        val error = failure ?: when {
            !acknowledged -> "PTT OFF was not confirmed"
            !dataModeRestored -> "TX DATA mode could not be restored"
            else -> null
        }
        _state.update {
            it.copy(
                pttState = if (error == null) PttState.OFF else PttState.ERROR,
                txLeaseId = null,
                transmitOwner = RadioTransmitOwner.NONE,
                lastCommandError = error,
                errorMessage = error ?: it.errorMessage
            )
        }
        diagnose(
            "transmit/release",
            "pttOff=${if (!controllerWasConnected && !hadActiveLease) "not-required" else acknowledged.toString()} " +
                "dataRestored=${if (dataRestoreRequired) dataModeRestored.toString() else "not-required"}",
            if (error == null) ControlDiagnosticSeverity.INFO else ControlDiagnosticSeverity.ERROR
        )
        if (error == null && _state.value.isActive) tuningWake.trySend(Unit)
    }

    private suspend fun restoreDataModeLocked(): Boolean {
        val controller = dataModeController ?: return true
        val baseMode = dataModeBaseMode ?: return true
        val split = dataModeUsesSplit
        dataModeController = null
        dataModeBaseMode = null
        dataModeUsesSplit = false
        if (!controller.isConnected) return false
        return if (split) {
            controller.setTxDataMode(enabled = false, baseMode = baseMode)
        } else {
            controller.setDataMode(enabled = false, baseMode = baseMode)
        }
    }

    private fun updateCommandState(busy: Boolean, pttState: PttState? = null) {
        _state.update { current ->
            current.copy(
                commandBusy = busy,
                pttState = pttState ?: current.pttState
            )
        }
    }

    private fun updateCommandFailure(error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        diagnose("command/failure", message, ControlDiagnosticSeverity.ERROR)
        _state.update { it.copy(trackingPhase = TrackingPhase.ERROR, lastCommandError = message, errorMessage = message) }
    }

    // ── Tracking ────────────────────────────────────────────────────────────

    override fun startTracking(pass: OrbitalPass, transponder: SatRadio, txBaseFreqHz: Long?) {
        if(userFrequencyEditingBlocked()){failTrackingCommand("Frequency editing is disabled during transmission");return}
        if(radioProfile(settingsRepo.radioControlSettings.value.radioModel).isL4s ||
            txController?.supportsPttReadback==true || rxController?.supportsPttReadback==true) {
            val editEpoch=manualEpoch.get()
            appScope.launch {transmitMutex.withLock {
                if(!userFrequencyEditingBlocked() && editEpoch==manualEpoch.get() && authorizeFrequencyEdit())
                    startTrackingAccepted(pass,transponder,txBaseFreqHz)
            }}
        } else startTrackingAccepted(pass,transponder,txBaseFreqHz)
    }
    private fun startTrackingAccepted(pass:OrbitalPass,transponder:SatRadio,txBaseFreqHz:Long?) {
        if (_state.value.selectedTransponder?.uuid != transponder.uuid || _state.value.currentPass?.catNum != pass.catNum) {
            calculatorTuning=null
            nativeBaseOverride=null
        }
        applyDataToneDefault(transponder)
        diagnose("tracking/start", "satellite=${pass.catNum} transponderSelected=true")
        txController?.invalidatePendingPttOn()
        val previousJob = trackingJob
        previousJob?.cancel()
        _state.update {
            it.copy(
                isActive = false,
                trackingPhase = TrackingPhase.INITIALIZING,
                currentPass = pass,
                selectedTransponder = transponder,
                txBaseFrequencyHz = txBaseFreqHz,
                txFrequencyHz = null,
                rxFrequencyHz = null,
                nominalRxFrequencyHz=null,nominalTxFrequencyHz=null,referenceDirty=false,
                calculatorLinked=calculatorTuning!=null,
                lastCommandError = null
            )
        }
        trackingJob = appScope.launch {
            try {
                withContext(NonCancellable) { previousJob?.join() }
                currentCoroutineContext().ensureActive()
                if (hasTransmitClaim) emergencyPttOff()
                val isSplit = _state.value.splitMode
                if (isSplit) runSplitTracking(transponder, txBaseFreqHz)
                else runDualRadioTracking(transponder, txBaseFreqHz)
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                failTrackingInitialization("Radio setup was interrupted")
            } catch (error: Throwable) {
                failTrackingInitialization(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    // ── Dual-radio tracking (Yaesu or two IC-705s) ──────────────────────────

    private suspend fun runDualRadioTracking(transponder: SatRadio, initialTxBaseFreqHz: Long?) {
        val tx = txController
        val rx = rxController
        if(tx?.isConnected!=true && rx?.isConnected!=true &&
            settingsRepo.radioControlSettings.value.catTransport!=RadioControlSettings.TRANSPORT_VOX) {
            return failTrackingInitialization("No CAT radio is connected")
        }

        transmitMutex.withLock {
            // Initial setup: set band/mode/CTCSS on both radios
            val txMode = controlUplinkMode(transponder)
            val rxMode = controlDownlinkMode(transponder, txMode)

            Log.i(tag, "DualRadio start: txMode=$txMode rxMode=$rxMode")

            if (settingsRepo.radioControlSettings.value.radioModel == RadioControlSettings.MODEL_ICOM_IC820) {
                val txNominal = initialTxBaseFreqHz ?: transponder.uplinkCenterFrequency()
                val rxNominal = transponder.downlinkLow
                if (tx?.isConnected == true && txNominal != null && !tx.setBand(txNominal)) {
                    return failTrackingInitialization("IC-820 TX band access was not acknowledged")
                }
                if (rx?.isConnected == true && rxNominal != null && !rx.setBand(rxNominal)) {
                    return failTrackingInitialization("IC-820 RX band access was not acknowledged")
                }
            }

            if (tx != null && tx.isConnected && txMode != null) {
                Log.d(tag, "Setting TX mode: $txMode")
                if (!tx.setMode(txMode)) return failTrackingInitialization("TX mode was not acknowledged")
            }
            if (rx != null && rx.isConnected && rxMode != null) {
                Log.d(tag, "Setting RX mode: $rxMode")
                if (!rx.setMode(rxMode)) return failTrackingInitialization("RX mode was not acknowledged")
            }
            if (tx != null && tx.isConnected) {
                val tone = _state.value.ctcssTone.takeIf { txMode.isFmMode() }
                val ctcssOk = if (tone != null) {
                    Log.d(tag, "Setting CTCSS: ${tone}Hz")
                    tx.setCtcssTone(tone) && tx.setCtcssMode(true)
                } else {
                    tx.setCtcssMode(false)
                }
                if (!ctcssOk) return failTrackingInitialization("CTCSS command was not acknowledged")
            }
            if(nativeBaseOverride==null) {
                val txBase=if(transponder.uplinkLow==null)null else initialTxBaseFreqHz ?: transponder.uplinkCenterFrequency()
                val rxBase=txBase?.let { TransponderMapper.mapUplinkToDownlink(it,transponder) } ?: transponder.downlinkLow
                val offset=if(DopplerFrequencyCalculator.isLinearTransponder(transponder))
                    DopplerFrequencyCalculator.parseOffsetHz(settingsRepo.getSatelliteOffset(_state.value.currentPass?.catNum ?: transponder.catnum ?: 0)) else 0L
                if(rxBase!=null)nativeBaseOverride=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(rxBase+offset,txBase)
            }
            _state.update {
                it.copy(
                    isActive = true,
                    trackingPhase = TrackingPhase.READY,
                    txMode = txMode,
                    rxMode = rxMode,
                    txBaseFrequencyHz = if (transponder.uplinkLow==null) null else
                        nativeBaseOverride?.txHz ?: initialTxBaseFreqHz ?: transponder.uplinkCenterFrequency()
                )
            }
            diagnose("tracking/ready", "mode=dual-radio")
        }

        runTrackingLoop(split = false)
    }

    // ── Icom single-radio duplex tracking ───────────────────────────────────

    private suspend fun runSplitTracking(transponder: SatRadio, initialTxBaseFreqHz: Long?) {
        val radio = txController
            ?: return failTrackingInitialization("TX radio is not connected")
        if (!radio.isConnected) return failTrackingInitialization("TX radio is not connected")

        val isSatelliteMode = _state.value.satelliteMode
        val addressedVfos = settingsRepo.radioControlSettings.value.let {
            it.radioModel == RadioControlSettings.MODEL_ICOM_IC705 &&
                !(it.catTransport == RadioControlSettings.TRANSPORT_TCP && it.tcpProtocol == RadioControlSettings.TCP_PROTOCOL_HAMLIB)
        }

        transmitMutex.withLock {
            if(!selectProfileContext(radio,transponder)) return failTrackingInitialization("Radio context was not acknowledged")
            val txMode = controlUplinkMode(transponder)
            val rxMode = controlDownlinkMode(transponder, txMode)
            // Compute nominal base frequencies
            val txCenter = transponder.uplinkCenterFrequency()
            val rxNominal = nativeBaseOverride?.rxHz ?: if (txCenter != null) {
                TransponderMapper.mapUplinkToDownlink(initialTxBaseFreqHz ?: txCenter, transponder)
            } else transponder.downlinkLow

            val reference=nativeBaseOverride
            val txBase = if (transponder.uplinkLow==null) null else if(reference!=null)reference.txHz else initialTxBaseFreqHz ?: txCenter
            if(radioProfile(settingsRepo.radioControlSettings.value.radioModel).isL4s && nativeBaseOverride==null && rxNominal!=null)
                nativeBaseOverride=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(rxNominal,txBase)
            else if(nativeBaseOverride==null && rxNominal!=null) {
                val offset=if(DopplerFrequencyCalculator.isLinearTransponder(transponder))
                    DopplerFrequencyCalculator.parseOffsetHz(settingsRepo.getSatelliteOffset(_state.value.currentPass?.catNum ?: transponder.catnum ?: 0)) else 0L
                nativeBaseOverride=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(rxNominal+offset,txBase)
            }
            Log.i(tag, "Icom duplex setup: txBase=${txBase}Hz rxNominal=${rxNominal}Hz txMode=$txMode rxMode=$rxMode")

            // ── Initial setup sequence ──────────────────────────────────────────
            // Explicitly select each VFO/band, then configure frequency and mode.
            // ACK from each command gates the next — no fixed delays needed.

            // Satellite-capable Icoms expose MAIN/SUB only after their dedicated mode is enabled.
            if (isSatelliteMode && !radio.setSplitMode(enabled = true)) {
                return failTrackingInitialization("Satellite mode was not acknowledged")
            }
            if (!isSatelliteMode && !radio.setSplitMode(enabled = false)) {
                return failTrackingInitialization("Split mode could not be reset before setup")
            }

            // VFO-A or MAIN = RX (downlink)
            Log.d(tag, "Duplex init: selecting RX target (VFO-A/MAIN)")
            if (!radio.setVfo(vfoA = true)) return failTrackingInitialization("RX target selection was not acknowledged")
            if (rxNominal != null) {
                Log.d(tag, "Duplex init: RX band for ${rxNominal}Hz")
                if (!radio.setBand(rxNominal)) return failTrackingInitialization("RX band was not acknowledged")
                Log.d(tag, "Duplex init: RX freq=${rxNominal}Hz")
                val applied = if (addressedVfos) radio.setWorkingFrequency(rxNominal) else radio.setFrequency(rxNominal)
                if (!applied) return failTrackingInitialization("RX frequency was not acknowledged")
            }

            // VFO-B or SUB = TX (uplink)
            Log.d(tag, "Duplex init: selecting TX target (VFO-B/SUB)")
            if (!addressedVfos && !radio.setVfo(vfoA = false)) return failTrackingInitialization("TX target selection was not acknowledged")
            if (txBase != null) {
                Log.d(tag, "Duplex init: TX band for ${txBase}Hz")
                if (!radio.setBand(txBase)) return failTrackingInitialization("TX band was not acknowledged")
                Log.d(tag, "Duplex init: TX freq=${txBase}Hz")
                val applied = if (addressedVfos) radio.setTxVfoFrequency(txBase) else radio.setFrequency(txBase)
                if (!applied) return failTrackingInitialization("TX frequency was not acknowledged")
            }
            if (!radio.setSplitModes(rxMode = rxMode, txMode = txMode)) {
                return failTrackingInitialization("Split VFO modes were not acknowledged or verified")
            }
            val splitTone = _state.value.ctcssTone.takeIf { txMode.isFmMode() }
            Log.d(tag, "Split init: TX CTCSS=${splitTone ?: "OFF"}")
            if ((!addressedVfos || txBase != null) && !configureSplitTone(radio,splitTone,txMode,transponder)) {
                return failTrackingInitialization("CTCSS command was not acknowledged")
            }

            // Enable ordinary split when dedicated satellite mode is not selected.
            Log.d(tag, "Split init: returning to VFO-A, then enabling SPLIT mode")
            if (!radio.setVfo(vfoA = true) || (!isSatelliteMode && !radio.setSplitMode(enabled = !addressedVfos || txBase != null))) {
                return failTrackingInitialization("Split mode was not acknowledged")
            }

            _state.update {
                it.copy(
                    isActive = true,
                    trackingPhase = TrackingPhase.READY,
                    txMode = txMode,
                    rxMode = rxMode,
                    txBaseFrequencyHz = txBase
                )
            }
            diagnose("tracking/ready", "mode=single-radio split=true satellite=$isSatelliteMode")
            Log.i(tag, "Icom duplex init done — entering tracking loop")

        }
        runTrackingLoop(split = true)
    }

    // A cycle and a settings change own the same lock as transmit preparation.
    // During PTT, hold both CAT legs and suppress dial-change detection.
    private suspend fun runTrackingLoop(split: Boolean) {
        var lastSetTx: Long? = null
        var lastSetRx: Long? = null
        var dialFollowState = DialFollowState()
        var revision = -1L
        var previousOffset: Long? = null
        var frozenLastCycle = false
        while (currentCoroutineContext().isActive && _state.value.isActive) {
            try {
                transmitMutex.withLock {
                    if (!hasTransmitClaim) refreshCatTransmitState()
                    val current = _state.value
                    if (!current.isActive) return@withLock
                    val pass = current.currentPass ?: return@withLock
                    val transponder = current.selectedTransponder ?: return@withLock
                    val now = clock.nowMillis()
                    if (now >= pass.losTime) {
                        if (hasTransmitClaim || current.pttState!=PttState.OFF || txController?.remoteState?.value?.txSource?.let {it!=0}==true)
                            forcePttOffLocked(null)
                        _state.update { it.copy(isActive = false, trackingPhase = TrackingPhase.IDLE,
                            lastCommandError = "Satellite pass reached LOS") }
                        return@withLock
                    }
                    val cycleRevision = tuningRevision.get()
                    val isLinear = DopplerFrequencyCalculator.isLinearTransponder(transponder)
                    val controlSettings = settingsRepo.radioControlSettings.value
                    val dialDeadband = if (isLinear) {
                        controlSettings.linearDialDeadbandHz
                    } else {
                        controlSettings.fmDialDeadbandHz
                    }
                    val offset = if (isLinear) {
                        DopplerFrequencyCalculator.parseOffsetHz(settingsRepo.getSatelliteOffset(pass.catNum))
                    } else 0L
                    if (previousOffset!=null && previousOffset!=offset && calculatorTuning==null &&
                        !radioProfile(controlSettings.radioModel).isL4s) {
                        nativeBaseOverride=nativeBaseOverride?.let { it.copy(rxHz=Math.addExact(it.rxHz,offset-previousOffset!!)) }
                    }
                    if (revision != cycleRevision || previousOffset != offset) {
                        dialFollowState = DialFollowState()
                        lastSetTx = null
                        lastSetRx = null
                        revision = cycleRevision
                        previousOffset = offset
                    }
                    val position = satelliteRepo.getPosition(pass.orbitalObject, settingsRepo.stationPosition.value, now)
                    val tx = txController
                    val rx = if (split) tx else rxController
                    if (tx?.remoteState?.value?.capabilities?.trackingDisplay==true) {
                        tx.updateTrackingDisplay(com.rtbishop.look4sat.core.domain.repository.RadioTrackingDisplay(
                            pass.name,Math.toDegrees(position.azimuth),Math.toDegrees(position.elevation),
                            tx.remoteState?.value?.let { profileKey(it,transponder) } ?: radioContextKey(transponder.uuid)))
                    }
                    // Keep BOTH RX and TX fixed during all transmission, including CW gaps.
                    val frozen = hasTransmitClaim || !current.catTransmitStateKnown || current.catTransmitting ||
                        current.pttState != PttState.OFF || tx?.remoteState?.value?.txSource?.let { it!=0 }==true ||
                        tx?.remoteState?.value?.nativeMenuOpen==true || tx?.remoteState?.value?.transmitStateKnown==false ||
                        tx?.remoteState?.value?.profileStore?.busy==true
                    if (frozenLastCycle && !frozen) {
                        lastSetTx = null
                        lastSetRx = null
                    }
                    frozenLastCycle = frozen
                    var base = current.txBaseFrequencyHz
                    val calculated = calculatorTuning?.frequencies(transponder, position, offset)
                    if (calculated != null) base = calculated.nominalTxHz
                    var observedTx = current.txFrequencyHz
                    var observedRx = current.rxFrequencyHz
                    suspend fun readTx() = if (split) tx?.readTxVfoFrequency() else tx?.readFrequencyAndMode()?.first
                    suspend fun readRx() = if (split) rx?.readWorkingFrequency() else rx?.readFrequencyAndMode()?.first
                    // Do not change the nominal pair under a waveform, even from the RX dial.
                    if (!frozen && calculated == null && tx?.remoteState==null) {
                        suspend fun sampleDial(leg: DialLeg): Boolean {
                            val controller = if (leg == DialLeg.TX) tx else rx
                            val commanded = if (leg == DialLeg.TX) lastSetTx else lastSetRx
                            if (controller?.isConnected != true || commanded == null || base == null) return false
                            val frequency = (if (leg == DialLeg.TX) readTx() else readRx())
                                ?: return dialFollowState.activeLeg != null
                            if (leg == DialLeg.TX) observedTx = frequency else observedRx = frequency
                            val result = DialFollowPolicy.update(
                                state = dialFollowState,
                                leg = leg,
                                observedFrequencyHz = frequency,
                                commandedFrequencyHz = commanded,
                                nowMillis = now,
                                deadbandHz = dialDeadband,
                                settleMillis = controlSettings.dialSettleMillis
                            )
                            dialFollowState = result.state
                            result.acceptedFrequencyHz?.let { acceptedFrequency ->
                                val velocity = position.distanceRate * 1000.0
                                val candidate = if (leg == DialLeg.TX) {
                                    (acceptedFrequency.toDouble() * SPEED_OF_LIGHT /
                                        (SPEED_OF_LIGHT + velocity)).toLong()
                                } else {
                                    (acceptedFrequency.toDouble() * SPEED_OF_LIGHT /
                                        (SPEED_OF_LIGHT - velocity)).toLong()
                                }
                                val nominalLeg = if(leg==DialLeg.TX) current.nominalTxFrequencyHz else current.nominalRxFrequencyHz
                                if (candidate > 0L && nominalLeg!=null && kotlin.math.abs(candidate-nominalLeg)<=1_000_000L) {
                                    val rxReference=nativeBaseOverride?.rxHz ?: current.nominalRxFrequencyHz
                                    if(rxReference!=null) {
                                        nativeBaseOverride=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(
                                            if(leg==DialLeg.RX) candidate else rxReference,
                                            if(leg==DialLeg.TX) candidate else base)
                                        if(leg==DialLeg.TX) base=candidate
                                    }
                                }
                                lastSetTx = null
                                lastSetRx = null
                                return true
                            }
                            return dialFollowState.activeLeg != null
                        }
                        val activeLeg = dialFollowState.activeLeg
                        if (activeLeg != null) {
                            sampleDial(activeLeg)
                        } else if (!sampleDial(DialLeg.TX)) {
                            sampleDial(DialLeg.RX)
                        }
                    } else {
                        dialFollowState = DialFollowState()
                    }
                    // A synchronous UI intent can invalidate this snapshot while a read suspends.
                    if (cycleRevision != tuningRevision.get() || !_state.value.isActive) return@withLock
                    val nominalRx = calculated?.nominalRxHz ?: nativeBaseOverride?.rxHz ?: (transponder.uplinkCenterFrequency()?.let { TransponderMapper.mapUplinkToDownlink(it, transponder) }
                        ?: transponder.downlinkLow)?.plus(offset)
                    val desiredTx = calculated?.txHz ?: base?.let(position::getUplinkFreq)
                    val desiredRx = calculated?.rxHz ?: nominalRx?.let(position::getDownlinkFreq)
                    // The FT4 lease preloads the slot-midpoint frequency. Hold both CAT legs
                    // until the waveform ends so no synthesizer step occurs under modulation.
                    if (!frozen && dialFollowState.activeLeg == null) {
                        if(nominalRx!=null && tx?.remoteState?.value?.capabilities?.satelliteProfiles==true &&
                            !tx.updateTrackingBases(com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(nominalRx,base))) {
                            val latest=tx.remoteState?.value
                            if(latest?.nativeSettingsModified==true) {
                                nativeBaseOverride=latest.nativeBaseFrequencies;calculatorTuning=null
                                latest.nativeBaseFrequencies?.let { saved -> _state.update { it.copy(txBaseFrequencyHz=saved.txHz) } }
                            }
                            tuningRevision.incrementAndGet();return@withLock
                        }
                        val txWriteAllowed = current.pttState == PttState.OFF
                        var wroteTx = false
                        if (split && tx?.supportsAtomicPair == true && desiredRx != null) {
                            when (tx.setTrackingFrequencies(desiredRx, desiredTx)) {
                                FrequencyPairResult.APPLIED -> {
                                    observedTx = tx.remoteState?.value?.txFrequencyHz
                                    observedRx = tx.remoteState?.value?.rxFrequencyHz
                                    lastSetTx = observedTx; lastSetRx = observedRx
                                }
                                FrequencyPairResult.HELD -> Unit
                                else -> failTrackingCommand("Atomic RX/TX frequency pair was not applied")
                            }
                        } else {
                        if (txWriteAllowed && tx?.isConnected == true && desiredTx != null &&
                            (controlSettings.radioModel != RadioControlSettings.MODEL_ICOM_IC705 || desiredTx != lastSetTx)) {
                            val ok = if (split) tx.setTxVfoFrequency(desiredTx) else tx.setFrequency(desiredTx)
                            if (ok) { lastSetTx = desiredTx; observedTx = desiredTx; wroteTx = true }
                            else failTrackingCommand("TX frequency was not acknowledged")
                        }
                        if (rx?.isConnected == true && desiredRx != null &&
                            (controlSettings.radioModel != RadioControlSettings.MODEL_ICOM_IC705 || desiredRx != lastSetRx)) {
                            if (split && wroteTx && controlSettings.sharedBusCommandDelayMillis > 0L) {
                                delay(controlSettings.sharedBusCommandDelayMillis)
                            }
                            val ok = if (split) rx.setWorkingFrequency(desiredRx) else rx.setFrequency(desiredRx)
                            if (ok) { lastSetRx = desiredRx; observedRx = desiredRx }
                            else failTrackingCommand("RX frequency was not acknowledged")
                        }
                        }
                    }
                    if (cycleRevision != tuningRevision.get()) return@withLock
                    _state.update {
                        it.copy(
                            txConnected = tx?.isConnected == true,
                            rxConnected = rx?.isConnected == true,
                            txBaseFrequencyHz = base,
                            txFrequencyHz = observedTx,
                            rxFrequencyHz = observedRx,
                            targetTxFrequencyHz = trackingTarget(tx,desiredTx,false),
                            targetRxFrequencyHz = trackingTarget(tx,desiredRx,true),
                            nominalTxFrequencyHz = base,
                            nominalRxFrequencyHz = nominalRx,
                            calculatorLinked=calculatorTuning!=null,
                            txDopplerCorrectionHz = dopplerCorrection(if(tx?.remoteState!=null)desiredTx else observedTx, base),
                            rxDopplerCorrectionHz = dopplerCorrection(if(tx?.remoteState!=null)desiredRx else observedRx, nominalRx),
                            azimuth = Math.toDegrees(position.azimuth),
                            elevation = Math.toDegrees(position.elevation),
                            distance = position.distance
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                // Preserve tracking when a transport cancels only its current transaction.
                currentCoroutineContext().ensureActive()
                tuningRevision.incrementAndGet()
            }
            withTimeoutOrNull(1000L) { tuningWake.receive() }
        }
    }

    // ── Other IRadioTrackingService methods ─────────────────────────────────

    override fun stopTracking() {
        txController?.invalidatePendingPttOn()
        trackingJob?.cancel()
        _state.update { it.copy(isActive = false, trackingPhase = TrackingPhase.IDLE) }
        appScope.launch {
            if (hasTransmitClaim) emergencyPttOff()
        }
    }

    override fun setTransponder(transponder: SatRadio) {
        changeRadioSettings {
            configureTransponder(transponder)
        }
    }

    private suspend fun configureTransponder(transponder: SatRadio) {
            applyDataToneDefault(transponder)
            val tx = txController
            val rx = rxController
            // Selecting a transponder is also local UI state. Allow it before a
            // radio is connected; startTracking will program the selected mode.
            if (tx?.isConnected != true && rx?.isConnected != true) {
                updateSelectedTransponder(transponder, controlDownlinkMode(transponder,controlUplinkMode(transponder)))
                return
            }
            val split = _state.value.splitMode
            if (tx?.isConnected==true && !selectProfileContext(tx,transponder)) {
                failTrackingCommand("Radio context was not acknowledged");return
            }
            val txMode=controlUplinkMode(transponder)
            val rxMode=controlDownlinkMode(transponder,txMode)
            val txModeOk: Boolean
            val rxModeOk: Boolean
            if (split) {
                val modesOk = tx?.setSplitModes(rxMode, txMode) ?: false
                txModeOk = modesOk
                rxModeOk = modesOk
            } else {
                txModeOk = txMode?.let { tx?.setMode(it) } ?: true
                rxModeOk = rxMode?.let { mode -> rx?.setMode(mode) } ?: true
            }
            val tone = _state.value.ctcssTone.takeIf { txMode.isFmMode() }
            val ctcssOk = if (split) {
                tx != null && configureSplitTone(tx,tone,txMode,transponder)
            } else if (tx == null || !tx.isConnected) {
                true
            } else if (tone != null) {
                tx.setCtcssTone(tone) && tx.setCtcssMode(true)
            } else {
                tx.setCtcssMode(false)
            }
            if (!ctcssOk) {
                return failTrackingCommand("CTCSS command was not acknowledged")
            }
            if (txModeOk != false && rxModeOk != false) updateSelectedTransponder(transponder, rxMode)
            else failTrackingCommand("Radio mode command was not acknowledged")
    }

    override fun tuneFromCalculator(transponder: SatRadio, tuning: CalculatorTuning) {
        if(userFrequencyEditingBlocked()) {failTrackingCommand("Frequency editing is disabled during transmission");return}
        if (tuning.transponderUuid != transponder.uuid || tuning.frequencyHz <= 0 ||
            !DopplerFrequencyCalculator.isLinearTransponder(transponder)) return
        if (_state.value.isActive && _state.value.currentPass?.catNum != transponder.catnum) {
            failTrackingCommand("Start tracking the calculator satellite before tuning its radio")
            return
        }
        fun accept() {
            nativeBaseOverride=null
            calculatorTuning = tuning
            _state.update {it.copy(calculatorLinked=true,referenceDirty=true)}
            tuningRevision.incrementAndGet()
            tuningWake.trySend(Unit)
            diagnose("calculator/tune", "anchor=${tuning.anchor} frequencyHz=${tuning.frequencyHz}")
        }
        if (_state.value.selectedTransponder?.uuid == transponder.uuid) {
            // Only update the desired anchor; the normal tracking loop owns all CAT writes.
            // All models reject edits during a transmit claim; nothing is deferred.
            changeRadioSettings {accept()}
        } else changeRadioSettings {
            configureTransponder(transponder)
            if (_state.value.selectedTransponder?.uuid == transponder.uuid) accept()
        }
    }

    private fun userFrequencyEditingBlocked():Boolean {
        val current=_state.value
        val remote=txController?.remoteState?.value ?: current.remoteRadio
        return current.frequencyEditingLocked || hasTransmitClaim || manualJob?.isActive==true || remote?.txSource?.let {it!=0}==true ||
            remote?.nativeMenuOpen==true || remote?.profileStore?.busy==true || remote?.let { !it.connected || !it.transmitStateKnown }==true
    }

    private suspend fun refreshCatTransmitState(): Boolean {
        val settings=settingsRepo.radioControlSettings.value
        val radios=listOfNotNull(txController?.takeIf { settings.txRadioAddress.isNotBlank() },
            rxController?.takeIf { !_state.value.splitMode && settings.rxRadioAddress.isNotBlank() })
            .distinct().filter { it.supportsPttReadback }
        if(radios.isEmpty())return true
        val statuses=radios.map { radio ->
            try { if(radio.isConnected) radio.readPttState() else null }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) {
                diagnose("transmit/status",error.message ?: "PTT readback failed",ControlDiagnosticSeverity.ERROR)
                null
            }
        }
        val known=statuses.all {it!=null}
        val transmitting=statuses.any {it==true}
        val unknownMessage="CAT transmit state is unknown; tuning is held"
        _state.update { it.copy(catTransmitStateKnown=known,catTransmitting=transmitting,
            errorMessage=if(!known) unknownMessage else it.errorMessage.takeUnless {message->message==unknownMessage},
            lastCommandError=if(!known) unknownMessage else it.lastCommandError.takeUnless {message->message==unknownMessage}) }
        return known && !transmitting
    }
    private suspend fun authorizeFrequencyEdit(correctionStep:Long=settingsRepo.radioControlSettings.value.l4sCorrectionEditStepHz):Boolean {
        if(!radioProfile(settingsRepo.radioControlSettings.value.radioModel).isL4s)return refreshCatTransmitState()
        val radio=txController ?: return true
        if(!radio.isConnected)return false
        val acknowledged=if(radio.remoteState?.value?.capabilities?.transactionalProfileStore==true) {
            val data=ByteArray(3);data[0]=7;L4sProtocol.put16(data,1,correctionStep.coerceIn(10,1000).toInt()/10*10)
            radio.profileStoreCommand(data)!=null
        } else radio.readWorkingFrequency()!=null
        return acknowledged && !userFrequencyEditingBlocked()
    }

    private fun changeRadioSettings(block: suspend () -> Unit) {
        if(userFrequencyEditingBlocked()) {failTrackingCommand("Frequency editing is disabled during transmission");return}
        val editEpoch=manualEpoch.get()
        pendingControls.incrementAndGet()
        tuningRevision.incrementAndGet()
        txController?.invalidatePendingPttOn()
        appScope.launch {
            try {
                transmitMutex.withLock {
                    if(userFrequencyEditingBlocked() || editEpoch!=manualEpoch.get()) {
                        failTrackingCommand("Frequency editing is disabled during transmission");return@withLock
                    }
                    if(!authorizeFrequencyEdit() || userFrequencyEditingBlocked() ||
                        editEpoch!=manualEpoch.get()) {
                        failTrackingCommand("Radio rejected frequency editing; no change was queued");return@withLock
                    }
                    if (_state.value.pttState != PttState.OFF) {
                        failTrackingCommand("PTT OFF must be confirmed before changing radio settings")
                        return@withLock
                    }
                    block()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failTrackingCommand(error.message ?: "Radio settings update failed")
            } finally {
                tuningRevision.incrementAndGet()
                pendingControls.decrementAndGet()
            }
        }
    }

    override fun setTxBaseFrequency(frequencyHz: Long) {
        if (frequencyHz <= 0L || _state.value.selectedTransponder?.uplinkLow==null) return
        changeRadioSettings {
            calculatorTuning = null
            nativeBaseOverride=independentBases(frequencyHz)
            _state.update { it.copy(txBaseFrequencyHz = frequencyHz,nominalTxFrequencyHz=frequencyHz,calculatorLinked=false,referenceDirty=true) }
            tuningWake.trySend(Unit)
        }
    }

    override fun adjustTxBaseFrequency(deltaHz: Long) {
        changeRadioSettings {
            val base = _state.value.txBaseFrequencyHz ?: return@changeRadioSettings
            val adjusted = Math.addExact(base, deltaHz)
            if (adjusted > 0L) {nativeBaseOverride=independentBases(adjusted);calculatorTuning=null;_state.update { it.copy(txBaseFrequencyHz = adjusted,nominalTxFrequencyHz=adjusted,calculatorLinked=false,referenceDirty=true) };tuningWake.trySend(Unit)}
        }
    }

    private fun independentBases(txHz:Long?):com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies? {
        val current=_state.value
        val rx=nativeBaseOverride?.rxHz ?: current.nominalRxFrequencyHz ?: current.selectedTransponder?.downlinkLow ?: return null
        return com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(rx,txHz)
    }
    override fun setRxBaseFrequency(frequencyHz:Long) {
        if(frequencyHz<=0) return
        changeRadioSettings {
            nativeBaseOverride=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(frequencyHz,_state.value.txBaseFrequencyHz)
            _state.update {it.copy(nominalRxFrequencyHz=frequencyHz,referenceDirty=true,calculatorLinked=false)}
            calculatorTuning=null;tuningRevision.incrementAndGet();tuningWake.trySend(Unit)
        }
    }

    override fun setBaseFrequencies(bases:com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies) {
        if(bases.rxHz !in 1L..9_999_999_999L || bases.txHz?.let {it !in 1L..9_999_999_999L}==true ||
            bases.txHz!=null && _state.value.selectedTransponder?.uplinkLow==null)return
        changeRadioSettings {
            nativeBaseOverride=bases;calculatorTuning=null
            _state.update {it.copy(txBaseFrequencyHz=bases.txHz,nominalTxFrequencyHz=bases.txHz,nominalRxFrequencyHz=bases.rxHz,calculatorLinked=false,referenceDirty=true)}
            tuningWake.trySend(Unit)
        }
    }

    override fun setCtcssTone(toneHz: Double?) {
        changeRadioSettings {
            ctcssExplicit=true
            val tx = txController
            val acknowledged = if (_state.value.splitMode) {
                tx != null && tx.configureTxCtcss(toneHz)
            } else if (toneHz != null) {
                tx?.setCtcssTone(toneHz) == true && tx.setCtcssMode(true)
            } else {
                tx?.setCtcssMode(false) == true
            }
            if (acknowledged) _state.update { it.copy(ctcssTone = toneHz,referenceDirty=true) }
            else failTrackingCommand("CTCSS command was not acknowledged")
        }
    }

    override fun setMode(txMode: String, rxMode: String) {
        changeRadioSettings {
            val split = _state.value.splitMode
            val txOk = if (split) {
                txController?.setSplitModes(rxMode, txMode) ?: false
            } else {
                txController?.setMode(txMode) ?: true
            }
            val rxOk = if (split) true else rxController?.setMode(rxMode) ?: true
            if (txOk && rxOk) _state.update { it.copy(txMode = txMode, rxMode = rxMode,referenceDirty=true) }
            else failTrackingCommand("Radio mode command was not acknowledged")
        }
    }

    private fun updateSelectedTransponder(transponder: SatRadio, rxMode: String?) {
        calculatorTuning = null
        nativeBaseOverride=null
        val txCenter = transponder.uplinkCenterFrequency()
        val rxNominal = if (txCenter != null) TransponderMapper.mapUplinkToDownlink(txCenter, transponder)
        else transponder.downlinkLow
        _state.update {
            it.copy(
                selectedTransponder = transponder,
                txBaseFrequencyHz = txCenter,
                nominalTxFrequencyHz = txCenter,
                nominalRxFrequencyHz = rxNominal,
                txMode = controlUplinkMode(transponder),
                rxMode = rxMode
            )
        }
        restoreNativeProfile(transponder)
    }

    private fun applyDataToneDefault(transponder: SatRadio) {
        val current=_state.value
        if(current.selectedTransponder?.uuid==transponder.uuid || current.selectedTransponder==null && ctcssExplicit) return
        val saved=txController?.remoteState?.value?.takeIf { it.nativeProfileValid && it.nativeProfileKey==profileKey(it,transponder) }
        val tone=if(saved!=null) saved.nativeSavedTxCtcssHz else transponder.defaultTxCtcssHz()
        ctcssExplicit=saved!=null || tone!=null
        _state.update { it.copy(ctcssTone=tone) }
    }

    private suspend fun configureSplitTone(radio: IRadioController,tone: Double?,mode: String?,transponder:SatRadio): Boolean {
        // New firmware restores the saved native profile on MODE. Missing metadata must not erase it.
        if(mode.isFmMode() && radio.remoteState?.value?.capabilities?.nativeSettings==true && !ctcssExplicit) {
            val saved=radio.remoteState!!.value
            if(!saved.nativeProfileValid || saved.nativeProfileKey=="0".repeat(32) || saved.nativeProfileKey==profileKey(saved,transponder)) return true
            return radio.configureTxCtcss(null)
        }
        return radio.configureTxCtcss(tone)
    }
    private fun nativeProfileMode(transponder: SatRadio): Int? = txController?.remoteState?.value
        ?.takeIf { it.nativeProfileValid && it.nativeProfileKey==profileKey(it,transponder) }?.nativeSavedMode

    private fun profileKey(remote: com.rtbishop.look4sat.core.domain.repository.RemoteRadioState,transponder:SatRadio):String {
        val pass=_state.value.currentPass
        return if(remote.capabilities?.satelliteProfiles==true && pass!=null && pass.catNum==transponder.catnum)
            com.rtbishop.look4sat.core.domain.repository.radioSatelliteProfileKey(pass.name,transponder.uuid)
        else radioContextKey(transponder.uuid)
    }

    private suspend fun selectProfileContext(radio:IRadioController,transponder:SatRadio):Boolean {
        val pass=_state.value.currentPass
        val ok=if(pass!=null && pass.catNum==transponder.catnum) radio.selectSatelliteProfile(pass.name,transponder.uuid)
            else radio.prepareTrackingContext(radioContextKey(transponder.uuid))
        if(ok) {
            if(pass!=null && pass.catNum==transponder.catnum) {
                val key=com.rtbishop.look4sat.core.domain.repository.radioSatelliteProfileKey(pass.name,transponder.uuid)
                settingsRepo.setRadioProfileMetadata(settingsRepo.getRadioProfileMetadata()+(key to
                    com.rtbishop.look4sat.core.domain.repository.RadioProfileMetadata(pass.name,transponder.uuid,transponder.info)))
            }
            restoreNativeProfile(transponder)
        }
        return ok
    }
    private fun restoreNativeProfile(transponder:SatRadio) {
        val remote=txController?.remoteState?.value ?: return
        if(!remote.nativeProfileValid || remote.nativeProfileKey!=profileKey(remote,transponder)) {nativeBaseOverride=null;return}
        nativeBaseOverride=remote.nativeBaseFrequencies
        ctcssExplicit=true
        _state.update { it.copy(ctcssTone=remote.nativeSavedTxCtcssHz,
            txBaseFrequencyHz=if(remote.nativeBaseFrequencies!=null)remote.nativeBaseFrequencies?.txHz else it.txBaseFrequencyHz,
            nominalRxFrequencyHz=remote.nativeBaseFrequencies?.rxHz ?: it.nominalRxFrequencyHz,
            calculatorLinked=false,referenceDirty=false) }
    }
    private fun trackingTarget(radio:IRadioController?,hz:Long?,rx:Boolean):Long? {
        if(hz==null) return null
        val remote=radio?.remoteState?.value ?: return hz
        if(remote.capabilities?.atomicPair!=true) return hz
        return com.rtbishop.look4sat.core.domain.repository.l4sTrackingTarget(hz,
            if(rx) remote.nativeRxCorrectionHz else remote.nativeTxCorrectionHz,settingsRepo.radioControlSettings.value.l4sDopplerStepHz)
    }

    private fun failTrackingCommand(message: String) {
        diagnose("tracking/command", message, ControlDiagnosticSeverity.ERROR)
        _state.update { it.copy(lastCommandError = message, errorMessage = message) }
    }

    private fun failTrackingInitialization(message: String) {
        diagnose("tracking/initialization", message, ControlDiagnosticSeverity.ERROR)
        _state.update {
            it.copy(
                isActive = false,
                trackingPhase = TrackingPhase.ERROR,
                lastCommandError = message,
                errorMessage = message
            )
        }
    }

    private fun diagnose(
        stage: String,
        message: String,
        severity: ControlDiagnosticSeverity = ControlDiagnosticSeverity.INFO
    ) {
        diagnostics.record(ControlDiagnosticSource.RADIO, stage, message, severity)
    }

    private companion object {
        const val PTT_COMMAND_TIMEOUT_MILLIS = 2_500L
        const val MAX_WAVEFORM_MILLIS = 6_000L
        const val MAX_PTT_MILLIS = 12_000L
        const val MAX_AUTOMATIC_START_LATENESS_MILLIS = 150L
    }
}

private fun SatRadio.resolvedUplinkMode(): String? =
    uplinkMode?.uppercase() ?: downlinkMode?.let {
        TransponderMapper.mapUplinkModeToDownlinkMode(it.uppercase(), isInverted)
    }

private fun String?.isFmMode(): Boolean = this?.uppercase() in setOf("FM", "FMN", "FM-N", "NFM")
private fun normalizeFmMode(mode:String?):String? = if(mode in setOf("FMN","FM-N")) "NFM" else mode

private fun SatRadio.uplinkCenterFrequency(): Long? {
    val low = uplinkLow
    val high = uplinkHigh
    return when {
        low != null && high != null -> (low + high) / 2L
        low != null -> low
        else -> null
    }
}

private fun dopplerCorrection(corrected: Long?, nominal: Long?): Long? =
    if (corrected != null && nominal != null) corrected - nominal else null

internal class RadioCommandLatencyEstimator(
    private val capacity: Int = 32,
    private val fallbackMillis: Long = 350L
) {
    private val samples = ArrayDeque<Long>()

    @Synchronized
    fun record(valueMillis: Long) {
        samples.addLast(valueMillis.coerceAtLeast(0L))
        while (samples.size > capacity) samples.removeFirst()
    }

    @Synchronized
    fun p95WithMargin(): Long {
        if (samples.size < 4) return fallbackMillis
        val sorted = samples.sorted()
        val index = kotlin.math.ceil(sorted.size * 0.95).toInt().coerceIn(1, sorted.size) - 1
        return (sorted[index] + 100L).coerceIn(250L, 1_500L)
    }
}
