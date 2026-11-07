package com.rtbishop.look4sat.feature.radar

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.presentation.MainTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[28],qualifiers="en-w411dp-h891dp-port")
class RecordingPanelTest {
    @get:Rule val compose=createComposeRule()
    private class Recorder: IRecordingService {
        override val state=MutableStateFlow(RecordingState())
        override val files=MutableStateFlow(emptyList<RecordingFile>())
        var starts=0;var stops=0
        var lastRequest:RecordingRequest?=null
        override fun sources()=listOf(RecordingSource("wlan","IC-705 WLAN",RecordingSourceKind.WLAN))
        override fun start(request:RecordingRequest) {starts++;lastRequest=request;state.value=RecordingState(phase=RecordingPhase.RECORDING,source=sources().single())}
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
        compose.onNodeWithText("Start recording").assertIsNotEnabled()
        compose.onNodeWithText("IC-705 WLAN").performClick()
        compose.onNodeWithText("Start recording").assertIsEnabled().performClick()
        compose.onNodeWithText("Stop recording").assertExists()
        compose.runOnIdle {assertEquals(1,recorder.starts);assertEquals(RecordingRequest("wlan","RS-44",10,87.8),recorder.lastRequest);visible.value=false}
        compose.runOnIdle {assertEquals(0,recorder.stops);assertTrue(recorder.state.value.active)}
    }
    @Test fun dismissingDarkPanelDoesNotStopBackgroundRecording()=exercise(true)
    @Test fun lightPanelRequiresExplicitReceiveSource()=exercise(false)
}
