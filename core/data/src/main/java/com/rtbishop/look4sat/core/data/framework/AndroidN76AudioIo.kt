/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.core.data.framework

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.rtbishop.look4sat.core.domain.audio.AudioConsumer
import com.rtbishop.look4sat.core.domain.audio.AudioDelivery
import com.rtbishop.look4sat.core.domain.audio.IAudioHub
import com.rtbishop.look4sat.core.domain.audio.StreamingAudioResampler
import com.rtbishop.look4sat.core.domain.model.N76ControlOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class AndroidN76AudioIo(private val context: Context, private val hub: IAudioHub,
    private val options: () -> N76ControlOptions = { N76ControlOptions() }) : N76AudioIo {
    private var track: AudioTrack? = null
    private val manager = context.getSystemService(AudioManager::class.java)
    private var focusHeld = false
    private var interrupted: (() -> Unit)? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        if (focus < 0) { runCatching { track?.pause() }; interrupted?.invoke() }
        else if (focus == AudioManager.AUDIOFOCUS_GAIN) runCatching { track?.play() }
    }
    @Suppress("DEPRECATION")
    override fun openPlayback(): Boolean {
        val minimum = AudioTrack.getMinBufferSize(32_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return false
        focusHeld = manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusHeld) return false
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(32_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, 6_400)).build()
        if (track?.state != AudioTrack.STATE_INITIALIZED) { closePlayback(); return false }
        track?.play()
        return true
    }
    override fun play(pcm: ShortArray) {
        if (!options().speakerMonitor) return
        val current = track ?: return
        // Nonblocking output bounds latency: no unbounded speaker backlog on a stalled device.
        check(current.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING) >= 0) { "Phone audio output failed" }
    }
    @Suppress("DEPRECATION")
    override fun closePlayback() {
        val current = track; track = null
        runCatching { current?.pause(); current?.flush() }
        runCatching { current?.release() }
        if (focusHeld) runCatching { manager.abandonAudioFocus(focusListener) }
        focusHeld = false
    }
    override fun hasMicrophonePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    override fun onInterrupted(callback: () -> Unit) { interrupted = callback }

    override fun microphone(): Flow<ShortArray> = flow {
        check(hasMicrophonePermission()) { "Microphone permission is required" }
        options().inputDeviceKey?.let { hub.selectInputDevice(it) }
        var resampler: StreamingAudioResampler? = null
        hub.audioFlow(AudioConsumer.N76_VOICE, AudioDelivery.LATEST).collect { chunk ->
            if (resampler?.inputRate != chunk.sampleRate) resampler = StreamingAudioResampler(chunk.sampleRate, 32_000)
            val samples = resampler.process(chunk.samples)
            emit(ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() })
        }
    }
}
