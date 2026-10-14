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

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Icom CI-V protocol encoder/decoder for the IC-705, IC-9700, and IC-910 family.
 *
 * Frame structure:
 *   FE FE <DEST> <SRC> <CMD> [<SUB>] [<DATA...>] FD
 *
 * IC-705 default CI-V address : 0xA4
 * Controller (us) address     : 0xE0
 */
object IcomCivProtocol {

    // ── Framing constants ──────────────────────────────────────────────────
    const val PREAMBLE: Byte      = 0xFE.toByte()
    const val END_OF_MSG: Byte    = 0xFD.toByte()
    const val ACK_OK: Byte        = 0xFB.toByte()
    const val ACK_NG: Byte        = 0xFA.toByte()

    // ── Address constants ──────────────────────────────────────────────────
    /** Default CI-V address of the IC-705. */
    const val ADDR_IC705: Byte    = 0xA4.toByte()
    const val ADDR_IC9700: Byte   = 0xA2.toByte()
    const val ADDR_IC910: Byte    = 0x60
    /** Default CI-V address of the controller (us). */
    const val ADDR_CTRL: Byte     = 0xE0.toByte()

    // ── Command bytes ──────────────────────────────────────────────────────
    /** Read operating frequency (main VFO). */
    const val CMD_READ_FREQ: Byte           = 0x03
    /** Read operating mode (main VFO). */
    const val CMD_READ_MODE: Byte           = 0x04
    /** Set operating frequency (main VFO). */
    const val CMD_SET_FREQ: Byte            = 0x05
    /** Set operating mode. */
    const val CMD_SET_MODE: Byte            = 0x06
    /** Select VFO mode or a specific VFO. */
    const val CMD_SELECT_VFO: Byte          = 0x07
    /** Set repeater duplex / SPLIT. */
    const val CMD_DUPLEX_SPLIT: Byte        = 0x0F
    /** Read/write memory and model-specific settings. */
    const val CMD_MEMORY_CONTROL: Byte      = 0x1A
    /** Read/write CTCSS tone frequency. */
    const val CMD_CTCSS_TONE: Byte          = 0x1B
    /** Read/write misc settings (used for enabling CTCSS encode). */
    const val CMD_MISC_SETTING: Byte        = 0x16
    /** Read/write selected-VFO frequency (cmd 0x25). */
    const val CMD_SELECTED_VFO_FREQ: Byte   = 0x25
    /** Read/write selected or unselected VFO mode (cmd 0x26). */
    const val CMD_SELECTED_VFO_MODE: Byte   = 0x26
    /** Read/write transceiver state (sub 0x00 controls PTT). */
    const val CMD_TRANSCEIVER_STATUS: Byte  = 0x1C

    // ── Sub-command bytes ──────────────────────────────────────────────────
    /** Sub for CMD_SELECT_VFO: select VFO-A (main). */
    const val SUB_VFO_A: Byte   = 0x00
    /** Sub for CMD_SELECT_VFO: select VFO-B (sub). */
    const val SUB_VFO_B: Byte   = 0x01
    /** Satellite-mode receiver (MAIN) selection. */
    const val SUB_MAIN: Byte = 0xD0.toByte()
    /** Satellite-mode transmitter (SUB) selection. */
    const val SUB_SUB: Byte = 0xD1.toByte()
    /** Sub for CMD_DUPLEX_SPLIT: simplex / split OFF. */
    const val SUB_SPLIT_OFF: Byte = 0x00
    /** Sub for CMD_DUPLEX_SPLIT: SPLIT ON. */
    const val SUB_SPLIT_ON: Byte  = 0x01
    /** Sub for CMD_SELECTED_VFO_FREQ: selected (active) VFO frequency. */
    const val SUB_SELECTED_VFO: Byte = 0x00
    /** Sub for CMD_SELECTED_VFO_FREQ: unselected (inactive / TX in split) VFO frequency. */
    const val SUB_UNSELECTED_VFO: Byte = 0x01
    /** Sub for CMD_MISC_SETTING: CTCSS/DTCS tone squelch. */
    const val SUB_CTCSS_SETTING: Byte = 0x42.toByte()
    /** Sub for CMD_MISC_SETTING: IC-9700 satellite mode. */
    const val SUB_SATELLITE_MODE: Byte = 0x5A
    /** IC-910 family satellite mode under CMD 0x1A. */
    const val SUB_IC910_SATELLITE_MODE: Byte = 0x07
    const val SUB_DATA_MODE: Byte = 0x06
    const val SUB_PTT: Byte = 0x00

