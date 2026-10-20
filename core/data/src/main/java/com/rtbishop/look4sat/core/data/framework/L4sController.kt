package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.CwTransmitText
import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioState
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class L4sController(
    private val transport: RadioTransport,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics,
    private val tuningStepHz: () -> Long = {250L},
    private val correctionStepHz:()->Long={10L},
    private val newSession: () -> Long = { (SecureRandom().nextInt().toLong() and 0xffffffffL).coerceAtLeast(1) }
) : IRadioController {
    private val state=MutableStateFlow(RemoteRadioState())
    override val remoteState: StateFlow<RemoteRadioState> = state
    override val supportsAtomicPair = true
    @Volatile override var isConnected = false
        private set
    private val ioMutex=Mutex()
    private var decoder=L4sProtocol.Decoder()
    private var session=0L
    private var sequence=0
    private var revision=0L
    private var rawTargets: com.rtbishop.look4sat.core.domain.repository.RawTrackingTargets?=null
    private var selectedRx=true
    private var armed=false
    private var requestedPair: com.rtbishop.look4sat.core.domain.repository.AppliedRadioFrequencies?=null
    private var toneTenths=0
    private var heartbeat: Job?=null

    override suspend fun connect(): Boolean = withContext(dispatcher) {
        if(isConnected) return@withContext true
        transport.setCdcDtrOnConnect(true)
        diagnose("l4s/transport", "Opening native CDC with DTR=1 RTS=0")
        if(!transport.connect()) {
            state.update { it.copy(connected=false,error="Could not open USB CDC; check device selection and USB permission") }
            diagnose("l4s/transport",state.value.error!!,ControlDiagnosticSeverity.ERROR)
            return@withContext false
        }
        try {
            decoder=L4sProtocol.Decoder();sequence=0;revision=0;armed=false;requestedPair=null
            session=newSession() and 0xffffffffL
            require(session!=0L)
            val hello=request(L4sProtocol.HELLO, wireSession=0) ?: return@withContext failedConnect()
            val (firmware,caps)=L4sProtocol.capabilities(hello.data) ?: return@withContext failedConnect()
            if(hello.result!=0) return@withContext failedConnect()
            state.value=RemoteRadioState(firmware=firmware,capabilities=caps,transmitStateKnown=false)
            diagnose("l4s/hello","firmware=$firmware capabilities=${hello.data.size} bytes")
            if(!ok(request(L4sProtocol.OPEN))) return@withContext failedConnect()
            if(caps.satelliteProfiles && !ok(request(L4sProtocol.PROFILE,byteArrayOf(2)))) return@withContext failedConnect()
            if(!caps.satelliteProfiles && caps.nativeSettings && !ok(request(L4sProtocol.SETTINGS))) return@withContext failedConnect()
            rawTargets=null; selectedRx=true
            isConnected=true;state.update { it.copy(connected=true,error=null) }
            diagnose("l4s/open","session opened; TX remains off")
            heartbeat=scope.launch(dispatcher) {
                try {
                    var polls=0
                    while(isConnected) {
                        delay(400)
                        if(!ok(request(L4sProtocol.KEEPALIVE))) {
                            isConnected=false
                            state.update { it.copy(connected=false,error=it.error ?: "L4S heartbeat lost; reconnect") }
                            transport.disconnect()
                        }
                        if(isConnected && caps.nativeSettings && ++polls%2==0) {
                            if(caps.satelliteProfiles) request(L4sProtocol.PROFILE,byteArrayOf(2)) else request(L4sProtocol.SETTINGS)
                            if(caps.transactionalProfileStore) request(L4sProtocol.PROFILE_STORE,byteArrayOf(0))
                        }
                        if(isConnected && caps.cwOptions && polls%5==0) request(L4sProtocol.CW_OPTIONS,byteArrayOf(0))
                        if(isConnected && caps.handsetMacros) request(L4sProtocol.MACROS,byteArrayOf(0,-1))
                        if(isConnected && caps.nativeDiagnostics && polls%15==0) request(L4sProtocol.DIAGNOSTICS)
                    }
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) {
                    isConnected=false
                    state.update { it.copy(connected=false,error=error.message ?: "L4S connection lost") }
                    runCatching { transport.disconnect() }
                }
            }
            true
        } catch(cancelled: CancellationException) { failedConnect();throw cancelled }
        catch(error: Exception) { state.update { it.copy(error=error.message) };failedConnect() }
    }

    private suspend fun failedConnect(): Boolean {
        transport.disconnect();isConnected=false
        state.update { it.copy(connected=false,error=it.error ?: "No compatible L4S v1 firmware") }
        return false
    }

    override suspend fun disconnect() = withContext(dispatcher+NonCancellable) {
        heartbeat?.cancel();heartbeat=null
        if(transport.isConnected) {
            request(L4sProtocol.STOP)
            request(L4sProtocol.CLOSE)
        }
        transport.disconnect();isConnected=false;armed=false;requestedPair=null;rawTargets=null
        state.update { it.copy(connected=false) }
    }

    private suspend fun request(command: Int, data: ByteArray=byteArrayOf(), wireSession: Long=session): L4sProtocol.Reply? =
        ioMutex.withLock {
            if(!transport.isConnected) return@withLock null
            if(!isConnected && command !in setOf(L4sProtocol.HELLO,L4sProtocol.OPEN,L4sProtocol.STOP,L4sProtocol.CLOSE,L4sProtocol.SETTINGS,L4sProtocol.PROFILE)) return@withLock null
            val seq=sequence.also { sequence=(sequence+1) and 0xffff }
            val wire=L4sProtocol.frame(L4sProtocol.body(command,seq,wireSession,data))
            var receivedBytes=0
            val label=when(command) { L4sProtocol.HELLO -> "HELLO"; L4sProtocol.OPEN -> "OPEN"; else -> "0x${command.toString(16)}" }
            repeat(2) { attempt ->
                if(command!=L4sProtocol.KEEPALIVE) diagnose("l4s/request","cmd=$label seq=$seq attempt=${attempt+1} bytes=${wire.size}")
                if(!transport.write(wire)) {
                    val detail=transport.lastIoFailure ?: "no transport detail"
                    diagnose("l4s/write-retry","cmd=$label seq=$seq $detail",ControlDiagnosticSeverity.WARNING)
                    delay(25)
                    if(!transport.write(wire)) {
                        val error="L4S $label USB write failed: ${transport.lastIoFailure ?: detail}"
                        isConnected=false;armed=false;requestedPair=null
                        state.update { it.copy(connected=false,error=error) }
                        diagnose("l4s/write",error,ControlDiagnosticSeverity.ERROR)
                        transport.disconnect() // Drop DTR; firmware's watchdog remains the independent fallback.
                        return@withLock null
                    }
                }
                val response=withTimeoutOrNull(if(command in setOf(L4sProtocol.PROFILE_STORE,L4sProtocol.MACROS))250L else 600L) {
                    var match: L4sProtocol.Reply?=null
                    while(match==null) {
                        val chunk=transport.readAvailable(1024)
                        receivedBytes+=chunk.size
                        decoder.feed(chunk).forEach { plain ->
                            val reply=L4sProtocol.reply(plain)
                            if(reply!=null && reply.command==command && reply.sequence==seq &&
                                (reply.session==wireSession || command==L4sProtocol.HELLO ||
                                    reply.session==0L && (reply.result==3 || command==L4sProtocol.CLOSE))) match=reply
                        }
                        if(match==null) delay(5)
                    }
                    match
                }
                if(response!=null) {
                    if(command!=L4sProtocol.KEEPALIVE) diagnose("l4s/reply","cmd=$label seq=$seq result=${response.result} rxBytes=$receivedBytes")
                    if(command!=L4sProtocol.HELLO) {
                        if(command in setOf(L4sProtocol.CW_OPTIONS,L4sProtocol.MACROS,L4sProtocol.DIAGNOSTICS) && response.result<=1) {
                            val valid=when(command) {
                                L4sProtocol.CW_OPTIONS -> L4sProtocol.cwOptions(response.data)?.also { options ->
                                    state.update {it.copy(nativeCwOptions=options,nativeCwWpm=options.wpm)}
                                }!=null
                                L4sProtocol.MACROS -> L4sProtocol.macroStatus(response.data)?.also { status ->
                                    if(state.value.handsetMacroStatus?.generation?.let {it!=status.generation}==true || status.busy) {armed=false;requestedPair=null}
                                    state.update {it.copy(handsetMacroStatus=status)}
                                }!=null
                                else -> L4sProtocol.diagnostics(response.data)?.also { counters ->
                                    state.update {it.copy(nativeDiagnostics=counters)}
                                    diagnose("l4s/native-diagnostics",counters.summary())
                                }!=null
                            }
                            if(!valid) {state.update {it.copy(error="Malformed L4S extended reply")};return@withLock null}
                            return@withLock response
                        }
                        if(command==L4sProtocol.PROFILE_STORE && response.result<=1) {
                            val store=L4sProtocol.storeStatus(response.data)
                            if(store==null){state.update {it.copy(error="Malformed L4S profile store reply",transmitStateKnown=false)};return@withLock null}
                            if(state.value.profileStore?.generation?.let {it!=store.generation}==true){armed=false;requestedPair=null}
                            if(data.firstOrNull()==7.toByte()){armed=false;requestedPair=null}
                            state.update {it.copy(profileStore=store)}
                            return@withLock response
                        }
                        val snapshot=when(command) {
                            L4sProtocol.PROFILE->L4sProtocol.profileSnapshot(response,state.value)
                            L4sProtocol.SETTINGS->L4sProtocol.settingsSnapshot(response,state.value)
                            else->L4sProtocol.snapshot(response,state.value)
                        }
                        if(snapshot==null) { state.update { old -> old.copy(error="Malformed L4S state reply",transmitStateKnown=false) };return@withLock null }
                        if(snapshot.nativeProfileGeneration!=state.value.nativeProfileGeneration) {armed=false;requestedPair=null;rawTargets=null}
                        if (state.value.txSource!=0 && snapshot.txSource==0) { armed=false;requestedPair=null }
                        if(response.result==3) {
                            isConnected=false;armed=false;requestedPair=null
                            state.value=snapshot.copy(connected=false,error=L4sProtocol.error(3))
                        } else state.value=snapshot
                    }
                    if(response.result>1) state.update { old -> old.copy(error=L4sProtocol.error(response.result)) }
                    return@withLock response
                }
            }
            val hint=if(command==L4sProtocol.HELLO) "; check L4S firmware, CDC/DTR and USB key mode" else ""
            state.update { it.copy(error="L4S $label timed out (RX $receivedBytes bytes)$hint",transmitStateKnown=false) }
            diagnose("l4s/timeout",state.value.error!!,ControlDiagnosticSeverity.ERROR)
            null
        }

    private fun diagnose(stage:String,message:String,severity:ControlDiagnosticSeverity=ControlDiagnosticSeverity.INFO) {
        diagnostics.record(ControlDiagnosticSource.RADIO,stage,message,severity)
    }

    private fun ok(reply: L4sProtocol.Reply?): Boolean = reply?.result==L4sProtocol.APPLIED
    override suspend fun updateTrackingDisplay(display: com.rtbishop.look4sat.core.domain.repository.RadioTrackingDisplay): Boolean = withContext(dispatcher) {
        if (!isConnected) return@withContext false
        if (state.value.capabilities?.trackingDisplay!=true) return@withContext true
        if (!display.azimuthDegrees.isFinite() || !display.elevationDegrees.isFinite()) return@withContext false
        val data=ByteArray(if(state.value.capabilities?.nativeSettings==true)36 else 20)
        val az=kotlin.math.round((((display.azimuthDegrees%360)+360)%360)*10).toInt()%3600
        val el=kotlin.math.round(display.elevationDegrees.coerceIn(-90.0,90.0)*10).toInt()
        L4sProtocol.put16(data,0,az);L4sProtocol.put16(data,2,el)
        display.satelliteName.take(16).map { if(it in ' '..'~')it else '?' }.joinToString("")
            .toByteArray(Charsets.US_ASCII).copyInto(data,4)
        if(data.size==36) (display.contextKey ?: state.value.nativeProfileKey)?.let { decodeContextKey(it).copyInto(data,20) }
        if(!ok(request(L4sProtocol.CONTEXT,data))) return@withContext false
        if(state.value.capabilities?.passClock==true) {
            val clock=ByteArray(5);clock[0]=display.passPhase.coerceIn(0,3).toByte()
            L4sProtocol.put32(clock,1,(if(display.passPhase==1)display.secondsUntilAos else display.secondsUntilLos).coerceIn(0,604800))
            ok(request(L4sProtocol.PASS_INFO,clock))
        } else true
    }
    override suspend fun prepareTrackingContext(contextKey: String): Boolean = withContext(dispatcher) {
        if(state.value.capabilities?.nativeSettings!=true) return@withContext true
        val data=ByteArray(36)
        L4sProtocol.put16(data,0,0xffff);L4sProtocol.put16(data,2,0x8000)
        decodeContextKey(contextKey).copyInto(data,20)
        ok(request(L4sProtocol.CONTEXT,data))
    }
    private fun decodeContextKey(key:String):ByteArray {
        require(key.length==32 && key.all { it in '0'..'9' || it in 'a'..'f' })
        return key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    override suspend fun selectSatelliteProfile(name: String, transponderUuid: String): Boolean = withContext(dispatcher) {
        if(state.value.capabilities?.satelliteProfiles!=true)
            return@withContext prepareTrackingContext(com.rtbishop.look4sat.core.domain.repository.radioContextKey(transponderUuid))
        val data=ByteArray(49)
        decodeContextKey(com.rtbishop.look4sat.core.domain.repository.radioSatelliteProfileKey(name,transponderUuid)).copyInto(data,1)
        decodeContextKey(com.rtbishop.look4sat.core.domain.repository.radioContextKey(transponderUuid)).copyInto(data,17)
        name.take(16).map { if(it in ' '..'~') it else '?' }.joinToString("").toByteArray(Charsets.US_ASCII).copyInto(data,33)
        val result=ok(request(L4sProtocol.PROFILE,data))
        if(result) {armed=false;requestedPair=null;rawTargets=null}
        result
    }
    override suspend fun updateTrackingBases(bases: com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies): Boolean = withContext(dispatcher) {
        if(state.value.capabilities?.satelliteProfiles!=true) return@withContext true
        if(state.value.capabilities?.transactionalProfileStore==true && state.value.profileStore?.correctionStepHz!=correctionStepHz()) {
            val config=ByteArray(3);config[0]=7;L4sProtocol.put16(config,1,correctionStepHz().coerceIn(10,1000).toInt()/10*10)
            if(!ok(request(L4sProtocol.PROFILE_STORE,config)))return@withContext false
        }
        val step=com.rtbishop.look4sat.core.domain.repository.normalizeL4sStep(tuningStepHz())
        if(state.value.nativeBaseFrequencies==bases && state.value.nativeStepHz==step) return@withContext true
        val data=ByteArray(15);data[0]=1
        L4sProtocol.put32(data,1,bases.rxHz);L4sProtocol.put32(data,5,bases.txHz ?: 0)
        L4sProtocol.put16(data,9,step.toInt())
        L4sProtocol.put32(data,11,state.value.nativeProfileGeneration ?: 0)
        val reply=request(L4sProtocol.PROFILE,data)
        if(reply?.result==9) request(L4sProtocol.PROFILE,byteArrayOf(2))
        if(ok(reply)) {armed=false;requestedPair=null}
        ok(reply)
    }
    override suspend fun profileStoreCommand(data:ByteArray):ByteArray?=withContext(dispatcher) {
        if(!isConnected || state.value.capabilities?.transactionalProfileStore!=true)return@withContext null
        request(L4sProtocol.PROFILE_STORE,data)?.takeIf {it.result<=1}?.data
    }
    override suspend fun setNativeCwOptions(options: com.rtbishop.look4sat.core.domain.repository.NativeCwOptions): Boolean = withContext(dispatcher) {
        if(!isConnected || state.value.capabilities?.cwOptions!=true || !options.valid() || nativeEditingBlocked()) return@withContext false
        val result=ok(request(L4sProtocol.CW_OPTIONS,byteArrayOf(1,options.wpm.toByte(),options.pitch.toByte(),
            options.volume.toByte(),options.hang.toByte(),options.keyer.toByte(),options.spacing.toByte())))
        if(result) {armed=false;requestedPair=null}
        result && ok(request(L4sProtocol.PROFILE,byteArrayOf(2)))
    }
    override suspend fun handsetMacroCommand(data: ByteArray): ByteArray? = withContext(dispatcher) {
        if(!isConnected || state.value.capabilities?.handsetMacros!=true || data.isEmpty()) return@withContext null
        if(data[0].toInt() !in setOf(0,4) && (state.value.txSource!=0 || !state.value.transmitStateKnown ||
            state.value.profileStore?.busy==true || state.value.cwState in setOf(
                com.rtbishop.look4sat.core.domain.repository.CwTransmitState.QUEUED,
                com.rtbishop.look4sat.core.domain.repository.CwTransmitState.SENDING))) return@withContext null
        request(L4sProtocol.MACROS,data)?.takeIf {it.result<=1}?.data
    }
    override suspend fun refreshNativeDiagnostics(): Boolean = withContext(dispatcher) {
        isConnected && state.value.capabilities?.nativeDiagnostics==true && ok(request(L4sProtocol.DIAGNOSTICS))
    }
    private fun nativeEditingBlocked() = state.value.let { !it.transmitStateKnown || it.txSource!=0 || it.nativeMenuOpen ||
        it.profileStore?.busy==true || it.handsetMacroStatus?.busy==true || it.cwState in setOf(
            com.rtbishop.look4sat.core.domain.repository.CwTransmitState.QUEUED,
            com.rtbishop.look4sat.core.domain.repository.CwTransmitState.SENDING) }
    override suspend fun setTrackingFrequencies(rxHz: Long, txHz: Long?): FrequencyPairResult = withContext(dispatcher) {
        if(!isConnected) return@withContext FrequencyPairResult.FAILED
        if(!state.value.transmitStateKnown || state.value.profileStore?.busy==true || state.value.handsetMacroStatus?.busy==true)return@withContext FrequencyPairResult.HELD
        if(state.value.txSource!=0 || state.value.cwState in setOf(
                com.rtbishop.look4sat.core.domain.repository.CwTransmitState.QUEUED,
                com.rtbishop.look4sat.core.domain.repository.CwTransmitState.SENDING)) {
            return@withContext FrequencyPairResult.HELD
        }
        val settings=state.value
        val pair=com.rtbishop.look4sat.core.domain.repository.AppliedRadioFrequencies(
            com.rtbishop.look4sat.core.domain.repository.l4sTrackingTarget(rxHz,settings.nativeRxCorrectionHz,tuningStepHz()),
            txHz?.let { com.rtbishop.look4sat.core.domain.repository.l4sTrackingTarget(it,settings.nativeTxCorrectionHz,tuningStepHz()) })
        rawTargets=com.rtbishop.look4sat.core.domain.repository.RawTrackingTargets(rxHz,txHz)
        val current=state.value
        if(requestedPair==pair && armed) {
            if(current.rxFrequencyHz==pair.rxHz && current.txFrequencyHz==pair.txHz && current.txEnabled==(pair.txHz!=null))
                return@withContext FrequencyPairResult.APPLIED
        }
        revision=(maxOf(revision,current.appliedRevision,current.pendingRevision)+1) and 0xffffffffL
        if(revision==0L) revision=1
        val data=ByteArray(13)
        L4sProtocol.put32(data,0,pair.rxHz);L4sProtocol.put32(data,4,pair.txHz ?: 0)
        data[8]=if(pair.txHz!=null) 1 else 0;L4sProtocol.put32(data,9,revision)
        val reply=request(L4sProtocol.PAIR,data)
        when(reply?.result) {
            L4sProtocol.APPLIED -> {
                val verified=state.value
                if(verified.appliedRevision!=revision || verified.rxFrequencyHz!=pair.rxHz ||
                    verified.txFrequencyHz!=pair.txHz || verified.txEnabled!=(pair.txHz!=null)) return@withContext FrequencyPairResult.FAILED
                requestedPair=pair;armed=true;FrequencyPairResult.APPLIED
            }
            4 -> if (state.value.txSource!=0) FrequencyPairResult.HELD else FrequencyPairResult.FAILED
            else -> FrequencyPairResult.FAILED
        }
    }

    override suspend fun setFrequency(frequencyHz: Long): Boolean = if(selectedRx) {
        setWorkingFrequency(frequencyHz)
    } else setTxVfoFrequency(frequencyHz)
    override suspend fun setWorkingFrequency(frequencyHz: Long) =
        setTrackingFrequencies(frequencyHz,rawTargets?.txHz)==FrequencyPairResult.APPLIED
    override suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean =
        rawTargets?.rxHz?.let { setTrackingFrequencies(it,frequencyHz)==FrequencyPairResult.APPLIED } ?: false
    override suspend fun setVfo(vfoA: Boolean): Boolean { selectedRx=vfoA;return isConnected }
    override suspend fun setBand(frequencyHz: Long): Boolean = state.value.capabilities?.let {
        frequencyHz in it.minimumRxHz..it.maximumRxHz
    } ?: false
    override suspend fun setSplitMode(enabled: Boolean): Boolean = if(enabled) isConnected else pttOff()

    override suspend fun setMode(mode: String): Boolean = if(selectedRx) setSplitModes(mode,null) else setSplitModes(null,mode)
    override suspend fun setSplitModes(rxMode: String?, txMode: String?): Boolean = withContext(dispatcher) {
        val rx=L4sProtocol.modeNumber(rxMode ?: state.value.rxMode) ?: return@withContext false
        val tx=L4sProtocol.modeNumber(txMode ?: state.value.txMode) ?: return@withContext false
        val caps=state.value.capabilities ?: return@withContext false
        if(caps.rxModeMask and (1 shl rx)==0 || caps.txModeMask and (1 shl tx)==0) {
            state.update { it.copy(error="L4S supports FM/CW transmit; AM/USB are receive-only") };return@withContext false
        }
        val narrow=if(listOf(rxMode,txMode).any { it?.uppercase(java.util.Locale.ROOT) in listOf("NFM","FM-N","FMN") }) 1 else 0
        ok(request(L4sProtocol.MODE,byteArrayOf(rx.toByte(),tx.toByte(),narrow.toByte()))) &&
            state.value.rxMode==L4sProtocol.modeName(rx) && state.value.txMode==L4sProtocol.modeName(tx)
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean {
        if(!toneHz.isFinite() || toneHz !in 0.0..999.9) return false
        toneTenths=kotlin.math.round(toneHz*10).toInt()
        return sendTone(toneTenths)
    }
    override suspend fun setCtcssMode(enabled: Boolean) = sendTone(if(enabled) toneTenths else 0)
    override suspend fun configureTxCtcss(toneHz: Double?): Boolean =
        if(toneHz==null) sendTone(0) else setCtcssTone(toneHz)
    private suspend fun sendTone(tenths: Int): Boolean = withContext(dispatcher) {
        if(state.value.capabilities?.ctcss!=true) return@withContext tenths==0
        ok(request(L4sProtocol.TONE,ByteArray(2).also { L4sProtocol.put16(it,0,tenths) }))
    }

    override suspend fun readFrequencyAndMode(): Pair<Long,String>? = withContext(dispatcher) {
        if(!ok(request(L4sProtocol.STATUS))) return@withContext null
        if(selectedRx) state.value.rxFrequencyHz?.let { it to state.value.rxMode }
        else state.value.txFrequencyHz?.let { it to state.value.txMode }
    }
    override suspend fun readWorkingFrequency(): Long? = withContext(dispatcher) {
        if(ok(request(L4sProtocol.STATUS))) state.value.rxFrequencyHz else null
    }
    override suspend fun readTxVfoFrequency(): Long? = withContext(dispatcher) {
        if(ok(request(L4sProtocol.STATUS))) state.value.txFrequencyHz else null
    }

    private suspend fun preparePair(): Boolean {
        val raw=rawTargets ?: return false
        val rawTx=raw.txHz ?: return false
        val current=state.value
        if(!current.transmitStateKnown || current.nativeMenuOpen || current.profileStore?.busy==true || current.handsetMacroStatus?.busy==true)return false
        val expected=com.rtbishop.look4sat.core.domain.repository.AppliedRadioFrequencies(
            com.rtbishop.look4sat.core.domain.repository.l4sTrackingTarget(raw.rxHz,current.nativeRxCorrectionHz,tuningStepHz()),
            com.rtbishop.look4sat.core.domain.repository.l4sTrackingTarget(rawTx,current.nativeTxCorrectionHz,tuningStepHz()))
        // A completed CW chunk still owns the TX window: verify the applied pair without retuning.
        if(armed && requestedPair==expected && current.rxFrequencyHz==expected.rxHz && current.txFrequencyHz==expected.txHz &&
            current.txEnabled && current.pendingRevision==0L)return true
        return setTrackingFrequencies(raw.rxHz,raw.txHz)==FrequencyPairResult.APPLIED
    }

    override suspend fun pttOn(): Boolean = withContext(dispatcher) {
        if(!isConnected || state.value.capabilities?.voicePtt!=true || !preparePair()) return@withContext false
        val data=ByteArray(5);data[0]=1;L4sProtocol.put32(data,1,state.value.appliedRevision)
        ok(request(L4sProtocol.PTT,data)) && state.value.txSource==1
    }
    override suspend fun pttOff(): Boolean = withContext(dispatcher) {
        armed=false;requestedPair=null
        ok(request(L4sProtocol.STOP)) && state.value.txSource==0
    }
    override suspend fun startCw(text: String, wpm: Int): Boolean = withContext(dispatcher) {
        val caps=state.value.capabilities ?: return@withContext false
        val normalized=CwTransmitText.normalize(text)
        if(!isConnected || !caps.cwText || normalized.length>caps.maxCwText ||
            wpm !in caps.minimumWpm..caps.maximumWpm || !preparePair()) return@withContext false
        val data=byteArrayOf(wpm.toByte(),normalized.length.toByte())+normalized.toByteArray(Charsets.US_ASCII)
        request(L4sProtocol.CW,data)?.result==L4sProtocol.ACCEPTED && state.value.cwState in
            setOf(com.rtbishop.look4sat.core.domain.repository.CwTransmitState.QUEUED,
                com.rtbishop.look4sat.core.domain.repository.CwTransmitState.SENDING,
                com.rtbishop.look4sat.core.domain.repository.CwTransmitState.DONE)
    }
}
