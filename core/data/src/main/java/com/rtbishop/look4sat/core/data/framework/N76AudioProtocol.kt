/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.core.data.framework

import java.io.ByteArrayOutputStream

internal object N76AudioProtocol {
    const val SERVICE_UUID = "39144315-32fa-40db-85ed-fbfeba2d86e6"
    // HTCommander's Generic Audio stop frame, not a GAIA control command.
    val stop: ByteArray get() = byteArrayOf(0x7e, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0x7e)
    fun audio(sbc: ByteArray): ByteArray {
        require(sbc.isNotEmpty() && sbc.size <= 4096)
        val result = ByteArrayOutputStream()
        result.write(0x7e); result.write(0)
        sbc.forEach { byte ->
            val value = byte.toInt() and 255
            if (value == 0x7d || value == 0x7e) { result.write(0x7d); result.write(value xor 0x20) }
            else result.write(value)
        }
        result.write(0x7e)
        return result.toByteArray()
    }
    class Decoder {
        private val pending = ByteArrayOutputStream()
        private var collecting = false
        private var escaped = false
        fun feed(bytes: ByteArray): List<ByteArray> {
            val frames = mutableListOf<ByteArray>()
            for (byte in bytes) {
                val value = byte.toInt() and 255
                if (value == 0x7e) {
                    if (collecting && !escaped && pending.size() > 0) frames += pending.toByteArray()
                    pending.reset(); collecting = true; escaped = false
                } else if (collecting) {
                    when {
                        escaped -> { pending.write(value xor 0x20); escaped = false }
                        value == 0x7d -> escaped = true
                        else -> pending.write(value)
                    }
                    if (pending.size() > 8192) { pending.reset(); collecting = false; escaped = false }
                }
            }
            return frames
        }
    }
}
