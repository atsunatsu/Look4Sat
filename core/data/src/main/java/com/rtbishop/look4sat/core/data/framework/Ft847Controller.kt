package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.IRadioController
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Two-way CAT, Yaesu FT-847 operating manual pp. 91–93. */
internal class Ft847Controller(
    private val transport: RadioTransport,
    private val satelliteLayout: Boolean
) : IRadioController {
    private var identified = false
    private var satellite = false
    private var selected = 0
    override val isConnected get() = identified && transport.isConnected
    override val supportsPttReadback get() = true

    override suspend fun connect(): Boolean {
        var ready = false
        try {
            if (!transport.connect() || !write(0x00)) return false
            ready = readVfo(0) != null
            identified = ready
            return ready
        } finally {
            if (!ready) withContext(NonCancellable) { transport.disconnect() }
        }
    }

    override suspend fun disconnect() {
        try {
            if (isConnected) write(0x80)
        } finally {
            identified = false
            selected = 0
            satellite = false
            transport.disconnect()
        }
    }

    override suspend fun setSplitMode(enabled: Boolean): Boolean {
        if (enabled && !satelliteLayout) return false
        if (enabled == satellite) return true
        if (!write(if (enabled) 0x4E else 0x8E)) return false
        if (readVfo(if (enabled) 0x10 else 0) == null || enabled && readVfo(0x20) == null) return false
        satellite = enabled
        selected = if (satellite) 0x10 else 0
        return true
    }

    override suspend fun setVfo(vfoA: Boolean): Boolean {
        if (!vfoA && !satellite) return false
        selected = if (!satellite) 0 else if (vfoA) 0x10 else 0x20
        return true
    }

    override suspend fun setBand(frequencyHz: Long) = frequencyHz in 100_000L..470_000_000L
    override suspend fun setFrequency(frequencyHz: Long) = setVfoFrequency(selected, frequencyHz)
    override suspend fun setWorkingFrequency(frequencyHz: Long) =
        setVfoFrequency(if (satellite) 0x10 else 0, frequencyHz)
    override suspend fun setTxVfoFrequency(frequencyHz: Long) = satellite && setVfoFrequency(0x20, frequencyHz)
    override suspend fun readWorkingFrequency() = readVfo(if (satellite) 0x10 else 0)?.first
    override suspend fun readTxVfoFrequency() = if (satellite) readVfo(0x20)?.first else null
    override suspend fun readFrequencyAndMode() = readVfo(selected)

    private suspend fun setVfoFrequency(vfo: Int, frequencyHz: Long): Boolean {
        if (!isConnected || !setBand(frequencyHz)) return false
        val rounded = (frequencyHz + 5) / 10 * 10
        val bcd = (rounded / 10).toString().padStart(8, '0').chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        return write(vfo + 0x01, bcd) && readVfo(vfo)?.first == rounded
    }

    override suspend fun setMode(mode: String): Boolean {
        val code = modes.entries.firstOrNull { it.value == mode }?.key ?: return false
        return isConnected && write(selected + 0x07, byteArrayOf(code.toByte())) && readVfo(selected)?.second == mode
    }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean =
        isConnected && write(selected + 0x0A, byteArrayOf(if (enabled) 0x4A else 0x8A.toByte()))

    override suspend fun setCtcssTone(toneHz: Double): Boolean {
        val code = tones[toneHz] ?: return false
        return isConnected && write(selected + 0x0B, byteArrayOf(code.toByte()))
    }
    override suspend fun readPttState(): Boolean? = read(0xF7, 1)?.let { (it[0].toInt() and 0x80) == 0 }
    override suspend fun pttOn(): Boolean = isConnected && write(0x08) && readPttState() == true
    override suspend fun pttOff(): Boolean = isConnected && write(0x88) && readPttState() == false

    private suspend fun readVfo(vfo: Int): Pair<Long, String>? {
        val bytes = read(vfo + 0x03, 5) ?: return null
        var frequency = 0L
        for (byte in bytes.take(4)) {
            val high = (byte.toInt() and 0xFF) shr 4
            val low = byte.toInt() and 0x0F
            if (high > 9 || low > 9) return null
            frequency = frequency * 100 + high * 10 + low
        }
        val mode = modes[bytes[4].toInt() and 0xFF] ?: return null
        return (frequency * 10).takeIf { setBand(it) }?.let { it to mode }
    }

    private suspend fun write(opcode: Int, data: ByteArray = byteArrayOf()): Boolean =
        transport.isConnected && transport.write(data.copyOf(4) + opcode.toByte())

    private suspend fun read(opcode: Int, size: Int): ByteArray? {
        if (!transport.isConnected) return null
        transport.readAvailable(64)
        if (!write(opcode)) return null
        return withTimeoutOrNull(500L) {
            val output = ByteArray(size)
            var offset = 0
            while (offset < size) {
                if (!transport.isConnected) return@withTimeoutOrNull null
                val chunk = transport.readAvailable(size - offset)
                chunk.copyInto(output, offset)
                offset += chunk.size
                if (offset < size) delay(5)
            }
            output
        }
    }

    private companion object {
        val modes = mapOf(0x00 to "LSB", 0x01 to "USB", 0x02 to "CW", 0x03 to "CW-R",
            0x04 to "AM", 0x08 to "FM", 0x88 to "NFM")
        val tones = mapOf(
            67.0 to 0x3F, 69.3 to 0x39, 71.9 to 0x1F, 74.4 to 0x3E, 77.0 to 0x0F, 79.7 to 0x3D,
            82.5 to 0x1E, 85.4 to 0x3C, 88.5 to 0x0E, 91.5 to 0x3B, 94.8 to 0x1D, 97.4 to 0x3A,
            100.0 to 0x0D, 103.5 to 0x1C, 107.2 to 0x0C, 110.9 to 0x1B, 114.8 to 0x0B, 118.8 to 0x1A,
            123.0 to 0x0A, 127.3 to 0x19, 131.8 to 0x09, 136.5 to 0x18, 141.3 to 0x08, 146.2 to 0x17,
            151.4 to 0x07, 156.7 to 0x16, 162.2 to 0x06, 167.9 to 0x15, 173.8 to 0x05, 179.9 to 0x14,
            186.2 to 0x04, 192.8 to 0x13, 203.5 to 0x03, 210.7 to 0x12, 218.1 to 0x02, 225.7 to 0x11,
            233.6 to 0x01, 241.8 to 0x10, 250.3 to 0x00
        )
    }
}
