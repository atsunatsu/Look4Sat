package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.CwTransmitPresets
import com.rtbishop.look4sat.core.domain.repository.HandsetMacroStatus

internal object HandsetMacroSync {
    fun normalize(presets: List<String>): List<String> {
        require(presets.size == 8) { "Eight CW presets are required" }
        return presets.mapIndexed { index, text ->
            CwTransmitPresets.normalize(text).also {
                require(it.length <= 92) { "F${index + 1} exceeds the handset limit of 92 characters" }
            }
        }
    }

    suspend fun stage(presets: List<String>, transaction: Long,
        command: suspend (ByteArray) -> ByteArray?): HandsetMacroStatus {
        require(transaction in 1..0xffffffffL) { "Invalid CW preset transaction" }
        val texts = normalize(presets)
        suspend fun exchange(data: ByteArray): HandsetMacroStatus =
            checkNotNull(command(data)?.let(L4sProtocol::macroStatus)) { "CW preset synchronization failed; retry" }
        val status = exchange(byteArrayOf(0, -1))
        check(!status.readOnly) { "Handset macro storage is read-only" }
        check(!status.busy) { "Handset macro storage is busy" }
        val begin = ByteArray(9).also {
            it[0] = 1; L4sProtocol.put32(it, 1, status.generation); L4sProtocol.put32(it, 5, transaction)
        }
        check(exchange(begin).phase == 1) { "Handset did not accept the CW preset batch" }
        texts.forEachIndexed { index, text ->
            check(exchange(byteArrayOf(2, index.toByte(), text.length.toByte()) + text.toByteArray(Charsets.US_ASCII)).phase == 1) {
                "Handset rejected F${index + 1}"
            }
        }
        return exchange(byteArrayOf(3)).also {
            check(it.waitingForConfirmation) { "Handset did not accept the complete CW preset batch" }
        }
    }
}
