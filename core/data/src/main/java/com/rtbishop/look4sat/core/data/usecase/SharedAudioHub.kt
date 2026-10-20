/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.usecase

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rtbishop.look4sat.core.domain.audio.AudioConsumer
import com.rtbishop.look4sat.core.domain.audio.AudioDelivery
import com.rtbishop.look4sat.core.domain.audio.AudioHubState
import com.rtbishop.look4sat.core.domain.audio.AudioInputDevice
import com.rtbishop.look4sat.core.domain.audio.AudioSampleFormat
import com.rtbishop.look4sat.core.domain.audio.IAudioHub
import com.rtbishop.look4sat.core.domain.audio.Pcm16Converter
import com.rtbishop.look4sat.core.domain.audio.TimestampedAudioChunk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val AUDIO_TIMESTAMP_TIMEBASE = AudioTimestamp.TIMEBASE_BOOTTIME

class SharedAudioHub(
    context: Context,
    private val scope: CoroutineScope,
    initialDeviceKey: String? = null
) : IAudioHub {
    private data class Candidate(
        val sampleRate: Int,
        val encoding: Int,
        val source: Int,
        val format: AudioSampleFormat
    )

    private data class Subscriber(
        val consumer: AudioConsumer,
        val delivery: AudioDelivery,
        val channel: SendChannel<TimestampedAudioChunk>
    )

    private data class OpenedRecorder(
        val recorder: AudioRecord,
        val candidate: Candidate
    )

    private val lifecycleMutex = Mutex()
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val nextSubscriberId = AtomicLong(1L)
    private val subscribers = ConcurrentHashMap<Long, Subscriber>()
    private val _state = MutableStateFlow<AudioHubState>(AudioHubState.Idle)
    private val _inputDevices = MutableStateFlow(readInputDevices())
    @Volatile
    private var preferredDeviceKey = initialDeviceKey
    private var captureJob: Job? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshInputDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshInputDevices()
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    }

    override val state: StateFlow<AudioHubState> = _state.asStateFlow()
    override val inputDevices: StateFlow<List<AudioInputDevice>> = _inputDevices.asStateFlow()

    override fun audioFlow(
        consumer: AudioConsumer,
        delivery: AudioDelivery
    ): Flow<TimestampedAudioChunk> = callbackFlow {
        val id = nextSubscriberId.getAndIncrement()
        register(id, Subscriber(consumer, delivery, channel))
        awaitClose { scope.launch { unregister(id) } }
    }.buffer(
        capacity = if (delivery == AudioDelivery.LATEST) Channel.CONFLATED else RELIABLE_BUFFER_CHUNKS
    )

    override suspend fun stopAll() {
        lifecycleMutex.withLock {
            val job = captureJob
            captureJob = null
            subscribers.values.forEach { it.channel.close() }
            subscribers.clear()
            job?.cancelAndJoin()
            _state.value = AudioHubState.Idle
        }
    }

    override suspend fun selectInputDevice(deviceKey: String?) {
        lifecycleMutex.withLock {
            preferredDeviceKey = deviceKey
            val job = captureJob
            captureJob = null
            job?.cancelAndJoin()
            if (subscribers.isNotEmpty()) {
                captureJob = scope.launch(Dispatchers.IO) { captureAudio() }
            } else {
                _state.value = AudioHubState.Idle
            }
        }
    }

    private suspend fun register(id: Long, subscriber: Subscriber) {
        lifecycleMutex.withLock {
            subscribers[id] = subscriber
            val activeJob = captureJob
            if (activeJob == null || !activeJob.isActive) {
                activeJob?.join()
                captureJob = scope.launch(Dispatchers.IO) { captureAudio() }
            } else {
                updateConsumersInState()
            }
        }
    }

    private suspend fun unregister(id: Long) {
        lifecycleMutex.withLock {
            subscribers.remove(id)
            if (subscribers.isEmpty()) {
                val job = captureJob
                captureJob = null
                job?.cancelAndJoin()
                _state.value = AudioHubState.Idle
            } else {
                updateConsumersInState()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureAudio() {
        var recorder: AudioRecord? = null
        try {
            val opened = openRecorder()
            recorder = opened.recorder
            val candidate = opened.candidate
            val routedDevice = recorder.routedDevice
            _state.value = AudioHubState.Capturing(
                sampleRate = candidate.sampleRate,
                format = candidate.format,
                consumers = activeConsumers(),
                deviceId = routedDevice?.id,
                deviceType = routedDevice?.type,
                deviceName = routedDevice?.productName?.toString().orEmpty()
            )
            readLoop(recorder, candidate)
        } catch (error: Exception) {
            if (currentCoroutineContext().isActive) {
                _state.value = AudioHubState.Failed(error.message ?: error.javaClass.simpleName)
                // 录音资源暂不可用是可恢复状态，不能把异常抛入界面收集协程导致应用退出。
                subscribers.values.forEach { it.channel.close() }
                subscribers.clear()
            }
        } finally {
            recorder?.let { activeRecorder ->
                runCatching {
                    if (activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        activeRecorder.stop()
                    }
                }
                activeRecorder.release()
            }
        }
    }

    private suspend fun readLoop(recorder: AudioRecord, candidate: Candidate) {
        val chunkFrames = candidate.sampleRate / CHUNKS_PER_SECOND
        val floatBuffer = FloatArray(chunkFrames)
        val shortBuffer = ShortArray(chunkFrames)
        var framePosition = 0L
        while (currentCoroutineContext().isActive) {
            val count = if (candidate.format == AudioSampleFormat.PCM_FLOAT) {
                recorder.read(floatBuffer, 0, floatBuffer.size, AudioRecord.READ_BLOCKING)
            } else {
                recorder.read(shortBuffer, 0, shortBuffer.size, AudioRecord.READ_BLOCKING)
            }
            check(count >= 0) { "AudioRecord.read 失败: $count" }
            if (count == 0) continue
            updateSystemSilenced(recorder)

            val samples = if (candidate.format == AudioSampleFormat.PCM_FLOAT) {
                floatBuffer.copyOf(count)
            } else {
                Pcm16Converter.toFloat(shortBuffer, count)
            }
            val chunk = TimestampedAudioChunk(
                samples = samples,
                sampleRate = candidate.sampleRate,
                framePosition = framePosition,
                elapsedRealtimeNanos = firstFrameTimestampNanos(
                    recorder = recorder,
                    firstFramePosition = framePosition,
                    framesRead = count,
                    sampleRate = candidate.sampleRate
                ),
                format = candidate.format
            )
            framePosition += count
            publish(chunk)
        }
    }

    private suspend fun publish(chunk: TimestampedAudioChunk) {
        subscribers.values.toList().forEach { subscriber ->
            try {
                if (subscriber.delivery == AudioDelivery.RELIABLE) {
                    subscriber.channel.send(chunk)
                } else {
                    subscriber.channel.trySend(chunk)
                }
            } catch (_: ClosedSendChannelException) {
                // awaitClose 会移除刚刚结束的订阅者，不影响其他可靠消费者。
            }
        }
    }

    private fun firstFrameTimestampNanos(
        recorder: AudioRecord,
        firstFramePosition: Long,
        framesRead: Int,
        sampleRate: Int
    ): Long {
        val timestamp = AudioTimestamp()
        if (recorder.getTimestamp(timestamp, AUDIO_TIMESTAMP_TIMEBASE) == AudioRecord.SUCCESS) {
            val frameDelta = timestamp.framePosition - firstFramePosition
            return timestamp.nanoTime - frameDelta * NANOS_PER_SECOND / sampleRate
        }
        return SystemClock.elapsedRealtimeNanos() - framesRead.toLong() * NANOS_PER_SECOND / sampleRate
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): OpenedRecorder {
        val failures = mutableListOf<String>()
        val selectedKey = preferredDeviceKey
        val selectedDevice = selectedKey?.let { key ->
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { deviceKey(it) == key }
                ?: error("选择的音频输入设备当前不可用")
        }
        for (candidate in CANDIDATES) {
            val bytesPerSample = if (candidate.encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val minimum = AudioRecord.getMinBufferSize(
                candidate.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                candidate.encoding
            )
            if (minimum <= 0) {
                failures += "${candidate.sampleRate}/${candidate.format}: minBuffer=$minimum"
                continue
            }
            val recorderResult = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(candidate.source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(candidate.encoding)
                            .setSampleRate(candidate.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minimum * 2, candidate.sampleRate * bytesPerSample))
                    .build()
            }
            val recorder = recorderResult.getOrNull()
            if (recorder == null) {
                failures += "${candidate.sampleRate}/${candidate.format}: ${recorderResult.exceptionOrNull()?.message}"
                continue
            }
            if (selectedDevice != null && !recorder.setPreferredDevice(selectedDevice)) {
                failures += "${candidate.sampleRate}/${candidate.format}: 无法选择音频输入设备"
                recorder.release()
                continue
            }
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                val started = runCatching {
                    recorder.startRecording()
                    recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
                }.getOrDefault(false)
                if (started) return OpenedRecorder(recorder, candidate)
                failures += "${candidate.sampleRate}/${candidate.format}: 无法开始录音"
            } else {
                failures += "${candidate.sampleRate}/${candidate.format}: 未初始化"
            }
            recorder.release()
        }
        error("没有可用的单声道录音配置: ${failures.joinToString()}")
    }

    private fun refreshInputDevices() {
        _inputDevices.value = readInputDevices()
        val selectedKey = preferredDeviceKey ?: return
        if (_inputDevices.value.none { it.key == selectedKey }) {
            scope.launch { selectInputDevice(selectedKey) }
        }
    }

    private fun readInputDevices(): List<AudioInputDevice> = audioManager
        .getDevices(AudioManager.GET_DEVICES_INPUTS)
        .map { device ->
            AudioInputDevice(
                key = deviceKey(device),
                name = device.productName.toString(),
                type = device.type,
                external = device.type in EXTERNAL_DEVICE_TYPES
            )
        }
        .distinctBy { it.key }

    private fun deviceKey(device: AudioDeviceInfo): String {
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else ""
        val identity = address.ifBlank { device.productName.toString() }
        return "${device.type}:$identity"
    }

    private fun updateConsumersInState() {
        val current = _state.value
        if (current is AudioHubState.Capturing) {
            _state.value = current.copy(consumers = activeConsumers())
        }
    }

    private fun updateSystemSilenced(recorder: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val silenced = runCatching {
            recorder.activeRecordingConfiguration?.isClientSilenced == true
        }.getOrDefault(false)
        val current = _state.value
        if (current is AudioHubState.Capturing && current.systemSilenced != silenced) {
            _state.value = current.copy(systemSilenced = silenced)
        }
    }

    private fun activeConsumers(): Set<AudioConsumer> = subscribers.values
        .mapTo(linkedSetOf()) { it.consumer }

    companion object {
        private const val CHUNKS_PER_SECOND = 10
        private const val RELIABLE_BUFFER_CHUNKS = 32
        private const val NANOS_PER_SECOND = 1_000_000_000L

        private val EXTERNAL_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        )

        private val CANDIDATES = listOf(
            Candidate(
                48_000,
                AudioFormat.ENCODING_PCM_FLOAT,
                MediaRecorder.AudioSource.UNPROCESSED,
                AudioSampleFormat.PCM_FLOAT
            ),
            Candidate(48_000, AudioFormat.ENCODING_PCM_16BIT, MediaRecorder.AudioSource.MIC, AudioSampleFormat.PCM_16),
            Candidate(24_000, AudioFormat.ENCODING_PCM_16BIT, MediaRecorder.AudioSource.MIC, AudioSampleFormat.PCM_16),
            Candidate(12_000, AudioFormat.ENCODING_PCM_16BIT, MediaRecorder.AudioSource.MIC, AudioSampleFormat.PCM_16)
        )
    }
}