    // ── Mode bytes ────────────────────────────────────────────────────────
    /** Maps mode strings (upper-case) to CI-V mode bytes used by supported radios. */
    val MODE_TO_BYTE: Map<String, Byte> = mapOf(
        "LSB"    to 0x00,
        "USB"    to 0x01,
        "AM"     to 0x02,
        "CW"     to 0x03,
        "RTTY"   to 0x04,
        "FM"     to 0x05,
        "WFM"    to 0x06,
        "CW-R"   to 0x07,
        "RTTY-R" to 0x08,
        "DV"     to 0x17,
        "AFSK"   to 0x05  // AFSK uses FM modulation
    )

    val BYTE_TO_MODE: Map<Byte, String> = MODE_TO_BYTE
        .filterKeys { it != "AFSK" }
        .entries
        .associate { it.value to it.key }

    data class ModeSpec(
        val baseMode: String,
        val modeByte: Byte,
        val filter: Byte? = null
    )

    fun modeSpec(mode: String): ModeSpec? = when (mode.uppercase(Locale.US)) {
        "FM-N", "NFM" -> ModeSpec("FM", 0x05, 0x02)
        "USB-D", "DATA-U", "PKTUSB", "DIG" -> ModeSpec("USB", 0x01, 0x02)
        "LSB-D", "DATA-L", "PKTLSB" -> ModeSpec("LSB", 0x00, 0x02)
        "DATA-FM", "PKTFM", "PKT" -> ModeSpec("FM", 0x05, 0x02)
        else -> MODE_TO_BYTE[mode.uppercase(Locale.US)]?.let {
            ModeSpec(mode.uppercase(Locale.US), it)
        }
    }

    // ── Frequency BCD encoding ─────────────────────────────────────────────

    /**
     * Encode a frequency in Hz to the IC-705's 5-byte BCD format.
     *
     * The IC-705 uses 5 bytes, LSB pair first, with 1 Hz resolution.
     * Example: 145,500,000 Hz → "0145500000" → pairs LSB→MSB:
     *   [00, 00, 50, 45, 01]
     */
    fun encodeFrequencyBcd(frequencyHz: Long): ByteArray {
        require(frequencyHz in 0L..9_999_999_999L) { "CI-V frequency is outside the 10-digit BCD range" }
        val digits = String.format(Locale.US, "%010d", frequencyHz)
        val bcd = ByteArray(5)
        for (i in 0 until 5) {
            // digits are MSB first; we want pair index 0 = LSB pair
            val pairIndex = 4 - i
            val high = digits[pairIndex * 2] - '0'
            val low  = digits[pairIndex * 2 + 1] - '0'
            bcd[i] = ((high shl 4) or low).toByte()
        }
        return bcd
    }

    /**
     * Decode 5-byte BCD frequency (LSB pair first) to Hz.
     */
    fun decodeFrequencyBcd(bcd: ByteArray): Long {
        require(bcd.size == 5 && bcd.all(::isValidBcd)) { "Invalid CI-V frequency BCD" }
        // Build digit string MSB→LSB by reversing the byte order
        var freqHz = 0L
        for (i in 4 downTo 0) {
            val b    = bcd[i].toInt() and 0xFF
            val high = b shr 4
            val low  = b and 0x0F
            freqHz   = freqHz * 100 + high * 10 + low
        }
        return freqHz
    }

