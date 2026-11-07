package com.rtbishop.look4sat.feature.radar

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.presentation.MainTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[28],qualifiers="en-w411dp-h891dp-port")
class RecordingPanelTest {
    @get:Rule val compose=createComposeRule()
    @Before fun clearPreviousSelection() {
        RuntimeEnvironment.getApplication().getSharedPreferences("receive_recording", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
    private class Recorder(
        private val choices: List<RecordingSource> =
            listOf(RecordingSource("wlan","IC-705 WLAN",RecordingSourceKind.WLAN))
    ): IRecordingService {
        override val state=MutableStateFlow(RecordingState())
        override val files=MutableStateFlow(emptyList<RecordingFile>())
        var starts=0;var stops=0
        var lastRequest:RecordingRequest?=null
        override fun sources()=choices
        override fun start(request:RecordingRequest) {starts++;lastRequest=request;state.value=RecordingState(phase=RecordingPhase.RECORDING,source=sources().first { it.id == request.sourceId })}
        override fun failStart(reason:String)=Unit
        override suspend fun runRecording(request:RecordingRequest)=Unit
        override suspend fun stop() {stops++;state.value=RecordingState()}
        override suspend fun refreshFiles()=Unit
        override suspend fun play(id:String)=Unit
        override suspend fun stopPlayback()=Unit
        override suspend fun export(id:String,destinationUri:String)=Unit
        override suspend fun delete(id:String)=Unit
        override fun setApplicationForeground(foreground:Boolean)=Unit
    }
    private fun exercise(dark:Boolean) {
        val recorder=Recorder();val visible=mutableStateOf(true)
        compose.setContent {MainTheme(isDarkTheme=dark) {if(visible.value) RecordingPanel("RS-44",10,recorder,maxElevation=87.8) {visible.value=false}}}
        // The only available receiver is selected automatically.
        compose.onNodeWithText("Start recording").assertIsEnabled().performClick()
        compose.onNodeWithText("Stop recording").assertExists()
        compose.runOnIdle {assertEquals(1,recorder.starts);assertEquals(RecordingRequest("wlan","RS-44",10,87.8),recorder.lastRequest);visible.value=false}
        compose.runOnIdle {assertEquals(0,recorder.stops);assertTrue(recorder.state.value.active)}
    }
    @Test fun dismissingDarkPanelDoesNotStopBackgroundRecording()=exercise(true)
    @Test fun lightPanelAutomaticallyChoosesReceiveSource()=exercise(false)

    @Test fun selectionSurvivesClosingAndReopeningThePanel() {
        val recorder=Recorder(listOf(
            RecordingSource("first","Receiver 1",RecordingSourceKind.WLAN),
            RecordingSource("second","Receiver 2",RecordingSourceKind.WLAN)
        ))
        val visible=mutableStateOf(true)
        compose.setContent {
            MainTheme(isDarkTheme=false) {
                if(visible.value) RecordingPanel("RS-44",10,recorder) {visible.value=false}
            }
        }
        compose.onNodeWithText("Receiver 2").performClick()
        compose.runOnIdle {visible.value=false}
        compose.runOnIdle {visible.value=true}
        compose.onNodeWithText("Start recording").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("second",recorder.lastRequest?.sourceId) }
    }

    @Test fun preferredSourceFallsBackToConnectedN76UsbAndMicrophone() {
        val n76=RecordingSource("n76","N76",RecordingSourceKind.N76)
        val usb=RecordingSource("${AudioDeviceInfo.TYPE_USB_DEVICE}:USB Input","USB Input",RecordingSourceKind.DEVICE)
        val wired=RecordingSource("${AudioDeviceInfo.TYPE_WIRED_HEADSET}:Wired","Wired",RecordingSourceKind.DEVICE)
        val mic=RecordingSource("${AudioDeviceInfo.TYPE_BUILTIN_MIC}:Phone","Phone",RecordingSourceKind.MICROPHONE)
        val sources=listOf(mic,wired,usb,n76)
        assertEquals(n76, preferredRecordingSource(sources,null,true))
        assertEquals(usb, preferredRecordingSource(sources,null,false))
        assertEquals(wired, preferredRecordingSource(sources,wired.id,true))
        assertEquals(usb, preferredRecordingSource(sources,"disconnected",false))
        assertEquals(mic, preferredRecordingSource(listOf(n76,mic),"n76",false))
        assertNull(preferredRecordingSource(listOf(n76),"n76",false))
    }
}
