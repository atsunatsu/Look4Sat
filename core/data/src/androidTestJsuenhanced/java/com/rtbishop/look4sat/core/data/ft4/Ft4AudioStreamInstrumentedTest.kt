/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.ft4

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtbishop.look4sat.core.domain.audio.AudioConsumer
import com.rtbishop.look4sat.core.domain.audio.AudioDelivery
import com.rtbishop.look4sat.core.domain.audio.AudioHubState
import com.rtbishop.look4sat.core.domain.audio.AudioInputDevice
import com.rtbishop.look4sat.core.domain.audio.AudioSampleFormat
import com.rtbishop.look4sat.core.domain.audio.Ft4SlotAssembler
import com.rtbishop.look4sat.core.domain.audio.IAudioHub
import com.rtbishop.look4sat.core.domain.audio.TimestampedAudioChunk
import com.rtbishop.look4sat.core.domain.ft4.Ft4DecoderOptions
import com.rtbishop.look4sat.core.domain.time.ClockSample
import com.rtbishop.look4sat.core.domain.time.ClockSnapshot
import com.rtbishop.look4sat.core.domain.time.ClockSource
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import com.rtbishop.look4sat.core.ft4.Ft4Native
import com.rtbishop.look4sat.core.ft4.Ft4NativeDecoderOptions
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ft4AudioStreamInstrumentedTest {

    @Test
    fun officialCorpusDecodesThroughReliableAudioStream() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val wavBytes = context.assets.open(SAMPLE_ASSET).use { it.readBytes() }
        assertEquals(EXPECTED_SAMPLE_SHA256, wavBytes.sha256())
        val wav = parsePcm16MonoWav(wavBytes)
        assertEquals(Ft4SlotAssembler.SAMPLE_RATE, wav.sampleRate)
        assertEquals(72_576, wav.samples.size)

        val audioHub = InjectedAudioHub()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = Ft4Service(scope, audioHub, FixedClock())
        try {
            val capability = service.refreshCapability()
            assertTrue(capability.unavailableReason, capability.receiveAvailable)
            assertTrue(service.runNativeSelfTest().isSuccess)
            service.startReceiving(Ft4DecoderOptions(), MY_CALL)
            withTimeout(SUBSCRIPTION_TIMEOUT_MILLIS) {
                audioHub.state.first { it is AudioHubState.Capturing }
            }

            // 首个时隙模拟进入页面时必须丢弃的残缺时隙，官方语料从下一个完整边界开始。
            val stream = FloatArray(Ft4SlotAssembler.SLOT_SAMPLES * 2)
            wav.samples.copyInto(stream, Ft4SlotAssembler.SLOT_SAMPLES)
            var framePosition = 0
            var chunkIndex = 0
            while (framePosition < stream.size) {
                val chunkSize = minOf(
                    AUDIO_CHUNK_PATTERN[chunkIndex % AUDIO_CHUNK_PATTERN.size],
                    stream.size - framePosition
                )
                audioHub.emit(
                    TimestampedAudioChunk(
                        samples = stream.copyOfRange(framePosition, framePosition + chunkSize),
                        sampleRate = Ft4SlotAssembler.SAMPLE_RATE,
                        framePosition = framePosition.toLong(),
                        elapsedRealtimeNanos = framePosition.toLong() * NANOS_PER_SECOND /
                            Ft4SlotAssembler.SAMPLE_RATE,
                        format = AudioSampleFormat.PCM_FLOAT
                    )
                )
                framePosition += chunkSize
                chunkIndex++
            }

            val results = withTimeout(DECODE_TIMEOUT_MILLIS) {
                service.decodeResults.first { it.size == EXPECTED_DECODE_COUNT }
            }
            assertEquals(EXPECTED_DECODE_COUNT, results.size)
            EXPECTED_MESSAGES.forEach { expected ->
                assertTrue("未解码到 $expected", results.any { it.text == expected })
            }
        } finally {
            service.stopReceiving(clearResults = true)
            scope.cancel()
        }
    }

    @Test
    fun officialCorpusSurvivesTwentyFourAndFortyEightKhzResampling() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val wavBytes = context.assets.open(SAMPLE_ASSET).use { it.readBytes() }
        assertEquals(EXPECTED_SAMPLE_SHA256, wavBytes.sha256())
        val wav = parsePcm16MonoWav(wavBytes)
        for (sourceRate in intArrayOf(24_000, 48_000)) {
            decodeResampledCorpus(wav.samples, sourceRate)
        }
    }

    @Test
    fun officialCorpusDecodesFromSixSecondEarlyWindow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val wavBytes = context.assets.open(SAMPLE_ASSET).use { it.readBytes() }
        assertEquals(EXPECTED_SAMPLE_SHA256, wavBytes.sha256())
        val wav = parsePcm16MonoWav(wavBytes)
        val earlySlot = FloatArray(Ft4Native.SLOT_SAMPLE_COUNT)
        wav.samples.copyOf(EARLY_DECODE_SAMPLES).copyInto(earlySlot)
        val decoder = Ft4Native.createDecoder(0L)
        val results = try {
            Ft4Native.decodeFt4Slot(
                decoder = decoder,
                samples12k = earlySlot,
                slotUtcMillis = 0L,
                options = Ft4NativeDecoderOptions(earlyDecodeEnabled = true),
                myCall = MY_CALL
            )
        } finally {
            decoder.close()
        }

        EXPECTED_MESSAGES.forEach { expected ->
            assertTrue("六秒提前窗口未解码到 $expected", results.any { it.text == expected })
        }
    }

    private suspend fun decodeResampledCorpus(samples12k: FloatArray, sourceRate: Int) {
        val audioHub = InjectedAudioHub(sourceRate)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = Ft4Service(scope, audioHub, FixedClock())
        try {
            val capability = service.refreshCapability()
            assertTrue(capability.unavailableReason, capability.receiveAvailable)
            assertTrue(service.runNativeSelfTest().isSuccess)
            service.startReceiving(Ft4DecoderOptions(), MY_CALL)
            withTimeout(SUBSCRIPTION_TIMEOUT_MILLIS) {
                audioHub.state.first { it is AudioHubState.Capturing }
            }

            val sourceSamples = interpolate(samples12k, sourceRate / Ft4SlotAssembler.SAMPLE_RATE)
            val sourceSlotSamples = sourceRate * Ft4SlotAssembler.SLOT_MILLIS.toInt() / 1_000
            val stream = FloatArray(sourceSlotSamples * 2)
            sourceSamples.copyInto(stream, sourceSlotSamples)
            var framePosition = 0
            var chunkIndex = 0
            while (framePosition < stream.size) {
                val chunkSize = minOf(
                    AUDIO_CHUNK_PATTERN[chunkIndex % AUDIO_CHUNK_PATTERN.size],
                    stream.size - framePosition
                )
                audioHub.emit(
                    TimestampedAudioChunk(
                        samples = stream.copyOfRange(framePosition, framePosition + chunkSize),
                        sampleRate = sourceRate,
                        framePosition = framePosition.toLong(),
                        elapsedRealtimeNanos = framePosition.toLong() * NANOS_PER_SECOND / sourceRate,
                        format = AudioSampleFormat.PCM_FLOAT
                    )
                )
                framePosition += chunkSize
                chunkIndex++
            }

            val results = withTimeout(DECODE_TIMEOUT_MILLIS) {
                service.decodeResults.first { it.size == EXPECTED_DECODE_COUNT }
            }
            assertEquals("$sourceRate Hz", EXPECTED_DECODE_COUNT, results.size)
            EXPECTED_MESSAGES.forEach { expected ->
                assertTrue("$sourceRate Hz missing $expected", results.any { it.text == expected })
            }
        } finally {
            service.stopReceiving(clearResults = true)
            scope.cancel()
        }
    }

    private fun interpolate(input: FloatArray, factor: Int): FloatArray {
        return FloatArray(input.size * factor) { index ->
            val sourceIndex = index / factor
            val fraction = (index % factor).toFloat() / factor
            val first = input[sourceIndex]
            val second = input.getOrElse(sourceIndex + 1) { first }
            first + (second - first) * fraction
        }
    }

    private data class WavSamples(val sampleRate: Int, val samples: FloatArray)

    private fun parsePcm16MonoWav(bytes: ByteArray): WavSamples {
        require(bytes.size >= 44)
        require(bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WAVE")
        var offset = 12
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        while (offset + 8 <= bytes.size) {
            val chunkId = bytes.ascii(offset, 4)
            val chunkSize = bytes.leInt(offset + 4)
            val chunkStart = offset + 8
            require(chunkSize >= 0 && chunkStart + chunkSize <= bytes.size)
            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16)
                    format = bytes.leShort(chunkStart)
                    channels = bytes.leShort(chunkStart + 2)
                    sampleRate = bytes.leInt(chunkStart + 4)
                    bitsPerSample = bytes.leShort(chunkStart + 14)
                }
                "data" -> {
                    require(format == 1 && channels == 1 && bitsPerSample == 16)
                    val samples = FloatArray(chunkSize / 2) { index ->
                        bytes.leShort(chunkStart + index * 2).toShort() / 32768f
                    }
                    return WavSamples(sampleRate, samples)
                }
            }
            offset = chunkStart + chunkSize + (chunkSize and 1)
        }
        error("WAV 中没有 PCM 数据")
    }

    private fun ByteArray.ascii(offset: Int, length: Int) =
        String(this, offset, length, StandardCharsets.US_ASCII)

    private fun ByteArray.leShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.leInt(offset: Int): Int =
        leShort(offset) or (leShort(offset + 2) shl 16)

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private class InjectedAudioHub(private val sampleRate: Int = Ft4SlotAssembler.SAMPLE_RATE) : IAudioHub {
        private val chunks = Channel<TimestampedAudioChunk>(Channel.UNLIMITED)
        private val mutableState = MutableStateFlow<AudioHubState>(AudioHubState.Idle)
        override val state: StateFlow<AudioHubState> = mutableState.asStateFlow()
        override val inputDevices = MutableStateFlow<List<AudioInputDevice>>(emptyList()).asStateFlow()

        override suspend fun selectInputDevice(deviceKey: String?) = Unit

        override fun audioFlow(consumer: AudioConsumer, delivery: AudioDelivery): Flow<TimestampedAudioChunk> = flow {
            mutableState.value = AudioHubState.Capturing(
                sampleRate = sampleRate,
                format = AudioSampleFormat.PCM_FLOAT,
                consumers = setOf(consumer)
            )
            chunks.receiveAsFlow().collect { emit(it) }
        }

        suspend fun emit(chunk: TimestampedAudioChunk) {
            chunks.send(chunk)
        }

        override suspend fun stopAll() {
            mutableState.value = AudioHubState.Idle
        }
    }

    private class FixedClock : IDisciplinedClock {
        private val snapshot = ClockSnapshot(
            utcMillis = 0L,
            monotonicNanos = 0L,
            offsetMillis = 0.0,
            driftPpm = 0.0,
            uncertaintyMillis = 1.0,
            source = ClockSource.GNSS,
            sampleAgeMillis = 0L,
            healthy = true
        )
        private val mutableState = MutableStateFlow(snapshot)
        override val state: StateFlow<ClockSnapshot> = mutableState.asStateFlow()
        override fun snapshot(): ClockSnapshot = snapshot
        override fun nowMillis(): Long = 0L
        override fun utcMillisAt(monotonicNanos: Long): Long = monotonicNanos / 1_000_000L
        override fun submitSample(sample: ClockSample): Boolean = true
        override fun refresh(): ClockSnapshot = snapshot
        override fun automaticFt4TransmitAllowed(): Boolean = true
        override fun automaticFt4TransmitBlockReason(): String = ""
    }

    private companion object {
        const val SAMPLE_ASSET = "ft4_official_000000_000002.wav"
        const val EXPECTED_SAMPLE_SHA256 = "d9e91fa04ba138a7b9f41b4103823c77ca1c3a9775101f6b14d60935bcd3813b"
        const val EXPECTED_DECODE_COUNT = 16
        const val EARLY_DECODE_SAMPLES = 72_000
        const val MY_CALL = "BG5JSU"
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 15_000L
        const val DECODE_TIMEOUT_MILLIS = 90_000L
        val EXPECTED_MESSAGES = listOf(
            "N1TRK N4FKH 569 VA",
            "CQ RU N9OY EN43",
            "CQ RU W0FRC DM79"
        )
        val AUDIO_CHUNK_PATTERN = intArrayOf(137, 480, 1024, 311, 2048)
    }
}
