/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.core.data.usecase

import androidx.annotation.RequiresPermission
import com.rtbishop.look4sat.core.domain.audio.AudioConsumer
import com.rtbishop.look4sat.core.domain.audio.AudioDelivery
import com.rtbishop.look4sat.core.domain.audio.IAudioHub
import com.rtbishop.look4sat.core.domain.audio.StreamingAudioResampler
import com.rtbishop.look4sat.core.domain.usecase.IAudioCapture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AudioCapture(private val hub: IAudioHub) : IAudioCapture {
    override val sampleRate = 44_100

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    override fun audioFlow(): Flow<FloatArray> = flow {
        var resampler: StreamingAudioResampler? = null
        hub.audioFlow(AudioConsumer.SSTV, AudioDelivery.RELIABLE).collect { chunk ->
            if(resampler?.inputRate != chunk.sampleRate) {
                resampler?.finish()?.takeIf {it.isNotEmpty()}?.let {emit(it)}
                resampler=StreamingAudioResampler(chunk.sampleRate,sampleRate)
            }
            emit(resampler.process(chunk.samples))
        }
        resampler?.finish()?.takeIf {it.isNotEmpty()}?.let {emit(it)}
    }
}
