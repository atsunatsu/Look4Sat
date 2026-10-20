/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class N76AudioState(val connected: Boolean = false, val transmitting: Boolean = false, val error: String? = null)
internal interface N76AudioIo {
    fun openPlayback(): Boolean
    fun play(pcm: ShortArray)
    fun closePlayback()
    fun hasMicrophonePermission(): Boolean
    fun microphone(): Flow<ShortArray>
    fun onInterrupted(callback: () -> Unit) = Unit
}
interface N76Audio {
    val state: StateFlow<N76AudioState>
    suspend fun connect(): Boolean
    suspend fun startTransmit(): Boolean
    suspend fun stopTransmit(): Boolean
    suspend fun disconnect()
    fun setRadioPcmSink(sink: (ShortArray) -> Unit) = Unit
}

/** The audio socket is independent of CAT and is never opened when the setting is off. */
internal class N76AudioSession(
    private val transport: RadioTransport,
    private val io: N76AudioIo,
    private val scope: CoroutineScope,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics
) : N76Audio {
    private val mutableState = MutableStateFlow(N76AudioState())
    override val state: StateFlow<N76AudioState> = mutableState
    private val lifecycle = Mutex()
    private val writer = Mutex()
    private val generation = AtomicLong()
    private var receiver: Job? = null
    private var player: Job? = null
    private var transmitter: Job? = null
    private var playback: Channel<ShortArray>? = null
    private var radioPcmSink: ((ShortArray) -> Unit)? = null
    override fun setRadioPcmSink(sink: (ShortArray) -> Unit) { radioPcmSink = sink }

    override suspend fun connect(): Boolean = lifecycle.withLock {
        if (mutableState.value.connected) return@withLock true
        try {
            stopLocked()
            receiver?.cancelAndJoin(); receiver = null
            player?.cancelAndJoin(); player = null
            playback?.close(); playback = null
            io.closePlayback()
            transport.configureBluetoothConnection(secureFirst = true, timeoutMillis = 6_000)
            if (!transport.connect()) throw IOException(transport.lastIoFailure ?: "Could not open N76 Bluetooth audio")
            if (!io.openPlayback()) throw IOException("Could not open phone audio output")
            io.onInterrupted { scope.launch { stopTransmit() } }
            mutableState.value = N76AudioState(connected = true)
            val queue = Channel<ShortArray>(16, BufferOverflow.DROP_OLDEST)
            playback = queue
            player = scope.launch(Dispatchers.IO) {
                try { for (pcm in queue) if (!mutableState.value.transmitting) io.play(pcm) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { fail(error) }
            }
            receiver = scope.launch(Dispatchers.IO) {
                val framing = N76AudioProtocol.Decoder()
                val codec = N76SbcCodec()
                try {
                    while (isActive && transport.isConnected) {
                        for (frame in framing.feed(transport.readAvailable(4096))) {
                            val command = frame.firstOrNull()?.toInt()?.and(255)
                            if (command == 1) { codec.reset(); continue }
                            if (command !in listOf(0, 3) || mutableState.value.transmitting) continue
                            var offset = 1
                            while (offset < frame.size) {
                                val length = codec.frameLength(frame, offset) ?: break
                                val pcm = codec.decode(frame, offset) ?: break
                                radioPcmSink?.invoke(pcm)
                                queue.trySend(pcm)
                                offset += length
                            }
                        }
                        delay(5)
                    }
                    if (mutableState.value.connected) throw IOException("N76 Bluetooth audio disconnected")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { fail(error) }
            }
            true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { transport.disconnect(); io.closePlayback() }
            throw cancelled
        } catch (error: Exception) {
            transport.disconnect(); io.closePlayback(); fail(error); false
        }
    }

    override suspend fun startTransmit(): Boolean = lifecycle.withLock {
        if (!mutableState.value.connected || !transport.isConnected || transmitter?.isActive == true) return@withLock false
        if (!io.hasMicrophonePermission()) {
            mutableState.update { it.copy(error = "Allow microphone access before pressing PTT") }
            return@withLock false
        }
        val epoch = generation.incrementAndGet()
        val started = CompletableDeferred<Boolean>()
        mutableState.update { it.copy(transmitting = true, error = null) }
        transmitter = scope.launch(Dispatchers.IO) {
            val codec = N76SbcCodec()
            val pending = ShortArray(128)
            var count = 0
            try {
                io.microphone().collect { pcm ->
                    for (sample in pcm) {
                        if (generation.get() != epoch) throw CancellationException("PTT released")
                        pending[count++] = sample
                        if (count != 128) continue
                        val bytes = N76AudioProtocol.audio(codec.encode(pending))
                        writer.withLock {
                            if (generation.get() != epoch) throw CancellationException("PTT released")
                            // Finish an in-flight write before sending END, never after it.
                            withContext(NonCancellable) {
                                if (!transport.write(bytes)) throw IOException(transport.lastIoFailure ?: "N76 audio write failed")
                            }
                        }
                        started.complete(true)
                        count = 0
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error) }
            finally {
                started.complete(false)
                withContext(NonCancellable) { sendEnd() }
                mutableState.update { it.copy(transmitting = false) }
            }
        }
        val ok = withTimeoutOrNull(1_000) { started.await() } == true
        if (!ok) stopLocked()
        ok
    }

    override suspend fun stopTransmit(): Boolean = withContext(NonCancellable) {
        // Invalidate queued frames before waiting for any suspended audio operation.
        generation.incrementAndGet()
        lifecycle.withLock { stopLocked() }
    }

    private suspend fun stopLocked(): Boolean {
        generation.incrementAndGet()
        val active = transmitter
        transmitter = null
        active?.cancelAndJoin()
        val result = if (active != null || mutableState.value.transmitting) sendEnd() else true
        mutableState.update { it.copy(transmitting = false) }
        return result
    }

    private suspend fun sendEnd(): Boolean = writer.withLock {
        if (!transport.isConnected) return@withLock false
        transport.write(N76AudioProtocol.stop)
    }

    override suspend fun disconnect() = withContext(NonCancellable) {
        generation.incrementAndGet()
        lifecycle.withLock {
            stopLocked()
            mutableState.update { it.copy(connected = false) }
            receiver?.cancelAndJoin(); receiver = null
            playback?.close(); playback = null
            player?.cancelAndJoin(); player = null
            transport.disconnect()
            io.closePlayback()
        }
    }

    private suspend fun fail(error: Exception) {
        generation.incrementAndGet()
        withContext(NonCancellable) {
            if (transmitter?.isActive == true || mutableState.value.transmitting) sendEnd()
            transport.disconnect()
        }
        mutableState.update { it.copy(connected = false, transmitting = false, error = error.message ?: "N76 audio failed") }
        diagnostics.record(ControlDiagnosticSource.RADIO, "n76/audio/failed", mutableState.value.error!!, ControlDiagnosticSeverity.ERROR)
    }
}
