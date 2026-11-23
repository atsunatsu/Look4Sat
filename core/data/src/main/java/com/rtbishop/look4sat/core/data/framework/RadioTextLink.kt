package com.rtbishop.look4sat.core.data.framework

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Request/response framing for serial and TCP CAT streams. Calls are serialized by the radio actor. */
internal class RadioTextLink(
    private val transport: RadioTransport,
    private val terminator: Char,
    private val charset: java.nio.charset.Charset = Charsets.US_ASCII
) {
    suspend fun write(value: String): Boolean =
        transport.isConnected && transport.write((value + terminator).toByteArray(charset))

    suspend fun query(value: String, prefix: String, timeoutMillis: Long = 450L): String? {
        transport.readAvailable(2_048)
        if (!write(value)) return null
        return withTimeoutOrNull(timeoutMillis) {
            val bytes = java.io.ByteArrayOutputStream()
            var inspected = 0
            val delimiter = terminator.toString().toByteArray(charset)
            while (transport.isConnected && bytes.size() < 8192) {
                val chunk = transport.readAvailable(512)
                if (chunk.isEmpty()) {
                    delay(5L)
                    continue
                }
                bytes.write(chunk)
                val buffer = bytes.toByteArray()
                var frameStart = inspected
                var index = inspected
                while (index <= buffer.size - delimiter.size) {
                    if (delimiter.indices.all { buffer[index + it] == delimiter[it] }) {
                        val answer = String(buffer, frameStart, index - frameStart, charset).trim()
                        if (answer == "?" || answer == "E") return@withTimeoutOrNull null
                        if (answer != value && answer.startsWith(prefix)) return@withTimeoutOrNull answer
                        frameStart = index + delimiter.size
                        inspected = frameStart
                    }
                    index += delimiter.size
                }
            }
            null
        }
    }
}
