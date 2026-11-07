package com.rtbishop.look4sat.core.data.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.*
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=RecordingTestApplication::class)
class RecordingPlaybackTest {
    @get:Rule val temp=TemporaryFolder()
    private inline fun <reified T> stub(crossinline call:(String)->Any?):T =
        Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) {_,method,_->call(method.name)} as T

    private class Player : MediaPlayer() {
        val events=mutableListOf<String>()
        var route:AudioDeviceInfo?=null
        var acceptRoute=true
        var released=false
        private var initialized=false
        private var prepared=false
        private var preparedListener:OnPreparedListener?=null
        var completion:OnCompletionListener?=null
        var failure:OnErrorListener?=null
        override fun setAudioAttributes(attributes:AudioAttributes) {
            assertEquals(AudioAttributes.USAGE_MEDIA,attributes.usage)
            events+="attributes"
        }
        override fun setDataSource(path:String) {initialized=true;events+="source"}
        override fun setOnPreparedListener(listener:OnPreparedListener?) {preparedListener=listener}
        override fun setOnCompletionListener(listener:OnCompletionListener?) {completion=listener}
        override fun setOnErrorListener(listener:OnErrorListener?) {failure=listener}
        override fun prepareAsync() {events+="prepare"}
        fun ready() {prepared=true;preparedListener!!.onPrepared(this)}
        override fun setPreferredDevice(device:AudioDeviceInfo?):Boolean {
            check(initialized && prepared) {"Native player not initialized/prepared"}
            events+="route";return acceptRoute
        }
        override fun getRoutedDevice()=route
        override fun setVolume(left:Float,right:Float) {events+=if(left==0f && right==0f) "mute" else "unmute"}
        override fun start() {events+="start"}
        @Suppress("DEPRECATION") override fun seekTo(msec:Int) {events+="rewind"}
        override fun release() {released=true;events+="release"}
    }
    private fun device(type:Int,id:Int)=AudioDeviceInfoBuilder.newBuilder().setType(type).build().also {
        val port=ReflectionHelpers.getField<Any>(it,"mPort")
        val handle=ReflectionHelpers.getField<Any>(port,"mHandle")
        ReflectionHelpers.setField(handle,"mId",id)
    }
    private class Hub : IAudioHub {
        override val state=MutableStateFlow<AudioHubState>(AudioHubState.Idle)
        override val inputDevices=MutableStateFlow(emptyList<AudioInputDevice>())
        override fun audioFlow(consumer:AudioConsumer,delivery:AudioDelivery)=emptyFlow<TimestampedAudioChunk>()
        override suspend fun selectInputDevice(deviceKey:String?)=Unit
        override suspend fun stopAll()=Unit
    }
    private fun defaultConfig()=RadioControlSettings(false,RadioControlSettings.MODEL_ICOM_IC705,"","","","",19200)
    private fun TestScope.service(players:List<Player>,hub:Hub=Hub(),config:RadioControlSettings=defaultConfig()):RecordingService {
        val settings=stub<ISettingsRepo> {if(it=="getRadioControlSettings") MutableStateFlow(config) else null}
        val radio=stub<IRadioTrackingService> {if(it=="getState") MutableStateFlow(RadioTrackingState()) else error("Playback must not control the radio")}
        val queue=ArrayDeque(players)
        val dispatcher=StandardTestDispatcher(testScheduler)
        return RecordingService(RuntimeEnvironment.getApplication(),backgroundScope,settings,hub,N76ReceivePcmBus(),
            {radio},dispatcher,dispatcher,temp.root,mediaPlayerFactory={queue.removeFirst()})
    }
    private fun wave():File=File(temp.root,"received.wav").also {file ->
        RecordingWaveFile(file,48000).use {it.write(FloatArray(4800))}
    }
    private fun outputs(vararg devices:AudioDeviceInfo) {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)).setOutputDevices(devices.toList())
    }

    @Test fun playbackInitializesBeforeRoutingAndNeedsNoMicrophonePermission()=runTest {
        val speaker=device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,1);outputs(speaker)
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(PackageManager.PERMISSION_DENIED,app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val player=Player().apply {route=speaker};val service=service(listOf(player))
        service.play(wave().absolutePath)
        assertEquals(listOf("attributes","source","prepare"),player.events)
        player.ready();runCurrent()
        assertEquals(listOf("attributes","source","prepare","route","mute","start","rewind","unmute"),player.events)
        assertTrue(service.state.value.playing)
        service.stopPlayback();assertTrue(player.released)
    }
    @Test fun delayedRouteConfirmationStaysMutedAndRouteLossStopsPlayback()=runTest {
        val speaker=device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,1)
        val usb=device(AudioDeviceInfo.TYPE_USB_DEVICE,2);outputs(speaker,usb)
        val player=Player().apply {route=usb};val service=service(listOf(player))
        service.play(wave().absolutePath);player.ready();runCurrent()
        advanceTimeBy(200);runCurrent();assertFalse(player.events.contains("unmute"))
        player.route=speaker;advanceTimeBy(20);runCurrent();assertTrue(service.state.value.playing)
        player.route=usb;advanceTimeBy(110);runCurrent()
        assertTrue(player.released);assertFalse(service.state.value.playing)
    }
    @Test fun wrongRouteTimesOutWithoutUnmuting()=runTest {
        val speaker=device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,1);outputs(speaker)
        val player=Player();val service=service(listOf(player))
        service.play(wave().absolutePath);player.ready();runCurrent()
        advanceTimeBy(1600);runCurrent()
        assertTrue(player.released);assertFalse(player.events.contains("unmute"))
        assertTrue(service.state.value.error.orEmpty().contains("confirmed"))
    }
    @Test fun rejectedRouteCanRetryAndOldCallbacksCannotStopTheNewPlayer()=runTest {
        val speaker=device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,1);outputs(speaker)
        val failed=Player().apply {acceptRoute=false}
        val next=Player().apply {route=speaker};val service=service(listOf(failed,next));val file=wave()
        service.play(file.absolutePath);failed.ready();runCurrent()
        assertTrue(failed.released);assertFalse(failed.events.contains("start"));assertNotNull(service.state.value.error)
        service.play(file.absolutePath);next.ready();runCurrent()
        failed.completion!!.onCompletion(failed);failed.failure!!.onError(failed,1,0);runCurrent()
        assertTrue(service.state.value.playing);assertFalse(next.released);assertNull(service.state.value.error)
        service.stopPlayback()
    }
    @Test fun microphoneVariantsCollapseAndUsbKeysArePreserved()=runTest {
        val hub=Hub().apply {inputDevices.value=listOf(
            AudioInputDevice("15:bottom","Phone",AudioDeviceInfo.TYPE_BUILTIN_MIC,false),
            AudioInputDevice("15:back","Phone",AudioDeviceInfo.TYPE_BUILTIN_MIC,false),
            AudioInputDevice("11:usb","USB",AudioDeviceInfo.TYPE_USB_DEVICE,true))}
        assertEquals(listOf("15:bottom","11:usb"),service(emptyList(),hub).sources().map {it.id})
    }
}
