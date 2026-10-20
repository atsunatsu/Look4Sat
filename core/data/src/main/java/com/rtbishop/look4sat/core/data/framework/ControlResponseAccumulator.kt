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

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay

class ControlResponseAccumulator(
    private val nowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val waitMillis: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun readUntil(
        transport: ControlTransport,
        maxBytes: Int,
        timeoutMillis: Long,
        complete: (ByteArray) -> Boolean
    ): ByteArray {
        require(maxBytes > 0 && timeoutMillis > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, INITIAL_CAPACITY))
        val start = nowMillis()
        while (nowMillis() - start < timeoutMillis && output.size() < maxBytes) {
            val chunk = transport.readAvailable(maxBytes-output.size())
            if (chunk.isEmpty()) {
                if (!transport.isConnected) break
                waitMillis(DEFAULT_POLL_MILLIS)
            } else {
                output.write(chunk, 0, minOf(chunk.size, maxBytes-output.size()))
                val bytes = output.toByteArray()
                if (complete(bytes)) return bytes
            }
        }
        return output.toByteArray()
    }
    suspend fun readUntilQuiet(
        transport: ControlTransport,
        maxBytes: Int,
        timeoutMillis: Long,
        quietMillis: Long = DEFAULT_QUIET_MILLIS,
        pollMillis: Long = DEFAULT_POLL_MILLIS
    ): ByteArray {
        require(maxBytes > 0)
        require(timeoutMillis > 0)
        require(quietMillis > 0)
        require(pollMillis > 0)

        val output = ByteArrayOutputStream(minOf(maxBytes, INITIAL_CAPACITY))
        val startedAt = nowMillis()
        var lastDataAt: Long? = null
        while (nowMillis() - startedAt < timeoutMillis && output.size() < maxBytes) {
            val chunk = transport.readAvailable(maxBytes - output.size())
            if (chunk.isNotEmpty()) {
                output.write(chunk, 0, minOf(chunk.size, maxBytes - output.size()))
                lastDataAt = nowMillis()
                if (output.size() >= maxBytes) break
            } else {
                val last = lastDataAt
                if (last != null && nowMillis() - last >= quietMillis) break
                waitMillis(pollMillis)
            }
        }
        return output.toByteArray()
    }

    private companion object {
        const val INITIAL_CAPACITY = 1_024
        const val DEFAULT_QUIET_MILLIS = 80L
        const val DEFAULT_POLL_MILLIS = 20L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
