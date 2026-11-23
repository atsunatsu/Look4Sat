package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.model.UsbPttSettings
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** USB acknowledgement confirms the control line, independently of CAT TX-state readback. */
internal class UsbPttRadioController(
    private val cat: IRadioController,
    private val line: RadioTransport,
    private val settings: UsbPttSettings,
    private val sharedPort: Boolean,
    private val portAvailable: () -> Boolean = { true }
) : IRadioController by cat {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private var nativeCw = false
    override val externalUsbPtt get() = true
    override val isConnected get() = cat.isConnected && line.isConnected

    override suspend fun connect(): Boolean = mutex.withLock {
        if (isConnected) return@withLock true
        if (!portAvailable()) return@withLock false
        if (!line.reserveUsbPtt(settings)) return@withLock false
        var ready = false
        try {
            if (!sharedPort && !line.connect()) return@withLock false
            if (!cat.connect() || !line.setUsbPtt(false)) return@withLock false
            ready = true
            true
        } finally {
            if (!ready) withContext(NonCancellable) {
                runCatching { line.setUsbPtt(false) }
                runCatching { cat.disconnect() }
                if (!sharedPort) line.disconnect()
            }
        }
    }

    override suspend fun disconnect() {
        invalidatePendingPttOn()
        mutex.withLock {
            withContext(NonCancellable) {
                try { line.setUsbPtt(false) } finally {
                    try { cat.disconnect() } finally { if (!sharedPort) line.disconnect() }
                }
            }
        }
    }

    override fun pttSafetyGeneration() = generation.get()
    override fun invalidatePendingPttOn() {
        generation.incrementAndGet()
        cat.invalidatePendingPttOn()
    }

    override suspend fun startCw(text: String, wpm: Int): Boolean = mutex.withLock {
        nativeCw = true // A rejected/partial native command also needs a native stop attempt.
        cat.startCw(text, wpm)
    }

    override suspend fun pttOn() = pttOnIfGeneration(generation.get())
    override suspend fun pttOnIfGeneration(expectedGeneration: Long): Boolean = pttOnIfGeneration(expectedGeneration) { true }
    override suspend fun pttOnIfGeneration(expectedGeneration: Long, isOnTime: () -> Boolean): Boolean = mutex.withLock {
        if (generation.get() != expectedGeneration || !isConnected || !isOnTime()) return@withLock false
        if (cat.supportsPttReadback && cat.readPttState() != false) return@withLock false
        var armed = false
        try {
            if (!isOnTime() || generation.get() != expectedGeneration) return@withLock false
            armed = line.setUsbPtt(true) && generation.get() == expectedGeneration && isOnTime()
            armed
        } finally {
            if (!armed) withContext(NonCancellable) { line.setUsbPtt(false) }
        }
    }

    override suspend fun pttOff(): Boolean {
        invalidatePendingPttOn()
        return mutex.withLock { withContext(NonCancellable) {
            val released = line.setUsbPtt(false)
            val cwStopped = !nativeCw || cat.pttOff()
            if (cwStopped) nativeCw = false
            released && cwStopped
        } }
    }
}
