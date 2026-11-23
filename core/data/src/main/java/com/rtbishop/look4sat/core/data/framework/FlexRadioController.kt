package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.IRadioController
import java.math.BigDecimal
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** SmartSDR TCP command and slice-status channel. The operator supplies the RX/TX slices. */
internal class FlexRadioController(private val transport: RadioTransport) : IRadioController {
    private data class Slice(val frequency: Long? = null, val mode: String? = null,
                             val transmit: Boolean = false, val active: Boolean = false)

    private val slices = mutableMapOf<Int, Slice>()
    private val pending = StringBuilder()
    private var sequence = 0
    private var ready = false
    private var fullDuplex: Boolean? = null
    private var restoreDuplexOnClose = false
    private var selectedRx = true
    private var rxIndex: Int? = null
    private var txIndex: Int? = null
    override val isConnected get() = ready && transport.isConnected

    override suspend fun connect(): Boolean {
        try {
            if (!transport.connect() || !command("client program Look4Sat") ||
                !command("sub slice all") || !command("sub radio all")) return false
            ready = await { resolveRoles() }
            return ready
        } finally {
            if (!ready) withContext(NonCancellable) { transport.disconnect() }
        }
    }

    override suspend fun disconnect() {
        try {
            if (restoreDuplexOnClose && transport.isConnected) command("radio set full_duplex_enabled=0")
        } finally {
            ready = false
            restoreDuplexOnClose = false
            selectedRx = true
            rxIndex = null
            txIndex = null
            fullDuplex = null
            pending.clear()
            slices.clear()
            transport.disconnect()
        }
    }

    private fun resolveRoles(): Boolean {
        val tx = slices.entries.singleOrNull { it.value.transmit } ?: return false
        val receive = slices.entries.filter { it.key != tx.key }
        val rx = receive.singleOrNull() ?: receive.singleOrNull { it.value.active } ?: return false
        if (rx.value.frequency == null || tx.value.frequency == null) return false
        rxIndex = rx.key
        txIndex = tx.key
        return true
    }

    override suspend fun setSplitMode(enabled: Boolean): Boolean {
        if (!isConnected || !await { fullDuplex != null }) return false
        if (enabled && !resolveRoles()) return false
        val before = fullDuplex
        if (before == enabled) return true
        if (!command("radio set full_duplex_enabled=${if (enabled) 1 else 0}")) return false
        if (before == false && enabled) restoreDuplexOnClose = true
        if (before == true && !enabled) restoreDuplexOnClose = false
        return await { fullDuplex == enabled }
    }

    override suspend fun setVfo(vfoA: Boolean): Boolean {
        if (role(vfoA) == null) return false
        selectedRx = vfoA
        return true
    }

    private fun role(rx: Boolean): Int? {
        val index = if (rx) rxIndex else txIndex
        return index?.takeIf { slices[index]?.transmit == !rx }
    }

    override suspend fun setBand(frequencyHz: Long) = frequencyHz > 0
    override suspend fun setFrequency(frequencyHz: Long) = tune(selectedRx, frequencyHz)
    override suspend fun setWorkingFrequency(frequencyHz: Long) = tune(true, frequencyHz)
    override suspend fun setTxVfoFrequency(frequencyHz: Long) = tune(false, frequencyHz)
    override suspend fun readWorkingFrequency() = readSlice(true)?.frequency
    override suspend fun readTxVfoFrequency() = readSlice(false)?.frequency
    override suspend fun readFrequencyAndMode(): Pair<Long, String>? {
        val slice = readSlice(selectedRx) ?: return null
        val frequency = slice.frequency ?: return null
        val mode = slice.mode ?: return null
        return frequency to mode
    }

    private suspend fun readSlice(rx: Boolean): Slice? {
        if (!isConnected) return null
        readStatus()
        return role(rx)?.let(slices::get)
    }

