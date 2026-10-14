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

import com.rtbishop.look4sat.core.domain.ft4.Ft4Capability
import com.rtbishop.look4sat.core.domain.ft4.Ft4DecodeResult
import com.rtbishop.look4sat.core.domain.ft4.Ft4DecoderOptions
import com.rtbishop.look4sat.core.domain.ft4.Ft4EngineState
import com.rtbishop.look4sat.core.domain.ft4.Ft4MessageValidation
import com.rtbishop.look4sat.core.domain.ft4.Ft4SpectrumFrame
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmissionRequest
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmissionProgress
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmissionResult
import com.rtbishop.look4sat.core.domain.ft4.IFt4Service
import com.rtbishop.look4sat.core.domain.ft4.IFt4TransmitCoordinator
import com.rtbishop.look4sat.core.domain.ft4.TxLease
import com.rtbishop.look4sat.core.domain.ft4.TxRequest
import com.rtbishop.look4sat.core.domain.time.ClockSnapshot
import com.rtbishop.look4sat.core.domain.time.ClockSource
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import com.rtbishop.look4sat.core.domain.time.ClockSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Ft4AudioTransmitterTest {
    @Test
    fun pttIsConfirmedBeforeWaveformAndReleasedAfterwards() = runTest {
        val events = mutableListOf<String>()
        val progress = mutableListOf<Ft4TransmissionProgress>()
        val coordinator = FakeCoordinator(events)
        val transmitter = Ft4AudioTransmitter(
            context = null,
            ft4Service = FakeFt4Service(events),
            coordinator = coordinator,
            clock = FakeClock({ testScheduler.currentTime }, allowed = true),
            outputFactory = Ft4AudioOutputFactory { _, _ -> FakeOutput(events) }
        )

        transmitter.transmit(request(slotStart = 1_000L, automatic = true).copy(onProgress = progress::add))

        assertEquals(listOf("generate", "output", "prepare", "begin", "confirm", "play", "close", "end"), events)
        assertEquals(
            listOf(Ft4TransmissionResult.PREPARING, Ft4TransmissionResult.STARTED, Ft4TransmissionResult.COMPLETED),
            progress.map { it.result }
        )
        assertEquals(1, coordinator.offCount)
    }

    @Test
    fun preloadFailureNeverKeysPttAndClosesOutput() = runTest {
        val events = mutableListOf<String>()
        val progress = mutableListOf<Ft4TransmissionProgress>()
        val transmitter = Ft4AudioTransmitter(
            context = null,
            ft4Service = FakeFt4Service(events),
            coordinator = FakeCoordinator(events),
            clock = FakeClock({ testScheduler.currentTime }, allowed = true),
            outputFactory = Ft4AudioOutputFactory { _, _ -> FakeOutput(events, failPrepare = true) }
        )

        val failure = runCatching {
            transmitter.transmit(request(1_000L, automatic = false).copy(onProgress = progress::add))
        }.exceptionOrNull()

        assertEquals("preload failed", failure?.message)
        assertEquals(listOf("generate", "output", "prepare", "close", "emergency"), events)
        assertEquals(Ft4TransmissionResult.FAILED, progress.last().result)
    }

    @Test
    fun playbackFailureStillEndsLease() = runTest {
        val events = mutableListOf<String>()
        val progress = mutableListOf<Ft4TransmissionProgress>()
        val coordinator = FakeCoordinator(events)
        val transmitter = Ft4AudioTransmitter(
            context = null,
            ft4Service = FakeFt4Service(events),
            coordinator = coordinator,
            clock = FakeClock({ testScheduler.currentTime }, allowed = true),
            outputFactory = Ft4AudioOutputFactory { _, _ -> FakeOutput(events, fail = true) }
        )

        val failure = runCatching {
            transmitter.transmit(request(1_000L, automatic = false).copy(onProgress = progress::add))
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("audio failed") == true)
        assertEquals(Ft4TransmissionResult.FAILED, progress.last().result)
        assertEquals(1, coordinator.offCount)
        assertTrue(events.indexOf("close") < events.indexOf("end"))
    }

    @Test
    fun stopCancelsWaitingTransmissionAndForcesPttOff() = runTest {
        val events = mutableListOf<String>()
        val coordinator = FakeCoordinator(events)
        val transmitter = Ft4AudioTransmitter(
            context = null,
            ft4Service = FakeFt4Service(events),
            coordinator = coordinator,
            clock = FakeClock({ testScheduler.currentTime }, allowed = true),
            outputFactory = Ft4AudioOutputFactory { _, _ -> FakeOutput(events) }
        )
        val transmission = async { transmitter.transmit(request(10_000L, automatic = false)) }
        runCurrent()
        advanceTimeBy(9_700L)
        runCurrent()

        transmitter.stop()

        assertTrue(transmission.isCancelled)
        assertTrue(coordinator.offCount >= 1)
        assertTrue("play" !in events)
    }

    @Test
    fun automaticTransmitRequiresDisciplinedClock() = runTest {
        val events = mutableListOf<String>()
        val transmitter = Ft4AudioTransmitter(
            context = null,
            ft4Service = FakeFt4Service(events),
            coordinator = FakeCoordinator(events),
            clock = FakeClock({ testScheduler.currentTime }, allowed = false),
            outputFactory = Ft4AudioOutputFactory { _, _ -> FakeOutput(events) }
        )

        val failure = runCatching { transmitter.transmit(request(1_000L, automatic = true)) }.exceptionOrNull()

        assertEquals("clock unhealthy", failure?.message)
        assertTrue("generate" !in events)
    }

    @Test
    fun staleAutomaticIntentIsRejectedBeforeCatOrPtt() = runTest {
        val events = mutableListOf<String>()
        var current = true
        val transmitter = Ft4AudioTransmitter(
            context = null,
            ft4Service = FakeFt4Service(events),
            coordinator = FakeCoordinator(events),
            clock = FakeClock({ testScheduler.currentTime }, allowed = true),
            outputFactory = Ft4AudioOutputFactory { _, _ -> FakeOutput(events) }
        )
        val transmission = async {
            runCatching {
                transmitter.transmit(request(1_000L, automatic = true).copy(isStillCurrent = { current }))
            }
        }
        runCurrent()
        current = false
        advanceTimeBy(650L)
        runCurrent()

        val failure = transmission.await().exceptionOrNull()
        assertTrue(failure?.message?.contains("intent became stale") == true)
        assertTrue("begin" !in events)
        assertTrue("confirm" !in events)
        assertTrue("play" !in events)
    }

    @Test
    fun clockCorrectionWhileWaitingDoesNotMoveAnchoredSlot() = runTest {
        val events = mutableListOf<String>()
        var utcOffsetMillis = 0L
        var playMonotonicMillis = -1L
        val transmitter = Ft4AudioTransmitter(
            context = null,
            ft4Service = FakeFt4Service(events),
            coordinator = FakeCoordinator(events, onConfirm = { utcOffsetMillis = 600L }),
            clock = FakeClock(
                monotonicNow = { testScheduler.currentTime },
                allowed = true,
                utcOffsetMillis = { utcOffsetMillis }
            ),
            outputFactory = Ft4AudioOutputFactory { _, _ ->
                FakeOutput(events, onPlay = { playMonotonicMillis = testScheduler.currentTime })
            }
        )

        transmitter.transmit(request(slotStart = 1_000L, automatic = true))

        assertEquals(1_000L, playMonotonicMillis)
        assertTrue("play" in events)
    }

    @Test
    fun missedSlotIsRejectedBeforeCatOrPtt() = runTest {
        val events = mutableListOf<String>()
        val transmitter = Ft4AudioTransmitter(
            context = null,
            ft4Service = FakeFt4Service(events),
            coordinator = FakeCoordinator(events),
            clock = FakeClock({ testScheduler.currentTime }, allowed = true),
            outputFactory = Ft4AudioOutputFactory { _, _ -> FakeOutput(events) }
        )

        val failure = runCatching {
            transmitter.transmit(request(slotStart = -200L, automatic = false))
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("before radio preparation") == true)
        assertTrue("begin" !in events)
        assertTrue("confirm" !in events)
        assertTrue("play" !in events)
    }

    private fun request(slotStart: Long, automatic: Boolean) = Ft4TransmissionRequest(
        message = "CQ N0CALL FN42",
        audioFrequencyHz = 1_500f,
        slotStartUtcMillis = slotStart,
        sessionGeneration = 3,
        sessionId = "test-session",
        satelliteCatalogNumber = 25544,
        transponderUuid = "xpdr",
        automatic = automatic
    )
}

