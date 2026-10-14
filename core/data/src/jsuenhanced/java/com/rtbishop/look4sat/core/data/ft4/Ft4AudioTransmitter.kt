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

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmitState
import com.rtbishop.look4sat.core.domain.ft4.Ft4PlaybackTiming
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmissionRequest
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmissionProgress
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmissionResult
import com.rtbishop.look4sat.core.domain.ft4.IFt4AudioTransmitter
import com.rtbishop.look4sat.core.domain.ft4.IFt4Service
import com.rtbishop.look4sat.core.domain.ft4.IFt4TransmitCoordinator
import com.rtbishop.look4sat.core.domain.ft4.TxLease
import com.rtbishop.look4sat.core.domain.ft4.TxRequest
import com.rtbishop.look4sat.core.domain.time.ClockSnapshot
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class Ft4AudioTransmitter(
    context: Context?,
    private val ft4Service: IFt4Service,
    private val coordinator: IFt4TransmitCoordinator,
    private val clock: IDisciplinedClock,
    outputFactory: Ft4AudioOutputFactory? = null
) : IFt4AudioTransmitter {
    private val outputFactory = outputFactory ?: AndroidFt4AudioOutputFactory(requireNotNull(context))
    private val executionMutex = Mutex()
    private val mutableState = MutableStateFlow<Ft4TransmitState>(Ft4TransmitState.Idle)
    private val mutablePlaybackTiming = MutableStateFlow(Ft4PlaybackTiming())
    @Volatile private var activeJob: Job? = null

    override val state: StateFlow<Ft4TransmitState> = mutableState.asStateFlow()
    override val playbackTiming: StateFlow<Ft4PlaybackTiming> = mutablePlaybackTiming.asStateFlow()

    override fun recommendedSchedulingLeadMillis(): Long =
        coordinator.recommendedPrepareLeadMillis() + SCHEDULING_MARGIN_MILLIS

    override suspend fun transmit(request: Ft4TransmissionRequest) = executionMutex.withLock {
        check(activeJob == null) { "FT4 transmitter is already active" }
        activeJob = coroutineContext[Job]
        var lease: TxLease? = null
        var output: Ft4AudioOutput? = null
        try {
            require(request.audioFrequencyHz in 0f..3_000f) { "FT4 audio frequency is outside 0-3000 Hz" }
            require(request.volume in 0f..1f) { "FT4 output volume is outside 0-1" }
            checkAutomaticClock(request)
            val timing = TransmissionTiming.from(clock.snapshot(), request.slotStartUtcMillis)
            val prepareLeadMillis = coordinator.recommendedPrepareLeadMillis()
            Log.i(
                TAG,
                "Scheduled slot=${request.slotStartUtcMillis}, lead=${prepareLeadMillis}ms, " +
                    "anchorUtc=${timing.anchorUtcMillis}, anchorMono=${timing.anchorMonotonicNanos}"
            )
            mutableState.value = Ft4TransmitState.Preparing(request.message, request.slotStartUtcMillis)
            notifyProgress(request, Ft4TransmissionResult.PREPARING)
            val waveform = ft4Service.generateWaveform(
                message = request.message,
                audioFrequencyHz = request.audioFrequencyHz,
                outputSampleRate = OUTPUT_SAMPLE_RATE
            )
            check(waveform.size == EXPECTED_WAVEFORM_SAMPLES) { "Unexpected FT4 waveform length" }
            check(waveform.all(Float::isFinite)) { "FT4 waveform contains invalid samples" }
            output = outputFactory.create(OUTPUT_SAMPLE_RATE, request.volume)
            output.prepare(waveform)

            waitUntil(request, timing.before(prepareLeadMillis))
            checkCurrentIntent(request)
            checkAutomaticClock(request)
            checkNotMissed(timing, "before radio preparation")
            lease = coordinator.beginTransmit(
                TxRequest(
                    sessionGeneration = request.sessionGeneration,
                    waveformStartUtcMillis = request.slotStartUtcMillis,
                    waveformDurationMillis = WAVEFORM_DURATION_MILLIS,
                    expectedSatelliteCatalogNumber = request.satelliteCatalogNumber,
                    expectedTransponderUuid = request.transponderUuid,
                    automatic = request.automatic
                )
            )
            checkCurrentIntent(request)
            checkAutomaticClock(request)
            checkNotMissed(timing, "before PTT")
            coordinator.confirmTransmitReady(lease)
            Log.i(TAG, "PTT confirmed with ${timing.remainingMillis(clock.snapshot())}ms remaining")
            waitUntil(request, timing)
            checkCurrentIntent(request)
            checkAutomaticClock(request)
            checkNotMissed(timing, "before audio playback")
            mutableState.value = Ft4TransmitState.Transmitting(request.message, lease)
            notifyProgress(request, Ft4TransmissionResult.STARTED)
            val playCallErrorMillis = -timing.remainingMillis(clock.snapshot())
            val playback = output.play(waveform)
            mutablePlaybackTiming.value = Ft4PlaybackTiming(
                requestedSlotUtcMillis = request.slotStartUtcMillis,
                startErrorMillis = playback.startDelayMillis?.plus(playCallErrorMillis),
                underrunCount = playback.underrunCount
            )
            Log.i(
                TAG,
                "Playback completed: callError=${playCallErrorMillis}ms, " +
                    "startDelay=${playback.startDelayMillis}ms, underruns=${playback.underrunCount}"
            )
            notifyProgress(request, Ft4TransmissionResult.COMPLETED)
        } catch (cancelled: CancellationException) {
            notifyProgress(request, Ft4TransmissionResult.FAILED, cancelled.message ?: "Cancelled")
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Transmit failed: ${error.message}", error)
            mutableState.value = Ft4TransmitState.Failed(error.message ?: error.javaClass.simpleName)
            notifyProgress(request, Ft4TransmissionResult.FAILED, error.message ?: error.javaClass.simpleName)
            throw error
        } finally {
            withContext(NonCancellable) {
                runCatching { output?.close() }
                val released = runCatching {
                    if (lease != null) coordinator.endTransmit(lease) else coordinator.emergencyPttOff()
                }.isSuccess
                if (!released) runCatching { coordinator.emergencyPttOff() }
            }
            activeJob = null
            if (mutableState.value !is Ft4TransmitState.Failed) {
                mutableState.value = Ft4TransmitState.Idle
            }
        }
    }

    override suspend fun stop() {
        val job = activeJob
        if (job != null && job != currentCoroutineContext()[Job]) job.cancelAndJoin()
        coordinator.emergencyPttOff()
        mutableState.value = Ft4TransmitState.Idle
    }

    override suspend fun emergencyStop() {
        activeJob?.cancelAndJoin()
        coordinator.emergencyPttOff()
        mutableState.value = Ft4TransmitState.Idle
    }

    private suspend fun waitUntil(request: Ft4TransmissionRequest, timing: TransmissionTiming) {
        while (true) {
            currentCoroutineContext().ensureActive()
            checkAutomaticClock(request)
            val remaining = timing.remainingMillis(clock.snapshot())
            if (remaining <= 0L) return
            mutableState.value = Ft4TransmitState.Waiting(request.message, remaining)
            delay(remaining.coerceAtMost(WAIT_UPDATE_MILLIS))
        }
    }

    private fun checkAutomaticClock(request: Ft4TransmissionRequest) {
        if (request.automatic) {
            check(clock.automaticFt4TransmitAllowed()) {
                clock.automaticFt4TransmitBlockReason()
            }
        }
    }

    private fun checkNotMissed(timing: TransmissionTiming, phase: String) {
        val latenessMillis = -timing.remainingMillis(clock.snapshot())
        check(latenessMillis <= MAX_START_LATENESS_MILLIS) {
            "FT4 transmit slot was missed $phase by ${latenessMillis}ms"
        }
    }

    private fun checkCurrentIntent(request: Ft4TransmissionRequest) {
        check(!request.automatic || request.isStillCurrent()) {
            "FT4 automatic transmit intent became stale"
        }
    }

    private fun notifyProgress(
        request: Ft4TransmissionRequest,
        result: Ft4TransmissionResult,
        detail: String = ""
    ) {
        runCatching { request.onProgress(Ft4TransmissionProgress(clock.nowMillis(), result, detail)) }
    }

    private companion object {
        const val TAG = "Ft4AudioTransmitter"
        const val OUTPUT_SAMPLE_RATE = 48_000
        const val EXPECTED_WAVEFORM_SAMPLES = 241_920
        const val WAVEFORM_DURATION_MILLIS = 5_040L
        const val MAX_START_LATENESS_MILLIS = 150L
        const val WAIT_UPDATE_MILLIS = 100L
        const val SCHEDULING_MARGIN_MILLIS = 500L
    }
}

