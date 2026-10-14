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

import com.rtbishop.look4sat.core.domain.audio.AudioSampleFormat
import com.rtbishop.look4sat.core.domain.audio.Ft4AudioSlot
import com.rtbishop.look4sat.core.domain.audio.Ft4SlotAssembler
import com.rtbishop.look4sat.core.domain.audio.TimestampedAudioChunk
import com.rtbishop.look4sat.core.domain.time.ClockSample
import com.rtbishop.look4sat.core.domain.time.ClockSnapshot
import com.rtbishop.look4sat.core.domain.time.ClockSource
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ft4ReceivePipelineTest {

    @Test
    fun `boottime jump drops partial slot and next complete slot recovers`() {
        val timeline = Ft4ResampledTimeline(FixedClock())
        val assembler = Ft4SlotAssembler()

        val beforeSleep = timeline.process(chunk(80_000, 0L, 1_000_000_000L))
        if (beforeSleep.reset) assembler.reset()
        assertTrue(assembler.append(beforeSleep.samples, beforeSleep.firstSampleUtcNanos).isEmpty())
        assertTrue(assembler.bufferedSampleCount > 0)

        val afterWake = timeline.process(chunk(120_000, 80_000L, 20_000_000_000L))
        assertTrue(afterWake.reset)
        assertTrue(afterWake.droppedBlock)
        if (afterWake.reset) assembler.reset()
        val recovered = assembler.append(afterWake.samples, afterWake.firstSampleUtcNanos)

        assertEquals(1, recovered.size)
        assertEquals(22_500L, recovered.single().utcStartMillis)
        assertEquals(Ft4SlotAssembler.SLOT_SAMPLES, recovered.single().samples.size)
    }

    @Test
    fun `sustained decode overload drops stale work and keeps both queues bounded`() = runBlocking {
        var decoded = 0
        var latest = Ft4ReceivePipelineSnapshot()
        var maximumCaptureDepth = 0
        var maximumDecodeDepth = 0
        val slotCount = 40
        val totalSamples = (slotCount + 1) * Ft4SlotAssembler.SLOT_SAMPLES
        val audio = flow {
            var offset = 0
            while (offset < totalSamples) {
                val count = minOf(7_500, totalSamples - offset)
                emit(
                    chunk(
                        sampleCount = count,
                        framePosition = offset.toLong(),
                        bootTimeNanos = offset.toLong() * NANOS_PER_SECOND / SAMPLE_RATE
                    )
                )
                offset += count
                yield()
            }
        }

        Ft4ReceivePipeline(FixedClock()).run(
            audio = audio,
            decodeSlot = { _: Ft4AudioSlot ->
                delay(250L)
                decoded++
                0
            },
            onSnapshot = {
                latest = it
                maximumCaptureDepth = maxOf(maximumCaptureDepth, it.captureQueueDepth)
                maximumDecodeDepth = maxOf(maximumDecodeDepth, it.decodeQueueDepth)
            }
        )

        assertTrue(decoded < slotCount * 2)
        assertTrue(latest.droppedAudioBlocks > 0L || latest.droppedDecodeSlots > 0L)
        assertTrue(maximumCaptureDepth <= 16)
        assertTrue(maximumDecodeDepth <= 2)
        assertEquals(0, latest.captureQueueDepth)
        assertEquals(0, latest.decodeQueueDepth)
    }

    @Test
    fun `clock correction is adopted only at a completed slot boundary`() {
        val clock = AdjustableClock()
        val timeline = Ft4ResampledTimeline(clock)
        val first = timeline.process(chunk(48_000, 0L, 0L))
        clock.offsetMillis = 25L
        val second = timeline.process(chunk(48_000, 48_000L, 4_000_000_000L))

        assertTrue(first.reset)
        assertEquals("initialized", first.resetReason)
        assertTrue(!second.reset)
        assertEquals(25_000_000L, second.clockCorrectionNanos)
        assertEquals(
            first.firstSampleUtcNanos + first.samples.size.toLong() * NANOS_PER_SECOND / SAMPLE_RATE,
            second.firstSampleUtcNanos
        )
        assertTrue(timeline.applyClockCorrectionAtSlotBoundary(second.clockCorrectionNanos))

        val third = timeline.process(chunk(48_000, 96_000L, 8_000_000_000L))
        assertEquals(8_025_000_000L, third.firstSampleUtcNanos)
        assertEquals(0L, third.clockCorrectionNanos)
        assertTrue(!timeline.applyClockCorrectionAtSlotBoundary(third.clockCorrectionNanos))
    }

    private fun chunk(sampleCount: Int, framePosition: Long, bootTimeNanos: Long) = TimestampedAudioChunk(
        samples = FloatArray(sampleCount),
        sampleRate = SAMPLE_RATE,
        framePosition = framePosition,
        elapsedRealtimeNanos = bootTimeNanos,
        format = AudioSampleFormat.PCM_FLOAT
    )

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
        override val state: StateFlow<ClockSnapshot> = mutableState
        override fun snapshot() = snapshot
        override fun nowMillis() = 0L
        override fun utcMillisAt(monotonicNanos: Long) = monotonicNanos / NANOS_PER_MILLISECOND
        override fun submitSample(sample: ClockSample) = true
        override fun refresh() = snapshot
        override fun automaticFt4TransmitAllowed() = true
        override fun automaticFt4TransmitBlockReason() = ""
    }

    private class AdjustableClock : IDisciplinedClock {
        var offsetMillis = 0L
        private val mutableState = MutableStateFlow(snapshot())
        override val state: StateFlow<ClockSnapshot> = mutableState
        override fun snapshot() = ClockSnapshot(
            utcMillis = offsetMillis,
            monotonicNanos = 0L,
            offsetMillis = offsetMillis.toDouble(),
            driftPpm = 0.0,
            uncertaintyMillis = 1.0,
            source = ClockSource.NTP,
            sampleAgeMillis = 0L,
            healthy = true
        )
        override fun nowMillis() = offsetMillis
        override fun utcMillisAt(monotonicNanos: Long) = monotonicNanos / NANOS_PER_MILLISECOND + offsetMillis
        override fun submitSample(sample: ClockSample) = true
        override fun refresh() = snapshot()
        override fun automaticFt4TransmitAllowed() = true
        override fun automaticFt4TransmitBlockReason() = ""
    }

    private companion object {
        const val SAMPLE_RATE = 12_000
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
