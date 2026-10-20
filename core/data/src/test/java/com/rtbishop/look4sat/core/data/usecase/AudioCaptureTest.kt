package com.rtbishop.look4sat.core.data.usecase

import com.rtbishop.look4sat.core.domain.audio.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AudioCaptureTest {
    @Test fun decoderAdapterSharesTheHubAndRetainsIts44100HzContract()=runTest {
        val hub=FakeHub(listOf(FloatArray(4800){0.25f},FloatArray(4800){0.25f}))
        val capture=AudioCapture(hub)
        val chunks=capture.audioFlow().toList()
        assertEquals(44_100,capture.sampleRate)
        assertEquals(listOf(AudioConsumer.SSTV),hub.consumers)
        assertEquals(AudioDelivery.RELIABLE,hub.delivery)
        assertEquals(8820,chunks.sumOf {it.size})
        chunks.forEach {samples->samples.forEach {assertEquals(0.25f,it,0.0001f)}}
    }

    @Test fun captureRateChangeFlushesThePreviousFilterWithoutDroppingItsTail()=runTest {
        val hub=FakeHub(listOf(FloatArray(4800){0.25f},FloatArray(2400){0.5f}),listOf(48_000,24_000))
        val chunks=AudioCapture(hub).audioFlow().toList()
        assertEquals(8820,chunks.sumOf {it.size})
        assertTrue(chunks.first().all {kotlin.math.abs(it-0.25f)<0.0001f})
        assertTrue(chunks.last().all {kotlin.math.abs(it-0.5f)<0.0001f})
    }

    private class FakeHub(private val blocks: List<FloatArray>,private val rates:List<Int> = List(blocks.size){48_000}): IAudioHub {
        val consumers=mutableListOf<AudioConsumer>()
        var delivery: AudioDelivery?=null
        override val state=MutableStateFlow<AudioHubState>(AudioHubState.Idle)
        override val inputDevices=MutableStateFlow(emptyList<AudioInputDevice>())
        override fun audioFlow(consumer: AudioConsumer,delivery: AudioDelivery):Flow<TimestampedAudioChunk> {
            consumers+=consumer;this.delivery=delivery
            return flowOf(*blocks.mapIndexed {i,samples->TimestampedAudioChunk(samples,rates[i],i*4800L,i*100_000_000L,AudioSampleFormat.PCM_FLOAT)}.toTypedArray())
        }
        override suspend fun selectInputDevice(deviceKey: String?)=Unit
        override suspend fun stopAll()=Unit
    }
}
