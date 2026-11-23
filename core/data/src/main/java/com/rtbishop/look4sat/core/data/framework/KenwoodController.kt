package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.model.RadioLanLogin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class KenwoodModel(val id: String) {
    TH_D74("TH-D74"),
    TH_D75("TH-D75"),
    TS_2000("019"),
    TS_790("007"),
    TS_990("022"),
    TS_890("024"),
    TS_590("021"),
    TS_590SG("023");

    val handheld get() = this == TH_D74 || this == TH_D75
    val modernHf get() = this == TS_990 || this == TS_890
    val usesDataSwitch get() = this == TS_590 || this == TS_590SG
}

/** Kenwood PC command families. Legacy transceivers retain their own VFO selection layout. */
internal class KenwoodController(
    private val transport: RadioTransport,
    private val model: KenwoodModel,
    private val satelliteLayout: Boolean,
    private val useLanLogin: Boolean = false,
    private val login: RadioLanLogin? = null
) : IRadioController {
    private val link = RadioTextLink(transport, if (model.handheld) '\r' else ';',
        if (useLanLogin && model == KenwoodModel.TS_990) Charsets.UTF_16LE else Charsets.UTF_8)
    private var ready = false
    private var satellite = false
    private var rxSelected = true
    private var bandBMode: String? = null
    private var transverterOffset: Long? = null
    private var subtractIf = false
    override val isConnected get() = ready && transport.isConnected

    override suspend fun connect(): Boolean {
        try {
            if (!transport.connect()) return false
            if (useLanLogin && !authenticate()) return false
            val identity = link.query("ID", "ID") ?: return false
            if (if (model.handheld) !identity.startsWith("ID ${model.id}") else identity != "ID${model.id}") return false
            ready = readRawFrequency(true) != null
            return ready
        } finally {
            if (!ready) withContext(NonCancellable) { transport.disconnect() }
        }
    }

    private suspend fun authenticate(): Boolean {
        val frame = login?.let { kenwoodLoginFrame(model, it) } ?: return false
        return link.query("##CN", "##CN", 2_000) == "##CN1" &&
            link.query(frame, "##ID", 2_000) == "##ID1"
    }

    override suspend fun disconnect() {
        try {
            if (isConnected && satellite && model == KenwoodModel.TS_2000) link.write("SA0")
        } finally {
            ready = false
            satellite = false
            rxSelected = true
            transverterOffset = null
            bandBMode = null
            transport.disconnect()
        }
    }

    override suspend fun setBand(frequencyHz: Long): Boolean = frequencyHz > 0L

    override suspend fun setSplitMode(enabled: Boolean): Boolean {
        if (!isConnected || enabled && !satelliteLayout) return false
        return when (model) {
            KenwoodModel.TS_790 -> {
                satellite = enabled
                true
            }
            KenwoodModel.TS_2000 -> {
                if (!link.write(if (enabled) "SA1010000" else "SA0")) return false
                val value = link.query("SA", "SA")?.getOrNull(2)
                if (value != if (enabled) '1' else '0') return false
                satellite = enabled
                true
            }
            else -> !enabled
        }
    }

    override suspend fun setVfo(vfoA: Boolean): Boolean {
        if (!isConnected || !vfoA && !satellite) return false
        val confirmed = when (model) {
            KenwoodModel.TS_790 -> verify("DC${if (satellite && vfoA) '1' else '0'}", "DC")
            KenwoodModel.TS_2000 -> if (satellite) {
                link.write(if (vfoA) "SA1010000" else "SA1011000") &&
                    link.query("SA", "SA")?.getOrNull(5) == if (vfoA) '0' else '1'
            } else verify("FR0", "FR")
            KenwoodModel.TS_990, KenwoodModel.TS_890 -> verify("CB0", "CB") && verify("TB0", "TB")
            KenwoodModel.TS_590, KenwoodModel.TS_590SG -> verify("FR0", "FR")
            else -> vfoA
        }
        if (confirmed) rxSelected = vfoA
        return confirmed
    }

    override suspend fun readFrequencyAndMode(): Pair<Long, String>? {
        val frequency = readSkyFrequency(rxSelected) ?: return null
        val mode = readMode() ?: return null
        return frequency to mode
    }

    override suspend fun setFrequency(frequencyHz: Long) = writeFrequency(rxSelected, frequencyHz)
    override suspend fun readWorkingFrequency() = readSide(true)
    override suspend fun readTxVfoFrequency() = if (satellite) readSide(false) else null
    override suspend fun setWorkingFrequency(frequencyHz: Long) = writeSide(true, frequencyHz)
    override suspend fun setTxVfoFrequency(frequencyHz: Long) = satellite && writeSide(false, frequencyHz)

    private suspend fun readSide(rx: Boolean): Long? = onSide(rx) { readSkyFrequency(rx) }
    private suspend fun writeSide(rx: Boolean, frequency: Long): Boolean =
        onSide(rx) { writeFrequency(rx, frequency) } == true

    private suspend fun <T> onSide(rx: Boolean, operation: suspend () -> T): T? {
        if (model != KenwoodModel.TS_790 || !satellite || rxSelected == rx) return operation()
        if (!setVfo(rx)) return null
        val result: T
        var restored: Boolean
        try {
            result = operation()
        } finally {
            restored = withContext(NonCancellable + Ft4CatBudget.cleanup) { setVfo(!rx) }
        }
        return result.takeIf { restored }
    }

    private fun prefix(rx: Boolean) = if (model == KenwoodModel.TS_2000 && satellite && !rx) "FB" else "FA"

    private suspend fun readRawFrequency(rx: Boolean): Long? {
        if (model.handheld) return link.query("FO 1", "FO 1,")?.substringAfter(',')
            ?.substringBefore(',')?.takeIf { it.length == 10 }?.toLongOrNull()?.takeIf { it > 0 }
        val prefix = prefix(rx)
        return link.query(prefix, prefix)?.removePrefix(prefix)
            ?.takeIf { it.length == 11 }?.toLongOrNull()?.takeIf { it > 0 }
    }

    private suspend fun readSkyFrequency(rx: Boolean): Long? {
        val frequency = readRawFrequency(rx) ?: return null
        val offset = transverterOffset ?: return frequency
        return if (subtractIf) offset - frequency else offset + frequency
    }

    private suspend fun writeFrequency(rx: Boolean, hz: Long): Boolean {
        if (!isConnected || hz <= 0) return false
        if (model.handheld) {
            val mode = bandBMode ?: readMode() ?: return false
            val step = if (mode in setOf("USB", "LSB", "CW", "AM")) 20 else 5_000
            val target = ((hz + step / 2) / step) * step
            return target < 10_000_000_000L && link.write("FQ 1,${target.toString().padStart(10, '0')}") &&
                readRawFrequency(rx) == target
        }
        val offset = transverterOffset
        val raw = if (offset == null) hz else if (subtractIf) offset - hz else hz - offset
        if (raw !in 1L..99_999_999_999L) return false
        if (!link.write(prefix(rx) + raw.toString().padStart(11, '0'))) return false
        return readRawFrequency(rx) == raw
    }

    override suspend fun setMode(mode: String): Boolean {
        if (!isConnected) return false
        val codes = if (model.handheld) handheldModes else if (model.modernHf) modes + dataModes else modes
        val code = codes.entries.firstOrNull { it.value == if (model.handheld && mode == "FM") "NFM" else mode }?.key
            ?: return false
        if (model.handheld) {
            if (!link.write("VM 1,0") || !link.write("BC 1")) return false
            if (!verify("MD 1,$code", "MD 1", "MD 1,")) return false
            val fine = mode in setOf("USB", "LSB", "CW", "AM")
            if (!link.write(if (fine) "FT 1" else "FT 0") || fine && !link.write("FS 0")) return false
            bandBMode = codes[code]
            return true
        }
        if (model.modernHf || model.usesDataSwitch) {
            if (!setVfo(true) || !readTransverter()) return false
        }
        val prefix = if (model.modernHf) "OM0" else "MD"
        return verify(prefix + code, prefix)
    }

    override suspend fun setDataMode(enabled: Boolean, baseMode: String): Boolean = when {
        model.modernHf -> setMode(if (enabled) "DATA-$baseMode" else baseMode)
        model.usesDataSwitch -> readMode() == baseMode && verify(if (enabled) "DA1" else "DA0", "DA")
        else -> !enabled
    }

    private suspend fun readMode(): String? {
        val code = when {
            model.handheld -> link.query("MD 1", "MD 1,")?.getOrNull(5)
            model.modernHf -> link.query("OM0", "OM0")?.getOrNull(3)
            else -> link.query("MD", "MD")?.getOrNull(2)
        } ?: return null
        return (if (model.handheld) handheldModes else modes + dataModes)[code]
    }

    private suspend fun readTransverter(): Boolean {
        val menu = when (model) {
            KenwoodModel.TS_590 -> "EX0500000"
            KenwoodModel.TS_590SG -> "EX0560000"
            else -> "XV"
        }
        val state = link.query(menu, menu)?.removePrefix(menu)?.trim()?.singleOrNull() ?: return false
        if (state == '0') {
            transverterOffset = null
            return true
        }
        if (state != '1' && state != '2') return false
        val offset = link.query("XO", "XO")?.removePrefix("XO") ?: return false
        if (offset.length != 12 || offset.first() !in "01") return false
        transverterOffset = offset.drop(1).toLongOrNull()?.takeIf { it > 0 } ?: return false
        subtractIf = offset.first() == '1'
        return true
    }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean = !enabled
    override suspend fun setCtcssTone(toneHz: Double): Boolean = false
    override suspend fun pttOn(): Boolean = false
    override suspend fun pttOff(): Boolean = false

    private suspend fun verify(set: String, get: String, prefix: String = get): Boolean =
        link.write(set) && link.query(get, prefix) == set

    private companion object {
        val modes = mapOf('1' to "LSB", '2' to "USB", '3' to "CW", '4' to "FM", '5' to "AM")
        val dataModes = mapOf('C' to "DATA-LSB", 'D' to "DATA-USB", 'E' to "DATA-FM")
        val handheldModes = mapOf('2' to "AM", '3' to "LSB", '4' to "USB", '5' to "CW", '6' to "NFM")
    }
}

internal fun kenwoodLoginFrame(model: KenwoodModel, login: RadioLanLogin): String? {
    if (!model.modernHf) return null
    val max = if (model == KenwoodModel.TS_990) 8 else 32
    if (listOf(login.username, login.password).any { value ->
            value.length !in 1..max || value.any { it.code !in 32..126 || it == ';' }
        }) return null
    val user = login.username.length
    val pass = login.password.length
    val header = if (model == KenwoodModel.TS_990) "$user$pass"
        else "${if (login.administrator) 0 else 1}${user.toString().padStart(2, '0')}${pass.toString().padStart(2, '0')}"
    return "##ID$header${login.username}${login.password}"
}
