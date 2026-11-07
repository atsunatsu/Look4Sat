// SPDX-License-Identifier: GPL-3.0-or-later
package com.rtbishop.look4sat.core.data.audio

import com.rtbishop.look4sat.core.domain.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Page shutdown cancels its consumers, while the foreground recorder keeps the source alive. */
internal class RecordingAudioHub(private val delegate: IAudioHub, private val recordingActive: ()->Boolean): IAudioHub by delegate {
    private val ids=AtomicLong()
    private val consumers=ConcurrentHashMap<Long,Job>()
    override fun audioFlow(consumer: AudioConsumer,delivery: AudioDelivery): Flow<TimestampedAudioChunk> = flow {
        val id=ids.incrementAndGet()
        if(consumer!=AudioConsumer.RECORDING) consumers[id]=currentCoroutineContext().job
        try {emitAll(delegate.audioFlow(consumer,delivery))} finally {consumers.remove(id)}
    }
    override suspend fun stopAll() {
        consumers.values.toList().forEach {it.cancel()}
        if(!recordingActive()) delegate.stopAll()
    }
}

/** A second subscriber never creates a second N76 socket or SBC decoder. */
internal class N76ReceivePcmBus {
    private val frames=AtomicLong()
    private val reset=java.util.concurrent.atomic.AtomicBoolean(true)
    private var previousTime=0L
    private var previousSamples=0
    private val stream=MutableSharedFlow<TimestampedAudioChunk>(extraBufferCapacity=64,
        onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val audio: Flow<TimestampedAudioChunk> = stream
    fun discontinuity() {reset.set(true)}
    fun offer(samples: ShortArray) {
        val at=frames.getAndAdd(samples.size.toLong())
        val now=android.os.SystemClock.elapsedRealtimeNanos()
        val gap=reset.getAndSet(false) || (previousTime!=0L && now-previousTime>previousSamples*1_000_000_000L/32000+250_000_000L)
        previousTime=now;previousSamples=samples.size
        stream.tryEmit(TimestampedAudioChunk(FloatArray(samples.size) {samples[it]/32768f},32000,at,
            now,AudioSampleFormat.PCM_16,gap))
    }
}