    private suspend fun tune(rx: Boolean, hz: Long): Boolean {
        if (!isConnected || hz <= 0) return false
        readStatus()
        val index = role(rx) ?: return false
        if (slices[index]?.frequency == hz) return true
        if (!command("slice tune $index ${BigDecimal.valueOf(hz, 6).toPlainString()} autopan=0")) return false
        return await { role(rx) == index && slices[index]?.frequency == hz }
    }

    override suspend fun setMode(mode: String) = setSliceMode(selectedRx, mode)
    override suspend fun setDataMode(enabled: Boolean, baseMode: String) =
        setSliceMode(selectedRx, if (enabled) "DATA-$baseMode" else baseMode)
    override suspend fun setTxDataMode(enabled: Boolean, baseMode: String) =
        setSliceMode(false, if (enabled) "DATA-$baseMode" else baseMode)

    private suspend fun setSliceMode(rx: Boolean, mode: String): Boolean {
        val wireMode = when (mode) {
            "DATA-USB" -> "DIGU"
            "DATA-LSB" -> "DIGL"
            "USB", "LSB", "AM", "CW", "FM", "NFM" -> mode
            else -> return false
        }
        if (!isConnected) return false
        readStatus()
        val index = role(rx) ?: return false
        if (slices[index]?.mode == mode) return true
        if (!command("slice set $index mode=$wireMode")) return false
        return await { role(rx) == index && slices[index]?.mode == mode }
    }

    override suspend fun setCtcssMode(enabled: Boolean) = !enabled
    override suspend fun setCtcssTone(toneHz: Double) = false
    override suspend fun pttOn() = false
    override suspend fun pttOff() = false

    private suspend fun command(text: String): Boolean {
        val request = ++sequence
        if (!transport.write("C$request|$text\n".toByteArray(Charsets.US_ASCII))) return false
        var answer: Boolean? = null
        await(response = { line ->
            if (line.startsWith("R$request|")) {
                answer = line.substringAfter('|').substringBefore('|').toLongOrNull(16) == 0L
            }
        }) { answer != null }
        return answer == true
    }

    private suspend fun await(response: ((String) -> Unit)? = null, condition: () -> Boolean): Boolean = withTimeoutOrNull(800L) {
        while (transport.isConnected) {
            readStatus(response)
            if (condition()) return@withTimeoutOrNull true
            delay(5L)
        }
        false
    } == true

    private suspend fun readStatus(response: ((String) -> Unit)? = null) {
        pending.append(transport.readAvailable(4096).toString(Charsets.US_ASCII))
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline == -1) break
            val line = pending.substring(0, newline).trimEnd('\r')
            pending.delete(0, newline + 1)
            if (line.startsWith('S')) onStatus(line.substringAfter('|', ""))
            else if (line.startsWith('R')) response?.invoke(line)
        }
        if (pending.length > 8192) pending.clear()
    }

    private fun onStatus(body: String) {
        val fields = body.split(' ').mapNotNull {
            val position = it.indexOf('=')
            if (position < 0) null else it.substring(0, position) to it.substring(position + 1)
        }.toMap()
        if (body.startsWith("radio ")) {
            fields["full_duplex_enabled"]?.let { fullDuplex = it == "1" || it.equals("true", true) }
            return
        }
        if (!body.startsWith("slice ")) return
        val index = body.substringAfter(' ').substringBefore(' ').toIntOrNull()?.takeIf { it in 0..255 } ?: return
        if (fields["in_use"] == "0" || fields["removed"] == "1") {
            slices.remove(index)
            return
        }
        val current = slices[index] ?: Slice()
        val frequency = (fields["RF_frequency"] ?: fields["freq"])?.toBigDecimalOrNull()
            ?.movePointRight(6)?.toLong()?.takeIf { it > 0 }
        val mode = when (val value = fields["mode"]) {
            "DIGU" -> "DATA-USB"
            "DIGL" -> "DATA-LSB"
            else -> value
        }
        slices[index] = current.copy(frequency = frequency ?: current.frequency,
            mode = mode ?: current.mode, transmit = fields["tx"]?.let { it == "1" } ?: current.transmit,
            active = fields["active"]?.let { it == "1" } ?: current.active)
    }
}