private class FakeOutput(
    private val events: MutableList<String>,
    private val fail: Boolean = false,
    private val failPrepare: Boolean = false,
    private val onPlay: () -> Unit = {}
) : Ft4AudioOutput {
    init { events += "output" }

    override suspend fun prepare(samples: FloatArray) {
        events += "prepare"
        if (failPrepare) error("preload failed")
    }

    override suspend fun play(samples: FloatArray): Ft4AudioPlaybackResult {
        events += "play"
        onPlay()
        if (fail) error("audio failed")
        return Ft4AudioPlaybackResult(4.0, 0)
    }

    override fun close() {
        events += "close"
    }
}

private class FakeCoordinator(
    private val events: MutableList<String>,
    private val onConfirm: () -> Unit = {}
) : IFt4TransmitCoordinator {
    var offCount = 0

    override suspend fun beginTransmit(request: TxRequest): TxLease {
        events += "begin"
        return TxLease(
            id = 1,
            sessionGeneration = request.sessionGeneration,
            effectiveTxFrequencyHz = 145_900_000,
            txDopplerCorrectionHz = 1_000,
            waveformStartUtcMillis = request.waveformStartUtcMillis,
            waveformMidpointUtcMillis = request.waveformStartUtcMillis + request.waveformDurationMillis / 2,
            waveformDurationMillis = request.waveformDurationMillis,
            maximumPttMillis = request.maximumPttMillis,
            expectedSatelliteCatalogNumber = request.expectedSatelliteCatalogNumber,
            expectedTransponderUuid = request.expectedTransponderUuid,
            pttSafetyGeneration = 0L,
            automatic = request.automatic
        )
    }

    override suspend fun confirmTransmitReady(lease: TxLease) {
        events += "confirm"
        onConfirm()
    }

    override suspend fun endTransmit(lease: TxLease) {
        events += "end"
        offCount++
    }

    override suspend fun emergencyPttOff() {
        events += "emergency"
        offCount++
    }
}