    /**
     * Encode a CTCSS tone (Hz, e.g. 67.0) to CI-V's 3-byte big-endian BCD.
     * 67.0 → 670 (tenths of Hz) → BCD bytes [0x00, 0x06, 0x70].
     */
    fun encodeCtcssToneBcd(toneHz: Double): ByteArray {
        require(toneHz.isFinite() && toneHz in 0.0..999.9) { "Invalid CTCSS tone" }
        val tone01 = (toneHz * 10).roundToLong()
        val digits = String.format(Locale.US, "%06d", tone01)
        return byteArrayOf(
            ((digits[0] - '0') shl 4 or (digits[1] - '0')).toByte(),
            ((digits[2] - '0') shl 4 or (digits[3] - '0')).toByte(),
            ((digits[4] - '0') shl 4 or (digits[5] - '0')).toByte()
        )
    }

    // ── Message builders ───────────────────────────────────────────────────

    /** Wrap payload bytes in a CI-V frame: FE FE DEST SRC ... FD. */
    private fun frame(vararg payload: Byte): ByteArray {
        return byteArrayOf(PREAMBLE, PREAMBLE, ADDR_IC705, ADDR_CTRL) +
                payload +
                byteArrayOf(END_OF_MSG)
    }

    /** Set operating frequency via CMD 0x05 (main VFO). */
    fun buildSetFreqCommand(frequencyHz: Long): ByteArray {
        return frame(CMD_SET_FREQ, *encodeFrequencyBcd(frequencyHz))
    }

    /** Reject ambiguous legacy lengths instead of guessing a VHF/UHF scaling. */
    fun parseIc820FrequencyPayload(payload: ByteArray): Long? =
        if (payload.size == 5) parseFrequencyPayload(payload) else null

    // IC-820's own command table reverses these selectors versus newer Icoms.
    fun buildIc820BandAccessCommand(main: Boolean): ByteArray =
        frame(CMD_SELECT_VFO, if (main) 0xD1.toByte() else 0xD0.toByte())

    fun buildIc820SplitModeCommand(enabled: Boolean): ByteArray =
        frame(CMD_DUPLEX_SPLIT, if (enabled) 0x00 else 0x01)

    /**
     * Set selected-VFO frequency via CMD 0x25 sub 0x00.
     * Selection is controlled by CMD 0x07 and does not change when PTT is keyed.
     */
    fun buildSetWorkingFreqCommand(frequencyHz: Long): ByteArray {
        return frame(CMD_SELECTED_VFO_FREQ, SUB_SELECTED_VFO, *encodeFrequencyBcd(frequencyHz))
    }

    /**
     * Set unselected-VFO frequency via CMD 0x25 sub 0x01.
     * With VFO-A selected for receive, this targets VFO-B for transmit even
     * while PTT is keyed.
     */
    fun buildSetUnselectedVfoFreqCommand(frequencyHz: Long): ByteArray {
        return frame(CMD_SELECTED_VFO_FREQ, SUB_UNSELECTED_VFO, *encodeFrequencyBcd(frequencyHz))
    }

    /** Read operating frequency (CMD 0x03). */
    fun buildReadFreqCommand(): ByteArray = frame(CMD_READ_FREQ)

    /** Read operating mode (CMD 0x04). */
    fun buildReadModeCommand(): ByteArray = frame(CMD_READ_MODE)

    /** Read selected (active) VFO frequency (CMD 0x25 sub 0x00). */
    fun buildReadWorkingFreqCommand(): ByteArray = frame(CMD_SELECTED_VFO_FREQ, SUB_SELECTED_VFO)

    /** Read unselected (inactive/TX in split) VFO frequency (CMD 0x25 sub 0x01). */
    fun buildReadTxVfoFreqCommand(): ByteArray = frame(CMD_SELECTED_VFO_FREQ, SUB_UNSELECTED_VFO)

    /** Set operating mode (CMD 0x06). Filter byte is omitted — radio uses its default filter for the mode. */
    fun buildSetModeCommand(mode: String): ByteArray? {
        val spec = modeSpec(mode) ?: return null
        return if (spec.filter == null) {
            frame(CMD_SET_MODE, spec.modeByte)
        } else {
            frame(CMD_SET_MODE, spec.modeByte, spec.filter)
        }
    }

