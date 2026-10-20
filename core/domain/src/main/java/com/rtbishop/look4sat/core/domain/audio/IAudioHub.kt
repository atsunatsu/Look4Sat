/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class AudioConsumer {
    SSTV,
    CW,
    MORSE_EXPERT,
    N76_VOICE
}

enum class AudioDelivery {
    /** 解码路径必须完整接收每一个音频块。 */
    RELIABLE,

    /** 频谱只关心最新帧，允许覆盖尚未处理的旧帧。 */
    LATEST
}

enum class AudioSampleFormat {
    PCM_FLOAT,
    PCM_16
}

data class TimestampedAudioChunk(
    val samples: FloatArray,
    val sampleRate: Int,
    val framePosition: Long,
    val elapsedRealtimeNanos: Long,
    val format: AudioSampleFormat
)

data class AudioInputDevice(
    val key: String,
    val name: String,
    val type: Int,
    val external: Boolean
)

sealed interface AudioHubState {
    data object Idle : AudioHubState

    data class Capturing(
        val sampleRate: Int,
        val format: AudioSampleFormat,
        val consumers: Set<AudioConsumer>,
        val systemSilenced: Boolean = false,
        val deviceId: Int? = null,
        val deviceType: Int? = null,
        val deviceName: String = ""
    ) : AudioHubState

    data class Failed(val reason: String) : AudioHubState
}

interface IAudioHub {
    val state: StateFlow<AudioHubState>
    val inputDevices: StateFlow<List<AudioInputDevice>>

    /**
     * 订阅共享麦克风。首个订阅者启动录音，最后一个订阅者离开后释放录音。
     * 调用方必须已经持有 RECORD_AUDIO 权限。
     */
    fun audioFlow(
        consumer: AudioConsumer,
        delivery: AudioDelivery = AudioDelivery.RELIABLE
    ): Flow<TimestampedAudioChunk>

    /** 选择共享录音入口；传入 null 使用系统默认路由。活动录音会安全重启。 */
    suspend fun selectInputDevice(deviceKey: String?)

    /** 应用进入后台、权限撤销或用户禁用解码时停止并释放所有音频资源。 */
    suspend fun stopAll()
}