private class FakeFt4Service(private val events: MutableList<String>) : IFt4Service {
    override val capability = MutableStateFlow(Ft4Capability(transmitAvailable = true))
    override val engineState = MutableStateFlow<Ft4EngineState>(Ft4EngineState.Idle)
    override val decodeResults = MutableStateFlow<List<Ft4DecodeResult>>(emptyList())
    override val spectrumFrames: Flow<Ft4SpectrumFrame> = emptyFlow()
    override suspend fun refreshCapability(): Ft4Capability = capability.value
    override suspend fun runNativeSelfTest(): Result<Unit> = Result.success(Unit)
    override fun startReceiving(options: Ft4DecoderOptions, myCall: String) = Unit
    override suspend fun stopReceiving(clearResults: Boolean) = Unit
    override fun clearDecodeResults() = Unit
    override suspend fun validateMessage(message: String) = Ft4MessageValidation(true, message)
    override suspend fun generateWaveform(message: String, audioFrequencyHz: Float, outputSampleRate: Int): FloatArray {
        events += "generate"
        return FloatArray(241_920)
    }
}

private class FakeClock(
    private val monotonicNow: () -> Long,
    private val allowed: Boolean,
    private val utcOffsetMillis: () -> Long = { 0L }
) : IDisciplinedClock {
    override val state: StateFlow<ClockSnapshot> = MutableStateFlow(snapshot())
    override fun snapshot() = ClockSnapshot(
        utcMillis = monotonicNow() + utcOffsetMillis(),
        monotonicNanos = monotonicNow() * 1_000_000,
        offsetMillis = utcOffsetMillis().toDouble(),
        driftPpm = 0.0,
        uncertaintyMillis = if (allowed) 10.0 else 5_000.0,
        source = if (allowed) ClockSource.NTP else ClockSource.SYSTEM,
        sampleAgeMillis = 0,
        healthy = allowed
    )
    override fun nowMillis(): Long = monotonicNow() + utcOffsetMillis()
    override fun utcMillisAt(monotonicNanos: Long): Long =
        monotonicNanos / 1_000_000 + utcOffsetMillis()
    override fun submitSample(sample: ClockSample): Boolean = false
    override fun refresh(): ClockSnapshot = snapshot()
    override fun automaticFt4TransmitAllowed(): Boolean = allowed
    override fun automaticFt4TransmitBlockReason(): String = if (allowed) "" else "clock unhealthy"
}