    /** Set selected (0x00) or unselected (0x01) VFO mode via CMD 0x26. */
    fun isDataMode(mode: String): Boolean = mode.uppercase(Locale.US) in
        setOf("USB-D", "DATA-U", "PKTUSB", "DIG", "LSB-D", "DATA-L", "PKTLSB", "DATA-FM", "PKTFM", "PKT")

    fun buildSetVfoModeCommand(selected: Boolean, mode: String, completeFields: Boolean = false): ByteArray? {
        val spec = modeSpec(mode) ?: return null
        val selector = if (selected) SUB_SELECTED_VFO else SUB_UNSELECTED_VFO
        return if (spec.filter == null && !completeFields) {
            frame(CMD_SELECTED_VFO_MODE, selector, spec.modeByte)
        } else {
            // CMD 0x26 is selector, mode, DATA, filter (unlike CMD 0x06).
            val data: Byte = if (completeFields && isDataMode(mode)) 0x01 else 0x00
            frame(CMD_SELECTED_VFO_MODE, selector, spec.modeByte, data, spec.filter ?: 0x01)
        }
    }

    /** Read selected (0x00) or unselected (0x01) VFO mode via CMD 0x26. */
    fun buildReadVfoModeCommand(selected: Boolean): ByteArray {
        val selector = if (selected) SUB_SELECTED_VFO else SUB_UNSELECTED_VFO
        return frame(CMD_SELECTED_VFO_MODE, selector)
    }

    /** Select VFO-A (CMD 0x07 sub 0x00). */
    fun buildSelectVfoACommand(): ByteArray = frame(CMD_SELECT_VFO, SUB_VFO_A)

    /** Select VFO-B (CMD 0x07 sub 0x01). */
    fun buildSelectVfoBCommand(): ByteArray = frame(CMD_SELECT_VFO, SUB_VFO_B)

    /** Select the MAIN receiver while dedicated satellite mode is active. */
    fun buildSelectMainCommand(): ByteArray = frame(CMD_SELECT_VFO, SUB_MAIN)

    /** Select the SUB transmitter while dedicated satellite mode is active. */
    fun buildSelectSubCommand(): ByteArray = frame(CMD_SELECT_VFO, SUB_SUB)

    /** Enter VFO operating mode (CMD 0x07 with no sub-command). */
    fun buildEnterVfoModeCommand(): ByteArray = frame(CMD_SELECT_VFO)

    /** Enable or disable SPLIT mode (CMD 0x0F). */
    fun buildSplitModeCommand(enable: Boolean): ByteArray {
        val sub = if (enable) SUB_SPLIT_ON else SUB_SPLIT_OFF
        return frame(CMD_DUPLEX_SPLIT, sub)
    }

    fun buildReadSplitModeCommand(): ByteArray = frame(CMD_DUPLEX_SPLIT)

    /** Enable or disable the IC-9700's dedicated satellite mode. */
    fun buildSatelliteModeCommand(enable: Boolean): ByteArray {
        return frame(CMD_MISC_SETTING, SUB_SATELLITE_MODE, if (enable) 0x01 else 0x00)
    }

    /** Read the IC-9700's dedicated satellite-mode state. */
    fun buildReadSatelliteModeCommand(): ByteArray = frame(CMD_MISC_SETTING, SUB_SATELLITE_MODE)

    /** Enable or disable the IC-910 family's dedicated satellite mode (CMD 0x1A sub 0x07). */
    fun buildIc910SatelliteModeCommand(enable: Boolean): ByteArray {
        return frame(CMD_MEMORY_CONTROL, SUB_IC910_SATELLITE_MODE, if (enable) 0x01 else 0x00)
    }

    /** Read the IC-910 family's dedicated satellite-mode state. */
    fun buildReadIc910SatelliteModeCommand(): ByteArray =
        frame(CMD_MEMORY_CONTROL, SUB_IC910_SATELLITE_MODE)