private data class TransmissionTiming(
    val anchorUtcMillis: Long,
    val anchorMonotonicNanos: Long,
    val targetMonotonicNanos: Long
) {
    fun before(milliseconds: Long) = copy(
        targetMonotonicNanos = targetMonotonicNanos - milliseconds * NANOS_PER_MILLISECOND
    )

    fun remainingMillis(snapshot: ClockSnapshot): Long =
        (targetMonotonicNanos - snapshot.monotonicNanos) / NANOS_PER_MILLISECOND

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        fun from(
            snapshot: ClockSnapshot,
            targetUtcMillis: Long
        ) = TransmissionTiming(
            anchorUtcMillis = snapshot.utcMillis,
            anchorMonotonicNanos = snapshot.monotonicNanos,
            targetMonotonicNanos = snapshot.monotonicNanos +
                (targetUtcMillis - snapshot.utcMillis) * NANOS_PER_MILLISECOND
        )
    }
}

fun interface Ft4AudioOutputFactory {
    fun create(sampleRate: Int, volume: Float): Ft4AudioOutput
}

interface Ft4AudioOutput : AutoCloseable {
    suspend fun prepare(samples: FloatArray) = Unit
    suspend fun play(samples: FloatArray): Ft4AudioPlaybackResult
}

data class Ft4AudioPlaybackResult(val startDelayMillis: Double?, val underrunCount: Int)

