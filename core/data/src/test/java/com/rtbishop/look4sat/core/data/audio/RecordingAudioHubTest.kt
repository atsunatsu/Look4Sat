package com.rtbishop.look4sat.core.data.audio

import com.rtbishop.look4sat.core.domain.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingAudioHubTest {
    @Test fun pageShutdownCancelsDecodersButKeepsTheRecordingSource()=runTest {
        var physicalStops=0;var active=true
        val fake=object:IAudioHub {
            override val state=MutableStateFlow<AudioHubState>(AudioHubState.Idle)
            override val inputDevices=MutableStateFlow(emptyList<AudioInputDevice>())
            override fun audioFlow(consumer:AudioConsumer,delivery:AudioDelivery)=flow<TimestampedAudioChunk> {awaitCancellation()}
            override suspend fun selectInputDevice(deviceKey:String?)=Unit
            override suspend fun stopAll() {physicalStops++}
        }
        val hub=RecordingAudioHub(fake) {active}
        val decode=backgroundScope.launch {hub.audioFlow(AudioConsumer.CW).collect()}
        val record=backgroundScope.launch {hub.audioFlow(AudioConsumer.RECORDING).collect()}
        runCurrent();hub.stopAll();runCurrent()
        assertTrue(decode.isCancelled);assertTrue(record.isActive);assertEquals(0,physicalStops)
        record.cancelAndJoin();active=false;hub.stopAll();assertEquals(1,physicalStops)
    }
}