    /** Enable or disable the selected receiver's DATA path. Filter 2 is the documented default. */
    fun buildDataModeCommand(enabled: Boolean, filter: Int = 2): ByteArray {
        require(filter in 1..3)
        return frame(
            CMD_MEMORY_CONTROL,
            SUB_DATA_MODE,
            if (enabled) 0x01 else 0x00,
            if (enabled) filter.toByte() else 0x00
        )
    }

    fun buildReadDataModeCommand(): ByteArray = frame(CMD_MEMORY_CONTROL, SUB_DATA_MODE)

    fun parseDataModeState(payload: ByteArray): Boolean? {
        if (payload.size < 2 || payload[0] != SUB_DATA_MODE) return null
        return when (payload[1]) {
            0x00.toByte() -> false
            0x01.toByte() -> true
            else -> null
        }
    }

    /**
     * Enable/disable CTCSS encode (CMD 0x16 sub 0x42).
     * 0x01 = CTCSS encoder ON, 0x00 = OFF.
     */
    fun buildCtcssModeCommand(enabled: Boolean): ByteArray {
        val value: Byte = if (enabled) 0x01 else 0x00
        return frame(CMD_MISC_SETTING, SUB_CTCSS_SETTING, value)
    }

    /**
     * Set CTCSS tone frequency (CMD 0x1B sub 0x00).
     */
    fun buildSetCtcssToneCommand(toneHz: Double): ByteArray {
        val bcd = encodeCtcssToneBcd(toneHz)
        return frame(CMD_CTCSS_TONE, 0x00, *bcd)
    }

    /** Set PTT through CI-V CMD 0x1C sub 0x00; 0x01 = TX, 0x00 = RX. */
    fun buildPttCommand(enabled: Boolean): ByteArray =
        frame(CMD_TRANSCEIVER_STATUS, SUB_PTT, if (enabled) 0x01 else 0x00)

    /** Read PTT state through CI-V CMD 0x1C sub 0x00. */
    fun buildReadPttCommand(): ByteArray = frame(CMD_TRANSCEIVER_STATUS, SUB_PTT)

    /** Parse [SUB_PTT, state] from a CMD 0x1C response. */
    fun parsePttState(payload: ByteArray): Boolean? {
        if (payload.size < 2 || payload[0] != SUB_PTT) return null
        return when (payload[1]) {
            0x00.toByte() -> false
            0x01.toByte() -> true
            else -> null
        }
    }

    // ── Response parsing ───────────────────────────────────────────────────

    /**
     * Find and parse a complete CI-V response frame from a buffer.
     *
     * Returns the bytes between "FE FE E0 A4 <CMD>" and FD, or null if no
     * complete frame was found. The search is tolerant of interleaved
     * broadcast traffic.
     *
     * @param buf       bytes accumulated from the radio
     * @param expectCmd the command byte we are looking for in the reply, or
     *                  null to accept any command response from the radio
     */
    fun parseResponse(
        buf: ByteArray,
        expectCmd: Byte?,
        radioAddress: Byte = ADDR_IC705,
        payloadMatches: (ByteArray) -> Boolean = { true }
    ): ParsedResponse? {
        var i = 0
        while (i < buf.size - 5) {
            // Look for FE FE preamble
            if (buf[i] != PREAMBLE || buf[i + 1] != PREAMBLE) { i++; continue }
            val dest = buf[i + 2]
            val src  = buf[i + 3]
            val cmd  = buf[i + 4]
            // We only care about frames addressed to us from the radio
            if (dest != ADDR_CTRL || src != radioAddress) { i++; continue }
            // Find the terminating FD
            val fdIdx = buf.indexOf(END_OF_MSG, startIndex = i + 5)
            if (fdIdx < 0) break  // incomplete frame, wait for more data
            val payload = buf.copyOfRange(i + 5, fdIdx)
            if ((expectCmd == null || cmd == expectCmd) && payloadMatches(payload)) {
                return ParsedResponse(cmd, payload, fdIdx + 1)
            }
            i = fdIdx + 1
        }
        return null
    }

    private fun ByteArray.indexOf(b: Byte, startIndex: Int): Int {
        for (k in startIndex until size) if (this[k] == b) return k
        return -1
    }

