package com.rtbishop.look4sat.core.data.audio

import android.app.Application
import android.media.AudioDeviceInfo
import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.*
import java.lang.reflect.Proxy
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class RecordingTestApplication: Application(), IRecordingForegroundLauncher {
    var request: RecordingRequest?=null
    override fun startRecordingForeground(request: RecordingRequest) {this.request=request}
}
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=RecordingTestApplication::class)
class RecordingServiceTest {
    @get:Rule val temp=TemporaryFolder()
    private inline fun <reified T> stub(crossinline call:(String)->Any?):T = Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) {_,method,_->call(method.name)} as T
    private class Hub:IAudioHub {
        override val state=MutableStateFlow<AudioHubState>(AudioHubState.Idle)
        override val inputDevices=MutableStateFlow(emptyList<AudioInputDevice>())
        val input=MutableSharedFlow<TimestampedAudioChunk>(extraBufferCapacity=32)
        var subscribers=0
        override fun audioFlow(consumer:AudioConsumer,delivery:AudioDelivery)=flow {
            subscribers++;try {emitAll(input)} finally {subscribers--}
        }
        override suspend fun selectInputDevice(deviceKey:String?)=Unit
        override suspend fun stopAll()=Unit
    }
    @Test fun sharedReceiveStreamRecordsMonoSplitsDiscontinuitiesAndOutlivesPage()=runTest {
        val hub=Hub();val dispatcher=StandardTestDispatcher(testScheduler)
        val config=MutableStateFlow(RadioControlSettings(true,RadioControlSettings.MODEL_ICOM_IC705,"","","","",19200))
        val settings=stub<ISettingsRepo> {if(it=="getRadioControlSettings") config else null}
        val radioState=MutableStateFlow(RadioTrackingState())
        val radio=stub<IRadioTrackingService> {if(it=="getState") radioState else error("Recording must not issue CAT commands: $it")}
        val app=RuntimeEnvironment.getApplication() as RecordingTestApplication
        val service=RecordingService(app,backgroundScope,settings,hub,N76ReceivePcmBus(),{radio},dispatcher,dispatcher,temp.root,
            externalInputs={listOf(RecordingExternalInput(RecordingSource("wlan","Test network",RecordingSourceKind.WLAN),hub) {true})})
        val request=RecordingRequest("wlan","RS-44",10,87.8)
        service.start(request);assertEquals(request,app.request);assertTrue(service.state.value.active)
        val job=launch {service.runRecording(request)};runCurrent();assertEquals(1,hub.subscribers)
        hub.input.emit(TimestampedAudioChunk(FloatArray(480) {0.25f},48000,0,0,AudioSampleFormat.PCM_FLOAT));runCurrent()
        assertEquals(10L,service.state.value.durationMillis)
        service.setApplicationForeground(false)
        hub.input.emit(TimestampedAudioChunk(FloatArray(480) {0.5f},48000,960,0,AudioSampleFormat.PCM_FLOAT,true));runCurrent()
        assertTrue(service.state.value.active);assertEquals(1,service.files.value.size)
        service.stop();job.join();runCurrent()
        assertFalse(service.state.value.active);assertEquals(0,hub.subscribers)
        assertEquals(2,service.files.value.size)
        service.files.value.forEach {f ->
            val bytes=File(f.id).readBytes();val head=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1,head.getShort(22).toInt());assertEquals(48000,head.getInt(24));assertEquals(960,head.getInt(40))
            assertTrue(File(f.id+".json").isFile)
            assertTrue(f.name.contains("-RS-44-87.8deg"))
            assertEquals(87.8,org.json.JSONObject(File(f.id+".json").readText()).getDouble("maxElevation"),0.0)
        }
        val original=service.files.value.first()
        assertTrue(runCatching {service.export(original.id,"file:///invalid")}.isFailure)
        assertTrue(File(original.id).exists())
        assertTrue(runCatching {service.delete(File(temp.root.parent,"outside.wav").absolutePath)}.isFailure)
    }
    @Test fun failedForegroundStartResetsStateAndDoesNotStartCapture()=runTest {
        val hub=Hub();val dispatcher=StandardTestDispatcher(testScheduler)
        val config=MutableStateFlow(RadioControlSettings(true,RadioControlSettings.MODEL_ICOM_IC705,"","","","",19200))
        val settings=stub<ISettingsRepo> {if(it=="getRadioControlSettings") config else null}
        val radio=stub<IRadioTrackingService> {if(it=="getState") MutableStateFlow(RadioTrackingState()) else null}
        val service=RecordingService(RuntimeEnvironment.getApplication(),backgroundScope,settings,hub,N76ReceivePcmBus(),{radio},dispatcher,dispatcher,temp.root,
            externalInputs={listOf(RecordingExternalInput(RecordingSource("wlan","Test network",RecordingSourceKind.WLAN),hub) {true})})
        service.start(RecordingRequest("wlan"));service.failStart("Permission denied")
        assertFalse(service.state.value.active);assertEquals("Permission denied",service.state.value.error)
        assertEquals(0,hub.subscribers)
    }
    @Test fun n76ConfigurationDoesNotHideLocalAudioInputFallbacks()=runTest {
        val hub=Hub()
        hub.inputDevices.value=listOf(AudioInputDevice(
            "${AudioDeviceInfo.TYPE_BUILTIN_MIC}:Phone", "Phone",
            AudioDeviceInfo.TYPE_BUILTIN_MIC, false
        ))
        val config=MutableStateFlow(RadioControlSettings(true,RadioControlSettings.MODEL_VERO_N76,"","","","",19200))
        val settings=stub<ISettingsRepo> {if(it=="getRadioControlSettings") config else null}
        val radio=stub<IRadioTrackingService> {if(it=="getState") MutableStateFlow(RadioTrackingState()) else null}
        val dispatcher=StandardTestDispatcher(testScheduler)
        val service=RecordingService(RuntimeEnvironment.getApplication(),backgroundScope,settings,hub,
            N76ReceivePcmBus(),{radio},dispatcher,dispatcher,temp.root)
        assertEquals(
            listOf("n76", "${AudioDeviceInfo.TYPE_BUILTIN_MIC}:Phone"),
            service.sources().map {it.id}
        )
    }

    @Test fun n76RequiresExplicitAudioOptInEvenIfAnOldConnectionIsStillReported()=runTest {
        val hub=Hub();val dispatcher=StandardTestDispatcher(testScheduler)
        val config=MutableStateFlow(RadioControlSettings(true,RadioControlSettings.MODEL_VERO_N76,"","","","",19200))
        val settings=stub<ISettingsRepo> {if(it=="getRadioControlSettings") config else null}
        val radioState=MutableStateFlow(RadioTrackingState(remoteRadio=RemoteRadioState(connected=true,bluetoothAudioConnected=true)))
        val radio=stub<IRadioTrackingService> {if(it=="getState") radioState else null}
        val service=RecordingService(RuntimeEnvironment.getApplication(),backgroundScope,settings,hub,N76ReceivePcmBus(),{radio},dispatcher,dispatcher,temp.root)
        service.start(RecordingRequest("n76"))
        assertFalse(service.state.value.active);assertTrue(service.state.value.error.orEmpty().contains("Enable N76"))
        assertEquals(0,hub.subscribers)
    }
}
