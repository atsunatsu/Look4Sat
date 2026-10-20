/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothManager
import android.util.Log
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class Ft817Controller(
    bluetoothManager: BluetoothManager?,
    private val deviceAddress: String,
    private val transport: RadioTransport = BluetoothSppRadioTransport(
        requireNotNull(bluetoothManager),
        deviceAddress
    ),
    private val variant: YaesuCatVariant = YaesuCatVariant.FT817
) : IRadioController {

    private val tag = if (variant == YaesuCatVariant.FT817) "FT817" else "FT857"
    private val ioMutex = Mutex()
    private val responseTimeoutMs = if (variant == YaesuCatVariant.FT817) 3_000L else 200L
    private val pttRetries = if (variant == YaesuCatVariant.FT817) 5 else 0
    private var readCommandAcks = true

    override var isConnected: Boolean = false
        private set

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext true
        if (deviceAddress.isBlank()) return@withContext false
        try {
            readCommandAcks = true
            isConnected = transport.connect()
            if (isConnected && readFrequencyAndMode() == null) {
                transport.disconnect()
                isConnected = false
                Log.e(tag, "Connected transport did not return a valid CAT response")
            }
            Log.i(tag, "Connected to $deviceAddress")
            isConnected
        } catch (cancelled: CancellationException) {
            runCatching { transport.disconnect() }
            isConnected = false
            throw cancelled
        } catch (e: Exception) {
            Log.e(tag, "Connect error: ${e.message}")
            runCatching { transport.disconnect() }
            isConnected = false
            false
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO + NonCancellable) {
        try {
            if (isConnected) withTimeoutOrNull(PTT_OFF_TIMEOUT_MS) { pttOff() }
            transport.disconnect()
        } catch (e: Exception) {
            Log.e(tag, "Disconnect error: ${e.message}")
            runCatching { transport.disconnect() }
        } finally {
            isConnected = false
            Log.i(tag, "Disconnected from $deviceAddress")
        }
    }

    override suspend fun setFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val command = runCatching { Ft817CatProtocol.buildSetFreqCommand(frequencyHz) }
                .getOrElse { return@withLock false }
            val acknowledged = sendCommandWithAck(command)
            if (acknowledged && variant == YaesuCatVariant.FT817) delay(FT817_FREQUENCY_SETTLE_MS)
            acknowledged
        }
    }

    override suspend fun setMode(mode: String): Boolean = withContext(Dispatchers.IO) {
        val cmd = Ft817CatProtocol.buildSetModeCommand(mode) ?: return@withContext false
        ioMutex.withLock { sendCommandWithAck(cmd) }
    }

    override suspend fun setDataMode(enabled: Boolean, baseMode: String): Boolean {
        val target = if (enabled) {
            if (baseMode.equals("FM", ignoreCase = true) || baseMode.equals("FM-N", ignoreCase = true)) {
                "PKT"
            } else {
                "DIG"
            }
        } else {
            baseMode
        }
        return setMode(target)
    }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithAck(Ft817CatProtocol.buildCtcssModeCommand(enabled))
        }
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithAck(Ft817CatProtocol.buildSetCtcssToneCommand(toneHz))
        }
    }

    override suspend fun readFrequencyAndMode(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val sent = sendCommand(Ft817CatProtocol.buildReadFreqModeCommand())
            if (!sent) return@withContext null
            val response = readExact(RESPONSE_SIZE, responseTimeoutMs)
            val parsed=response?.let(Ft817CatProtocol::parseReadResponse)
            if(parsed==null) {
                isConnected=false
                withContext(NonCancellable) { runCatching { transport.disconnect() } }
            }
            parsed
        }
    }

    override suspend fun pttOn(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setPttAndConfirm(enabled = true) }
    }

    override val supportsPttReadback get() = true
    override suspend fun readPttState(): Boolean? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!sendCommand(Ft817CatProtocol.buildReadTxStatusCommand())) return@withLock null
            readExact(1, PTT_STATUS_TIMEOUT_MS)?.singleOrNull()?.let {
                Ft817CatProtocol.parsePttState(it, variant)
            }
        }
    }

    override suspend fun pttOff(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setPttAndConfirm(enabled = false) }
    }

    private suspend fun sendCommand(bytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isConnected || !transport.write(bytes)) {
                    isConnected = false
                    return@withContext false
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(tag, "Send error: ${e.message}")
                isConnected = false
                false
            }
        }
    }

    /**
     * Legacy Yaesu CAT documents a one-byte command acknowledgement but does not define its value.
     * Some compatible interfaces omit it entirely, which Hamlib handles by disabling ACK reads after
     * the first timeout. Mirror that behaviour instead of incorrectly requiring an ACK value of zero.
     */
    private suspend fun sendCommandWithAck(bytes: ByteArray): Boolean {
        if (!sendCommand(bytes)) return false
        if (!readCommandAcks) {
            delay(NO_ACK_POST_WRITE_DELAY_MS)
            return true
        }
        if (readByteWithTimeout(ACK_TIMEOUT_MS) == null) {
            if (!isConnected) return false
            readCommandAcks = false
            Log.i(tag, "CAT interface does not return command ACK bytes; continuing without ACK reads")
        }
        return true
    }

    private suspend fun setPttAndConfirm(enabled: Boolean): Boolean {
        val command = if (enabled) {
            Ft817CatProtocol.buildPttOnCommand()
        } else {
            Ft817CatProtocol.buildPttOffCommand()
        }
        if (variant == YaesuCatVariant.FT857) {
            val sent = sendCommandWithAck(command)
            if (sent && !enabled) delay(FT857_PTT_OFF_SETTLE_MS)
            return sent
        }

        repeat(pttRetries + 1) { attempt ->
            if (!sendCommandWithAck(command)) return false
            if (!sendCommand(Ft817CatProtocol.buildReadTxStatusCommand())) return false
            val status = readExact(1, PTT_STATUS_TIMEOUT_MS)?.singleOrNull()
            if (status != null && Ft817CatProtocol.parsePttState(status, variant) == enabled) return true
            if (attempt < pttRetries) delay(FT817_PTT_RETRY_DELAY_MS)
        }
        Log.e(tag, "PTT ${if (enabled) "ON" else "OFF"} was not confirmed by TX status")
        return false
    }

    private suspend fun readExact(size: Int, timeoutMs: Long): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val buffer = ByteArray(size)
                var read = 0
                val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
                while (read < size) {
                    val chunk = transport.readAvailable(size - read)
                    if (chunk.isEmpty()) {
                        if (System.nanoTime() >= deadline) return@withContext null
                        delay(POLL_INTERVAL_MS.milliseconds)
                        continue
                    }
                    chunk.copyInto(buffer, read)
                    read += chunk.size
                }
                buffer
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(tag, "Read error: ${e.message}")
                isConnected = false
                null
            }
        }
    }

    private suspend fun readByteWithTimeout(timeoutMs: Long): Int? = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        try {
            while (System.nanoTime() < deadline) {
                val value = transport.readAvailable(1).firstOrNull()
                if (value != null) return@withContext value.toInt() and 0xFF
                delay(POLL_INTERVAL_MS.milliseconds)
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(tag, "ACK read error: ${error.message}")
            isConnected = false
            null
        }
    }

    private companion object {
        const val RESPONSE_SIZE = 5
        const val ACK_TIMEOUT_MS = 200L
        const val PTT_STATUS_TIMEOUT_MS = 200L
        const val PTT_OFF_TIMEOUT_MS = 1_500L
        const val POLL_INTERVAL_MS = 10L
        const val FT817_FREQUENCY_SETTLE_MS = 50L
        const val FT817_PTT_RETRY_DELAY_MS = 100L
        const val FT857_PTT_OFF_SETTLE_MS = 200L
        const val NO_ACK_POST_WRITE_DELAY_MS = 10L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