    /**
     * Check whether a buffer contains an OK acknowledgement (FB FD) from
     * the radio. Tolerates broadcast noise before the ACK.
     */
    fun ackStatus(buf: ByteArray, radioAddress: Byte = ADDR_IC705): Boolean? {
        var i = 0
        while (i < buf.size - 5) {
            if (buf[i] != PREAMBLE || buf[i + 1] != PREAMBLE) { i++; continue }
            val dest = buf[i + 2]
            val src  = buf[i + 3]
            val cmd  = buf[i + 4]
            if (dest != ADDR_CTRL || src != radioAddress) { i++; continue }
            // Skip to FD
            val fdIdx = buf.indexOf(END_OF_MSG, startIndex = i + 5)
            if (fdIdx < 0) break
            if (cmd == ACK_OK) return true
            if (cmd == ACK_NG) return false
            i = fdIdx + 1
        }
        return null
    }

    fun containsAck(buf: ByteArray, radioAddress: Byte = ADDR_IC705): Boolean =
        ackStatus(buf, radioAddress) == true

    /** Parse the five-byte frequency payload returned by CMD 0x03. */
    fun parseFrequencyPayload(payload: ByteArray): Long? =
        payload.takeIf { it.size >= 5 && it.copyOfRange(0, 5).all(::isValidBcd) }
            ?.copyOfRange(0, 5)
            ?.let(::decodeFrequencyBcd)

    /** Parse a CMD 0x25 response and verify that it belongs to the requested VFO selector. */
    fun parseVfoFrequencyPayload(payload: ByteArray, selected: Boolean): Long? {
        if (payload.size < 6) return null
        val expected = if (selected) SUB_SELECTED_VFO else SUB_UNSELECTED_VFO
        if (payload[0] != expected) return null
        return parseFrequencyPayload(payload.copyOfRange(1, 6))
    }

    /** Parse [subcommand, state] from an IC-9700 or IC-910 satellite-mode response. */
    fun parseSatelliteModeState(
        payload: ByteArray,
        subcommand: Byte = SUB_SATELLITE_MODE
    ): Boolean? {
        if (payload.size < 2 || payload[0] != subcommand) return null
        return when (payload[1]) {
            0x00.toByte() -> false
            0x01.toByte() -> true
            else -> null
        }
    }

    /** Parse the mode byte returned by CMD 0x04. An optional filter byte follows it. */
    fun parseModePayload(payload: ByteArray): String? = payload.firstOrNull()?.let(BYTE_TO_MODE::get)

    /** Parse a CMD 0x26 response and verify that it belongs to the requested VFO selector. */
    fun parseVfoModePayload(payload: ByteArray, selected: Boolean): String? {
        if (payload.size < 2) return null
        val expected = if (selected) SUB_SELECTED_VFO else SUB_UNSELECTED_VFO
        if (payload[0] != expected) return null
        return BYTE_TO_MODE[payload[1]]
    }

    private fun isValidBcd(value: Byte): Boolean {
        val number = value.toInt() and 0xff
        return number ushr 4 <= 9 && number and 0x0f <= 9
    }

    /**
     * Parse frequency + mode from a CMD_READ_FREQ reply payload.
     * Payload layout after stripping command byte: [5 freq bytes]
     * The IC-705 does not include a mode byte; if one is present it is parsed, otherwise mode is empty.
     */
    fun parseFreqModePayload(payload: ByteArray): Pair<Long, String>? {
        if (payload.size < 5) return null
        if (!payload.copyOfRange(0, 5).all(::isValidBcd)) return null
        val freqHz = decodeFrequencyBcd(payload.copyOfRange(0, 5))
        val mode   = if (payload.size > 5) BYTE_TO_MODE[payload[5]] ?: return null else ""
        return freqHz to mode
    }

    /** Hex dump of bytes, useful for debug logging. */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

    data class ParsedResponse(
        val cmd: Byte,
        val payload: ByteArray,
        /** Index in the source buffer immediately after the FD terminator. */
        val nextOffset: Int
    )
}
