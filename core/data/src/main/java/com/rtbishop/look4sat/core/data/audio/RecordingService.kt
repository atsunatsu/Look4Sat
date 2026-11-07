// SPDX-License-Identifier: GPL-3.0-or-later
package com.rtbishop.look4sat.core.data.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import com.rtbishop.look4sat.core.data.framework.N76RecordingDestination
import com.rtbishop.look4sat.core.data.framework.copyN76Recording
import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.control.*
import com.rtbishop.look4sat.core.domain.repository.*
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import org.json.JSONObject

/** Already-decoded network audio can register here without coupling recording to a CAT backend. */
internal data class RecordingExternalInput(
    val source: RecordingSource,
    val hub: IAudioHub,
    val available: () -> Boolean
)

internal class RecordingService(
    private val context: Context, private val scope: CoroutineScope,
    private val settings: ISettingsRepo, private val local: IAudioHub,
    private val n76: N76ReceivePcmBus, private val radio: ()->IRadioTrackingService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    directory: File? = null,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics,
    private val mediaPlayerFactory: () -> MediaPlayer = ::MediaPlayer,
    private val externalInputs: () -> List<RecordingExternalInput> = { emptyList() }
) : IRecordingService {
    private val mutable=MutableStateFlow(RecordingState())
    override val state=mutable.asStateFlow()
    private val listing=MutableStateFlow(emptyList<RecordingFile>())
    override val files=listing.asStateFlow()
    private val root=directory ?: File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,"Recordings")
    private val legacy=File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,"N76")
    @Volatile private var active: Job?=null
    private var player: MediaPlayer?=null
    @Volatile private var foreground=true
    @Volatile private var pending: RecordingRequest?=null
    @Volatile private var pendingConfiguration: Pair<String,Boolean>?=null
    private val recovery=scope.async(ioDispatcher) {root.mkdirs();root.listFiles()?.filter {it.name.endsWith(".wav.partial")}?.forEach(::recoverRecording);refreshFiles()}

    override fun sources(): List<RecordingSource> {
        val config=settings.radioControlSettings.value
        if(config.enabled && config.radioModel==RadioControlSettings.MODEL_VERO_N76)
            return listOf(RecordingSource("n76","N76",RecordingSourceKind.N76))
        return externalInputs().map {it.source} + local.inputDevices.value.filter {(it.external && it.type!=AudioDeviceInfo.TYPE_BLUETOOTH_SCO) || it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC}
            .distinctBy { if(it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC) "phone-microphone" else it.key }
            .map {RecordingSource(it.key,it.name,if(it.external) RecordingSourceKind.DEVICE else RecordingSourceKind.MICROPHONE)}
    }
    @Synchronized override fun start(request: RecordingRequest) {
        if(state.value.active) return
        try {
            val source=checkNotNull(sources().firstOrNull {it.id==request.sourceId}) {"Select an available receive source"}
            check(radio().state.value.pttState==PttState.OFF && !radio().state.value.catTransmitting) {"Stop transmission before changing the receive source"}
            if(source.kind==RecordingSourceKind.N76) check(settings.radioControlSettings.value.n76BluetoothAudio && radio().state.value.remoteRadio?.bluetoothAudioConnected==true) {"Enable N76 Bluetooth audio and connect the radio first"}
            mutable.value=RecordingState(phase=RecordingPhase.PREPARING,source=source)
            pending=request
            pendingConfiguration=settings.radioControlSettings.value.let {it.radioModel to it.n76BluetoothAudio}
            diagnostics.record(ControlDiagnosticSource.RADIO,"recording/start","source=${source.name} satellite=${request.satellite}")
            (context.applicationContext as IRecordingForegroundLauncher).startRecordingForeground(request)
        } catch(error:Exception) {pending=null;pendingConfiguration=null;mutable.value=RecordingState(error=error.message)}
    }
    override fun failStart(reason: String) {pending=null;pendingConfiguration=null;mutable.update {it.copy(phase=RecordingPhase.IDLE,error=reason)}}
    override suspend fun runRecording(request: RecordingRequest) {
        if(pending!=request || active!=null) return
        active=currentCoroutineContext().job
        var output: RecordingWaveFile?=null
        var finalizedMillis=0L
        val source=checkNotNull(state.value.source)
        var expected: Long?=null
        var segmentSatellite=request.satellite
        var segmentPass=request.passAos
        var segmentMaxElevation=request.maxElevation
        var segmentStartFrame=0L
        var segmentStart=System.currentTimeMillis()
        val sourceConfig=pendingConfiguration
        suspend fun finishSegment(reason: String) {
            val current=output ?: return
            output=null
            current.close()
            if(current.samples==0L) {current.file.delete();return}
            val final=File(root,current.file.name.removeSuffix(".partial"))
            check(!final.exists() && current.file.renameTo(final)) {"Could not finalize recording"}
            File(root,final.name+".json").writeText(JSONObject().apply {
                put("startedUtcMillis",segmentStart);put("source",source.id);put("sourceName",source.name);put("satellite",segmentSatellite)
                put("passAos",segmentPass);put("startFrame",segmentStartFrame);put("sampleRate",current.rate);put("samples",current.samples)
                put("maxElevation",segmentMaxElevation)
                put("endReason",reason);put("endedUtcMillis",System.currentTimeMillis())
            }.toString(),Charsets.UTF_8)
            finalizedMillis+=current.samples*1000/current.rate
            diagnostics.record(ControlDiagnosticSource.RADIO,"recording/segment","samples=${current.samples} rate=${current.rate} reason=$reason")
            refreshFiles()
        }
        try {withContext(ioDispatcher) {
            recovery.await()
            stopPlayback()
            check(sourceConfig==settings.radioControlSettings.value.let {it.radioModel to it.n76BluetoothAudio}) {"Receive configuration changed before recording started"}
            check(root.exists() || root.mkdirs()) {"Cannot create recording folder"}
            check(root.usableSpace>20_000_000) {"Not enough storage for recording"}
            val input=when(source.kind) {
                RecordingSourceKind.N76 -> n76.audio
                RecordingSourceKind.WLAN -> {
                    val external=checkNotNull(externalInputs().firstOrNull {it.source.id==source.id}) {"Receive source is unavailable"}
                    check(external.available()) {"Receive audio is not ready"}
                    external.hub.audioFlow(AudioConsumer.RECORDING,AudioDelivery.RELIABLE)
                }
                else -> {local.selectInputDevice(source.id);local.audioFlow(AudioConsumer.RECORDING,AudioDelivery.RELIABLE)}
            }
            coroutineScope {
                val queue=Channel<TimestampedAudioChunk>(16)
                val producer=launch {
                    try {input.collect {chunk ->
                        if(!queue.trySend(chunk.copy(samples=chunk.samples.copyOf())).isSuccess) error("Recording storage is too slow")
                    }} catch(cancelled:CancellationException) {throw cancelled}
                    catch(error:Exception) {queue.close(error)} finally {queue.close()}
                }
                val monitor=launch {
                    while(isActive) {
                        delay(500)
                        val config=settings.radioControlSettings.value
                        check(sourceConfig==(config.radioModel to config.n76BluetoothAudio)) {"Receive source changed; recording saved"}
                        if(source.kind==RecordingSourceKind.N76) check(config.n76BluetoothAudio && radio().state.value.remoteRadio?.bluetoothAudioConnected==true) {"N76 audio disconnected"}
                        if(source.kind==RecordingSourceKind.WLAN) check(externalInputs().firstOrNull {it.source.id==source.id}?.available?.invoke()==true) {"Receive audio interrupted"}
                    }
                }
                try {
                    mutable.update {it.copy(phase=RecordingPhase.RECORDING)}
                    for(chunk in queue) {
                        ensureActive()
                        check(chunk.sampleRate in 8000..192000) {"Unsupported recording sample rate"}
                        val config=settings.radioControlSettings.value
                        check(sourceConfig==(config.radioModel to config.n76BluetoothAudio)) {"Receive source changed; recording saved"}
                        if(source.kind==RecordingSourceKind.N76) check(config.n76BluetoothAudio) {"N76 Bluetooth audio disabled; recording saved"}
                        val capture=local.state.value as? AudioHubState.Capturing
                        if(source.kind in listOf(RecordingSourceKind.MICROPHONE,RecordingSourceKind.DEVICE)) {
                            check(capture!=null && !capture.systemSilenced) {"Audio capture was interrupted"}
                            val device=context.getSystemService(android.media.AudioManager::class.java)
                                .getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).firstOrNull {it.id==capture.deviceId}
                            val identity=device?.let { if(android.os.Build.VERSION.SDK_INT>=28) it.address.ifBlank {it.productName.toString()} else it.productName.toString() }
                            check(device!=null && "${device.type}:$identity"==source.id) {"Selected receive input was disconnected or rerouted"}
                        }
                        val currentPass=radio().state.value.currentPass
                        val satellite=currentPass?.name ?: request.satellite
                        val pass=currentPass?.aosTime ?: request.passAos
                        val split=output?.let {it.rate!=chunk.sampleRate || it.samples>=it.rate*1800L}==true ||
                            chunk.discontinuity || (expected!=null && expected!=chunk.framePosition) || satellite!=segmentSatellite || pass!=segmentPass
                        if(split) finishSegment("segment boundary or receive discontinuity")
                        if(output==null) {
                            check(root.usableSpace>20_000_000) {"Not enough storage for recording"}
                            segmentSatellite=satellite
                            segmentMaxElevation=currentPass?.maxElevation ?: request.maxElevation
                            segmentPass=pass;segmentStartFrame=chunk.framePosition
                            segmentStart=System.currentTimeMillis()-(SystemClock.elapsedRealtimeNanos()-chunk.elapsedRealtimeNanos).coerceAtLeast(0)/1_000_000
                            output=RecordingWaveFile(createRecordingFile(root,segmentStart,satellite,segmentMaxElevation),chunk.sampleRate)
                        }
                        output!!.write(chunk.samples);expected=chunk.framePosition+chunk.samples.size
                        mutable.update {it.copy(durationMillis=finalizedMillis+output!!.samples*1000/chunk.sampleRate,
                            sampleRate=chunk.sampleRate,fileName=output!!.file.name.removeSuffix(".partial"))}
                    }
                    val failure=(local.state.value as? AudioHubState.Failed)?.reason
                    check(failure==null) {failure.orEmpty()}
                } finally {producer.cancel();monitor.cancel();queue.cancel()}
            }
        }} catch(cancelled:CancellationException) {throw cancelled}
        catch(error:Exception) {
            diagnostics.record(ControlDiagnosticSource.RADIO,"recording/error",error.message.orEmpty(),ControlDiagnosticSeverity.ERROR)
            mutable.update {it.copy(error=error.message)}
        }
        finally {withContext(NonCancellable+ioDispatcher) {
            mutable.update {it.copy(phase=RecordingPhase.FINISHING)}
            runCatching {finishSegment("stopped")}.onFailure {mutable.update {s->s.copy(error=it.message)}}
            refreshFiles()
            pending=null;pendingConfiguration=null;active=null;mutable.update {it.copy(phase=RecordingPhase.IDLE)}
            if(!foreground && source.kind==RecordingSourceKind.N76) runCatching {radio().setAudioForeground(false)}
        }}
    }
    override suspend fun stop() {
        val job=active
        if(job!=null && job!=currentCoroutineContext().job) job.cancelAndJoin()
        if(job==null) {pending=null;pendingConfiguration=null;mutable.update {it.copy(phase=RecordingPhase.IDLE)}}
    }
    override fun setApplicationForeground(foreground: Boolean) {this.foreground=foreground}
    override suspend fun refreshFiles()=withContext(ioDispatcher) {
        listing.value=listOf(root,legacy).flatMap {it.listFiles()?.toList().orEmpty()}
            .filter {it.isFile && it.extension.equals("wav",true) && it.length()>=44}
            .sortedByDescending(File::lastModified).map {RecordingFile(it.absolutePath,it.name,it.length(),it.lastModified())}
    }
    private fun file(id: String): File {
        val file=File(id).canonicalFile
        val parent=checkNotNull(file.parentFile) {"Recording file is unavailable"}
        require(parent in listOf(root.canonicalFile,legacy.canonicalFile) && file.extension.equals("wav",true) && file.isFile) {"Recording file is unavailable"}
        return file
    }
    override suspend fun play(id: String)=withContext(mainDispatcher) {
        check(!state.value.active && radio().state.value.pttState==PttState.OFF && !radio().state.value.catTransmitting) {"Stop recording/transmission before playback"}
        stopPlayback()
        mutable.update {it.copy(error=null)}
        val candidate=mediaPlayerFactory();player=candidate
        try {
            val outputs=context.getSystemService(android.media.AudioManager::class.java)
                .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            val speaker=checkNotNull(outputs.firstOrNull {it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}) {"Phone playback speaker is unavailable"}
            if(android.os.Build.VERSION.SDK_INT<28) check(outputs.all {it.type in listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)}) {"Disconnect radio audio before playback on this Android version"}
            candidate.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            candidate.setDataSource(file(id).absolutePath)
            candidate.setOnCompletionListener {finished -> if(player===finished) scope.launch {if(player===finished) stopPlayback()}}
            candidate.setOnErrorListener {failed,what,extra ->
                if(player===failed) scope.launch {
                    if(player===failed) {
                        stopPlayback();mutable.update {it.copy(error="Playback failed")}
                        diagnostics.record(ControlDiagnosticSource.RADIO,"recording/playback/error","what=$what extra=$extra",ControlDiagnosticSeverity.ERROR)
                    }
                }
                true
            }
            candidate.setOnPreparedListener {ready -> if(player===ready) scope.launch(mainDispatcher) {
                try {
                    // setPreferredDevice needs the native player created by setDataSource.
                    // Apply it after preparation, before starting even muted playback.
                    if(android.os.Build.VERSION.SDK_INT>=28) check(ready.setPreferredDevice(speaker)) {"Phone playback route is unavailable"}
                    // Prove routing with muted audio before exposing a recording
                    // to an output that might otherwise feed a radio's VOX.
                    ready.setVolume(0f,0f);ready.start()
                    if(android.os.Build.VERSION.SDK_INT>=28) {
                        withTimeout(1500) {while(player===ready && ready.routedDevice?.id!=speaker.id) delay(10)}
                        if(player!==ready) return@launch
                    }
                    check(!state.value.active && radio().state.value.pttState==PttState.OFF && !radio().state.value.catTransmitting) {"Stop recording/transmission before playback"}
                    ready.seekTo(0);ready.setVolume(1f,1f);mutable.update {it.copy(playing=true)}
                    diagnostics.record(ControlDiagnosticSource.RADIO,"recording/playback/ready","route=phone-speaker")
                    while(player===ready) {
                        delay(100)
                        if(player!==ready) break
                        val safe=radio().state.value.pttState==PttState.OFF && !radio().state.value.catTransmitting &&
                            (android.os.Build.VERSION.SDK_INT<28 || ready.routedDevice?.id==speaker.id)
                        if(!safe) {ready.setVolume(0f,0f);stopPlayback();mutable.update {it.copy(error="Playback stopped after a radio or output route change")}}
                    }
                } catch(timeout:TimeoutCancellationException) {
                    if(player===ready) {
                        stopPlayback();mutable.update {it.copy(error="Phone playback route could not be confirmed")}
                        diagnostics.record(ControlDiagnosticSource.RADIO,"recording/playback/error","Phone speaker route confirmation timed out",ControlDiagnosticSeverity.ERROR)
                    }
                } catch(cancelled:CancellationException) {
                    withContext(NonCancellable) {if(player===ready) stopPlayback()}
                    throw cancelled
                }
                catch(error:Exception) {if(player===ready) {
                    stopPlayback();mutable.update {it.copy(error=error.message)}
                    diagnostics.record(ControlDiagnosticSource.RADIO,"recording/playback/error",error.message.orEmpty(),ControlDiagnosticSeverity.ERROR)
                }}
            }}
            candidate.prepareAsync()
        } catch(error:Exception) {
            stopPlayback()
            diagnostics.record(ControlDiagnosticSource.RADIO,"recording/playback/error",error.message.orEmpty(),ControlDiagnosticSeverity.ERROR)
            throw error
        }
    }
    override suspend fun stopPlayback()=withContext(mainDispatcher) {
        player?.release();player=null;mutable.update {it.copy(playing=false)}
    }
    override suspend fun export(id: String,destinationUri: String)=withContext(ioDispatcher) {
        check(!state.value.exporting);mutable.update {it.copy(exporting=true,error=null)}
        try {
            val uri=Uri.parse(destinationUri);require(uri.scheme=="content")
            copyN76Recording(file(id)) {object:N76RecordingDestination {
                override fun open()=context.contentResolver.openOutputStream(uri,"w")
            }}
            Unit
        } finally {mutable.update {it.copy(exporting=false)}}
    }
    override suspend fun delete(id: String)=withContext(ioDispatcher) {
        check(!state.value.active && !state.value.exporting)
        stopPlayback();val target=file(id);check(target.delete());File(target.parentFile,target.name+".json").delete();refreshFiles()
    }
}