private class AndroidFt4AudioOutputFactory(context: Context) : Ft4AudioOutputFactory {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun create(sampleRate: Int, volume: Float): Ft4AudioOutput =
        AndroidFt4AudioOutput(audioManager, sampleRate, volume)
}

private class AndroidFt4AudioOutput(
    private val audioManager: AudioManager,
    private val sampleRate: Int,
    private val volume: Float
) : Ft4AudioOutput {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var preparedCount = 0

    override suspend fun prepare(samples: FloatArray) = withContext(Dispatchers.IO) {
        check(requestAudioFocus()) { "Audio focus was denied" }
        // The entire 5.04-second waveform is ready before PTT. A static buffer
        // avoids streaming starvation and an underrun at the end of the clip.
        val floatTrack = buildTrack(AudioFormat.ENCODING_PCM_FLOAT, samples.size)
        if (floatTrack != null) {
            track = floatTrack
            preparedCount = writeFloatChunk(floatTrack, samples, 0, samples.size)
        } else {
            val pcm16Track = checkNotNull(buildTrack(AudioFormat.ENCODING_PCM_16BIT, samples.size)) {
                "AudioTrack could not be initialized"
            }
            track = pcm16Track
            preparedCount = writePcm16Chunk(pcm16Track, samples, 0, samples.size)
        }
        check(preparedCount == samples.size) { "AudioTrack preload incomplete: $preparedCount/${samples.size}" }
    }

    override suspend fun play(samples: FloatArray): Ft4AudioPlaybackResult = withContext(Dispatchers.IO) {
        if (track == null) prepare(samples)
        val audioTrack = checkNotNull(track)
        check(preparedCount == samples.size) { "AudioTrack waveform was not fully prepared" }
        val playRequestNanos = System.nanoTime()
        audioTrack.play()
        val startDelayMillis = waitForPlayback(audioTrack, samples.size, playRequestNanos)
        val underruns = audioTrack.underrunCount
        check(underruns == 0) { "AudioTrack underrun" }
        Ft4AudioPlaybackResult(startDelayMillis, underruns)
    }

    private fun buildTrack(encoding: Int, sampleCount: Int): AudioTrack? {
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val candidate = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(sampleCount * bytesPerSample)
                .build()
        }.getOrNull()
        if (candidate == null || candidate.state == AudioTrack.STATE_UNINITIALIZED) {
            candidate?.release()
            return null
        }
        candidate.setVolume(volume)
        return candidate
    }

    private fun writeFloatChunk(track: AudioTrack, samples: FloatArray, offset: Int, maximum: Int): Int =
        track.write(samples, offset, minOf(maximum, samples.size - offset), AudioTrack.WRITE_BLOCKING)

    private fun writePcm16Chunk(track: AudioTrack, samples: FloatArray, offset: Int, maximum: Int): Int {
        val count = minOf(maximum, samples.size - offset)
        val buffer = ShortArray(count)
        for (index in 0 until count) {
            buffer[index] = (samples[offset + index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return track.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING)
    }

    private suspend fun waitForPlayback(
        audioTrack: AudioTrack,
        sampleCount: Int,
        playRequestNanos: Long
    ): Double? {
        val deadline = SystemClock.elapsedRealtime() + sampleCount * 1_000L / sampleRate + PLAYBACK_DRAIN_GRACE_MILLIS
        var startDelayMillis: Double? = null
        val timestamp = AudioTimestamp()
        while (audioTrack.playbackHeadPosition.toLong() < sampleCount) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() < deadline) { "AudioTrack playback timed out" }
            if (startDelayMillis == null && audioTrack.getTimestamp(timestamp)) {
                val firstFrameNanos = timestamp.nanoTime - timestamp.framePosition * 1_000_000_000L / sampleRate
                // Some Android audio HALs report a timestamp just before play() while
                // priming a static buffer. Playback cannot physically start before the call.
                startDelayMillis = ((firstFrameNanos - playRequestNanos) / 1_000_000.0)
                    .coerceAtLeast(0.0)
            }
            delay(PLAYBACK_POLL_MILLIS)
        }
        return startDelayMillis
    }

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    override fun close() {
        val current = track
        track = null
        runCatching { current?.pause() }
        runCatching { current?.flush() }
        runCatching { current?.stop() }
        runCatching { current?.release() }
        if (focusHeld) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let(audioManager::abandonAudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
        focusRequest = null
        focusHeld = false
        preparedCount = 0
    }

    private companion object {
        const val PLAYBACK_POLL_MILLIS = 10L
        const val PLAYBACK_DRAIN_GRACE_MILLIS = 1_000L
    }
}
