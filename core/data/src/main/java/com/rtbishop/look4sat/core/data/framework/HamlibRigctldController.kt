/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import android.util.Log
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Radio controller for Hamlib's line-oriented rigctld TCP protocol. */
class HamlibRigctldController(
    private val endpoint: String,
    private val transport: RadioTransport,
    private val pttReadbackSupported: Boolean = true
) : IRadioController {

    private val ioMutex = Mutex()
    private var pendingInput = ByteArray(0)

    override var isConnected: Boolean = false
        private set

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext true
        if (endpoint.isBlank()) return@withContext false
        try {
            isConnected = transport.connect()
            val ready = isConnected && ioMutex.withLock {
                readFrequencyLocked() != null && readModeLocked() != null
            }
            if (!ready) {
                transport.disconnect()
                pendingInput = ByteArray(0)
                isConnected = false
                Log.e(TAG, "rigctld did not return a valid frequency and mode")
            }
            ready
        } catch (cancelled: CancellationException) {
            runCatching { transport.disconnect() }
            pendingInput = ByteArray(0)
            isConnected = false
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Connect error: ${error.message}")
            runCatching { transport.disconnect() }
            pendingInput = ByteArray(0)
            isConnected = false
            false
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO + NonCancellable) {
        try {
            if (isConnected && pttReadbackSupported) withTimeoutOrNull(DISCONNECT_PTT_TIMEOUT_MS) {
                ioMutex.withLock { setPttLocked(false) }
            }
            transport.disconnect()
        } catch (error: Exception) {
            Log.e(TAG, "Disconnect error: ${error.message}")
            runCatching { transport.disconnect() }
        } finally {
            pendingInput = ByteArray(0)
            isConnected = false
        }
    }

    override suspend fun setFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setCommandLocked("\\set_freq $frequencyHz") }
    }

    override suspend fun setMode(mode: String): Boolean = withContext(Dispatchers.IO) {
        val hamlibMode = toHamlibMode(mode) ?: return@withContext false
        val passband = if (mode.equals("FM-N", ignoreCase = true) || mode.equals("NFM", ignoreCase = true)) {
            NARROW_FM_PASSBAND_HZ
        } else {
            0
        }
        ioMutex.withLock { setCommandLocked("\\set_mode $hamlibMode $passband") }
    }

    override suspend fun setDataMode(enabled: Boolean, baseMode: String): Boolean {
        val target = dataModeTarget(enabled, baseMode)
        return setMode(target)
    }

    override suspend fun setTxDataMode(enabled: Boolean, baseMode: String): Boolean =
        withContext(Dispatchers.IO) {
            val target = dataModeTarget(enabled, baseMode)
            val hamlibMode = toHamlibMode(target) ?: return@withContext false
            val passband = if (target.equals("FM-N", ignoreCase = true)) NARROW_FM_PASSBAND_HZ else 0
            ioMutex.withLock { setCommandLocked("\\set_split_mode $hamlibMode $passband") }
        }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setCommandLocked("\\set_func TONE ${if (enabled) 1 else 0}") }
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean = withContext(Dispatchers.IO) {
        if (!toneHz.isFinite() || toneHz !in 0.0..999.9) return@withContext false
        ioMutex.withLock { setCommandLocked("\\set_ctcss_tone ${(toneHz * 10).roundToInt()}") }
    }

    override suspend fun readFrequencyAndMode(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val frequency = readFrequencyLocked() ?: return@withLock null
            val mode = readModeLocked() ?: return@withLock null
            frequency to mode
        }
    }

    override suspend fun pttOn(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setPttLocked(true) }
    }

    override val supportsPttReadback get() = pttReadbackSupported
    override suspend fun readPttState(): Boolean? = withContext(Dispatchers.IO) {
        if(!supportsPttReadback)return@withContext null
        ioMutex.withLock { readPttLocked() }
    }

    override suspend fun pttOff(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setPttLocked(false) }
    }

    override suspend fun setBand(frequencyHz: Long): Boolean = frequencyHz > 0L

    override suspend fun setVfo(vfoA: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setCommandLocked("\\set_vfo ${if (vfoA) "VFOA" else "VFOB"}") }
    }

    override suspend fun setSplitMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            setCommandLocked("\\set_split_vfo ${if (enabled) 1 else 0} VFOB") &&
                readSplitStateLocked() == enabled
        }
    }

    override suspend fun setSplitModes(rxMode: String?, txMode: String?): Boolean =
        withContext(Dispatchers.IO) {
            val normalizedRx = rxMode?.let(::toHamlibMode)
            val normalizedTx = txMode?.let(::toHamlibMode)
            if (rxMode != null && normalizedRx == null) return@withContext false
            if (txMode != null && normalizedTx == null) return@withContext false
            ioMutex.withLock {
                // Duplex initialization may leave VFO-B selected after setting its frequency.
                if (!setCommandLocked("\\set_vfo VFOA")) return@withLock false
                if (normalizedRx != null && !setCommandLocked("\\set_mode $normalizedRx 0")) {
                    return@withLock false
                }
                if (normalizedRx != null && readModeLocked() != fromHamlibMode(normalizedRx)) {
                    return@withLock false
                }
                if (normalizedTx != null && !setCommandLocked("\\set_split_mode $normalizedTx 0")) {
                    return@withLock false
                }
                normalizedTx == null || readSplitModeLocked() == fromHamlibMode(normalizedTx)
            }
        }

    override suspend fun configureTxCtcss(toneHz: Double?): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            var restored = false
            val configured = try {
                if (!setCommandLocked("\\set_vfo VFOB")) {
                    false
                } else if (toneHz != null) {
                    toneHz.isFinite() && toneHz in 0.0..999.9 &&
                        setCommandLocked("\\set_ctcss_tone ${(toneHz * 10).roundToInt()}") &&
                        setCommandLocked("\\set_func TONE 1")
                } else {
                    setCommandLocked("\\set_func TONE 0")
                }
            } finally {
                restored = withContext(NonCancellable) { setCommandLocked("\\set_vfo VFOA") }
            }
            configured && restored
        }
    }

    override suspend fun setWorkingFrequency(frequencyHz: Long): Boolean = setFrequency(frequencyHz)

    override suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setCommandLocked("\\set_split_freq $frequencyHz") }
    }

    override suspend fun readWorkingFrequency(): Long? = withContext(Dispatchers.IO) {
        ioMutex.withLock { readFrequencyLocked() }
    }

    override suspend fun readTxVfoFrequency(): Long? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            commandLocked("\\get_split_freq", expectedLines = 1)
                ?.singleOrNull()
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }
        }
    }

    private suspend fun setPttLocked(enabled: Boolean): Boolean {
        if(!pttReadbackSupported)return false
        if (!setCommandLocked("\\set_ptt ${if (enabled) 1 else 0}")) return false
        return readPttLocked() == enabled
    }

    private suspend fun readPttLocked(): Boolean? =
        commandLocked("\\get_ptt", expectedLines = 1)
            ?.singleOrNull()
            ?.toIntOrNull()
            ?.takeIf { it in 0..3 }
            ?.let { it != 0 }

    private suspend fun readFrequencyLocked(): Long? =
        commandLocked("\\get_freq", expectedLines = 1)
            ?.singleOrNull()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }

    private suspend fun readModeLocked(): String? =
        commandLocked("\\get_mode", expectedLines = 2)
            ?.firstOrNull()
            ?.let(::fromHamlibMode)

    private suspend fun readSplitModeLocked(): String? =
        commandLocked("\\get_split_mode", expectedLines = 2)
            ?.firstOrNull()
            ?.let(::fromHamlibMode)

    private suspend fun readSplitStateLocked(): Boolean? =
        commandLocked("\\get_split_vfo", expectedLines = 2)
            ?.firstOrNull()
            ?.toIntOrNull()
            ?.let { it != 0 }

    private suspend fun setCommandLocked(command: String): Boolean =
        commandLocked(command, expectedLines = 1)?.singleOrNull() == "RPRT 0"

    private suspend fun commandLocked(command: String, expectedLines: Int): List<String>? {
        if (!isConnected || expectedLines <= 0) return null
        val bytes = "$command\n".toByteArray(Charsets.US_ASCII)
        val written=try { transport.write(bytes) }
        catch(cancelled:CancellationException) { throw cancelled }
        catch(error:Exception) { Log.e(TAG,"Write error: ${error.message}");false }
        if (!written) {
            dropConnection()
            return null
        }
        val lines = mutableListOf<String>()
        val deadline = System.nanoTime() + RESPONSE_TIMEOUT_MS * NANOS_PER_MILLISECOND
        while (lines.size < expectedLines) {
            val newline = pendingInput.indexOf(NEWLINE)
            if (newline >= 0) {
                val line = pendingInput.copyOfRange(0, newline).toString(Charsets.US_ASCII).trimEnd('\r')
                pendingInput = pendingInput.copyOfRange(newline + 1, pendingInput.size)
                if (line.startsWith("RPRT ") && expectedLines > 1) return null
                lines += line
                continue
            }
            if (System.nanoTime() >= deadline) {
                dropConnection()
                return null
            }
            val chunk = try {
                transport.readAvailable(MAX_RESPONSE_BYTES - pendingInput.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Read error: ${error.message}")
                dropConnection()
                return null
            }
            if (chunk.isEmpty()) {
                delay(POLL_INTERVAL_MS)
            } else {
                if (pendingInput.size + chunk.size > MAX_RESPONSE_BYTES) {
                    dropConnection()
                    return null
                }
                pendingInput += chunk
            }
        }
        return lines
    }

    private suspend fun dropConnection() {
        isConnected=false
        pendingInput=ByteArray(0)
        withContext(NonCancellable) { runCatching { transport.disconnect() } }
    }

    private fun ByteArray.indexOf(value: Byte): Int {
        for (index in indices) if (this[index] == value) return index
        return -1
    }

    private fun toHamlibMode(mode: String): String? = when (mode.uppercase(Locale.US)) {
        "LSB", "USB", "CW", "AM", "FM", "WFM", "RTTY", "PKTUSB", "PKTLSB", "PKTFM" ->
            mode.uppercase(Locale.US)
        "FM-N", "NFM" -> "FM"
        "CW-R" -> "CWR"
        "RTTY-R" -> "RTTYR"
        "AFSK" -> "FM"
        "DIG" -> "PKTUSB"
        "PKT" -> "PKTFM"
        else -> null
    }

    private fun fromHamlibMode(mode: String): String = when (mode.uppercase(Locale.US)) {
        "CWR" -> "CW-R"
        "RTTYR" -> "RTTY-R"
        "PKTUSB", "PKTLSB" -> "DIG"
        "PKTFM" -> "PKT"
        else -> mode.uppercase(Locale.US)
    }

    private fun dataModeTarget(enabled: Boolean, baseMode: String): String = if (enabled) {
        when (baseMode.uppercase(Locale.US)) {
            "LSB" -> "PKTLSB"
            "FM", "FM-N", "NFM" -> "PKTFM"
            else -> "PKTUSB"
        }
    } else {
        baseMode
    }

    private companion object {
        const val TAG = "HamlibRigctld"
        const val RESPONSE_TIMEOUT_MS = 1_500L
        const val DISCONNECT_PTT_TIMEOUT_MS = 1_500L
        const val NARROW_FM_PASSBAND_HZ = 10_000
        const val POLL_INTERVAL_MS = 10L
        const val MAX_RESPONSE_BYTES = 8_192
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NEWLINE: Byte = 0x0A
    }
}
