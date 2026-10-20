/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.RadioTrackingDisplay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Wire-format reference (Apache-2.0):
 * https://github.com/Ylianst/HTCommander/blob/33a13be1b55daa32184ed0c96b6171c8cf70e539/docs/blogs/radio-command-protocol.md
 * This is a native Kotlin implementation; no Flutter or Bluetooth audio code is used.
 */
internal object N76Protocol {
    const val GET_DEVICE_INFO = 4
    const val REGISTER_NOTIFICATION = 6
    const val EVENT_NOTIFICATION = 9
    const val GET_HT_STATUS = 20
    const val SET_FREQUENCY = 35
    const val GET_FREQUENCY = 36
    const val SET_SATELLITE_INFO = 77
    const val DO_PROG_FUNC = 66
    const val GROUP = 2

    data class Packet(val group: Int, val command: Int, val reply: Boolean, val payload: ByteArray)
    data class Frequencies(val rxHz: Long, val txHz: Long?)

    // fluoroom/Look4Sat-n76 c3f59411: distinct press/release, not a blind TX toggle.
    fun ptt(on: Boolean): ByteArray = byteArrayOf(0, if (on) 0x0d else 0x1a)

    fun frame(command: Int, payload: ByteArray = byteArrayOf(), reply: Boolean = false): ByteArray {
        require(command in 0..0x7fff && payload.size <= 255)
        return ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(0xff.toByte()).put(1).put(0).put(payload.size.toByte())
            .putShort(GROUP.toShort()).putShort((command or if (reply) 0x8000 else 0).toShort())
            .put(payload).array()
    }

    fun validFrequency(hz: Long): Boolean = hz in 136_000_000L..174_000_000L || hz in 400_000_000L..480_000_000L

    fun frequencyParameters(pair: Frequencies, txToneHz: Double?, satelliteUpdates: Int,
        satelliteFirmware: Boolean = true, rxToneHz: Double? = null, powerCode: Int? = null): ByteArray {
        require(validFrequency(pair.rxHz) && (pair.txHz == null || validFrequency(pair.txHz)))
        val tone = if (pair.txHz == null) 0 else toneCode(txToneHz)
        // A zero TX frequency means no uplink, not a simplex fallback to the RX frequency.
        require(powerCode == null || powerCode in 0..3)
        val buffer = ByteBuffer.allocate(if (satelliteFirmware) 16 else 14).order(ByteOrder.BIG_ENDIAN)
            .putInt(pair.rxHz.toInt()).putInt((pair.txHz ?: 0).toInt())
            .putShort(toneCode(rxToneHz).toShort()).putShort(tone.toShort())
            .putShort(((if (satelliteFirmware) 0x0a00 else 0x8300) or
                (powerCode ?: if (satelliteUpdates < 2 && satelliteFirmware) 3 else 0)).toShort())
        if (satelliteFirmware) buffer.putShort(25_000)
        return buffer.array()
    }

    fun toneCode(hz: Double?): Int {
        if (hz == null) return 0
        require(hz.isFinite() && hz in 60.0..260.0)
        return (hz * 100).roundToInt()
    }

    fun satelliteInfo(display: RadioTrackingDisplay): ByteArray {
        require(display.azimuthDegrees.isFinite() && display.elevationDegrees.isFinite())
        require(display.distanceKm.isFinite() && display.altitudeKm.isFinite())
        val bytes = ByteArray(30)
        val name = display.satelliteName.map { if (it.code in 32..126) it else '?' }
            .joinToString("").toByteArray(Charsets.US_ASCII)
        name.copyInto(bytes, endIndex = minOf(name.size, 20))
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply {
            position(20)
            putShort((((display.azimuthDegrees % 360 + 360) % 360) * 128).roundToInt().toShort())
            putShort((display.elevationDegrees.coerceIn(-90.0, 90.0) * 256).roundToInt().toShort())
            putShort(display.distanceKm.roundToInt().coerceIn(0, 65535).toShort())
            putShort(display.altitudeKm.roundToInt().coerceIn(0, 65535).toShort())
            putShort(display.secondsUntilAos.coerceIn(0, 65535).toShort())
        }
        return bytes
    }

    fun frequency(bytes: ByteArray, offset: Int = 0): Pair<Long, String>? {
        if (offset < 0 || bytes.size < offset + 4) return null
        val encoded = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
        val hz = encoded.toLong() and 0x3fffffffL
        val mode = when (encoded ushr 30) { 0 -> "FM"; 1 -> "AM"; else -> return null }
        return if (validFrequency(hz)) hz to mode else null
    }

    class Decoder {
        private var pending = byteArrayOf()
        fun clear() { pending = byteArrayOf() }

        fun feed(bytes: ByteArray): List<Packet> {
            // At most one partial 264-byte frame is retained between reads.
            val input = pending + bytes
            val packets = mutableListOf<Packet>()
            var offset = 0
            while (offset + 1 < input.size) {
                if (input[offset] != 0xff.toByte() || input[offset + 1] != 1.toByte()) { offset++; continue }
                if (input.size - offset < 8) break
                val flags = input[offset + 2].toInt() and 255
                if (flags and 0xfe != 0) { offset++; continue }
                val length = (input[offset + 3].toInt() and 255) + 8 + (flags and 1)
                if (input.size - offset < length) break
                if (flags and 1 != 0 && (offset until offset + length).fold(0) { sum, i -> sum xor (input[i].toInt() and 255) } != 0) {
                    offset++; continue
                }
                val buffer = ByteBuffer.wrap(input, offset + 4, 4).order(ByteOrder.BIG_ENDIAN)
                val group = buffer.short.toInt() and 65535
                val command = buffer.short.toInt() and 65535
                packets += Packet(group, command and 0x7fff, command and 0x8000 != 0,
                    input.copyOfRange(offset + 8, offset + length - (flags and 1)))
                offset += length
            }
            pending = input.copyOfRange(offset, input.size).takeLast(263).toByteArray()
            return packets
        }
    }
}
