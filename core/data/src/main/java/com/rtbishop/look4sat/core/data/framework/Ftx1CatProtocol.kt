/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.core.data.framework

import java.util.Locale
import kotlin.math.abs

/** FTX-1 CAT reference 2508-C: MAIN/SUB addressing, not legacy five-byte Yaesu CAT. */
internal object Ftx1CatProtocol {
    const val MIN_HZ = 30_000L
    const val MAX_HZ = 470_000_000L
    val modes = mapOf("LSB" to '1',"USB" to '2',"CW" to '3',"CW-U" to '3',"FM" to '4',
        "AM" to '5',"RTTY" to '6',"CW-R" to '7',"CW-L" to '7',"DATA-L" to '8',
        "RTTY-R" to '9',"DATA-FM" to 'A',"PKT" to 'A',"FM-N" to 'B',"NFM" to 'B',
        "DATA-U" to 'C',"USB-D" to 'C',"DIG" to 'C',"LSB-D" to '8',"AM-N" to 'D',
        "DATA-FM-N" to 'F')
    private val canonical = mapOf('1' to "LSB",'2' to "USB",'3' to "CW",'4' to "FM",'5' to "AM",
        '6' to "RTTY",'7' to "CW-R",'8' to "DATA-L",'9' to "RTTY-R",'A' to "DATA-FM",
        'B' to "FM-N",'C' to "DATA-U",'D' to "AM-N",'E' to "PSK",'F' to "DATA-FM-N",
        'H' to "C4FM-DN",'I' to "C4FM-VW")
    private val tones = doubleArrayOf(67.0,69.3,71.9,74.4,77.0,79.7,82.5,85.4,88.5,91.5,
        94.8,97.4,100.0,103.5,107.2,110.9,114.8,118.8,123.0,127.3,131.8,136.5,141.3,
        146.2,151.4,156.7,159.8,162.2,165.5,167.9,171.3,173.8,177.3,179.9,183.5,186.2,
        189.9,192.8,196.6,199.5,203.5,206.5,210.7,218.1,225.7,229.1,233.6,241.8,250.3,254.1)

    fun frequency(side: Int, hz: Long): String? = if (side in 0..1 && hz in MIN_HZ..MAX_HZ)
        String.format(Locale.US,"F%c%09d;",if(side==0) 'A' else 'B',hz) else null
    fun parseFrequency(frame: String, side: Int): Long? {
        val prefix = if(side==0) "FA" else "FB"
        if (!frame.matches(Regex("${prefix}[0-9]{9};"))) return null
        return frame.substring(2,11).toLongOrNull()?.takeIf { it in MIN_HZ..MAX_HZ }
    }
    fun mode(side: Int, mode: String): String? = modes[mode.uppercase(Locale.US)]?.let { "MD$side$it;" }
    fun parseMode(frame: String, side: Int): String? =
        if(frame.length==5 && frame.startsWith("MD$side") && frame.endsWith(';')) canonical[frame[3]] else null
    fun tone(side: Int, tone: Double): String? {
        if (!tone.isFinite()) return null
        val index=tones.indexOfFirst { abs(it-tone)<0.01 }
        return if(index<0) null else String.format(Locale.US,"CN%d0%03d;",side,index)
    }
    fun dataMode(enabled: Boolean, base: String): String? = if(!enabled) base else when(base.uppercase(Locale.US)) {
        "USB","USB-D","DATA-U","DIG" -> "DATA-U"
        "LSB","LSB-D","DATA-L" -> "DATA-L"
        "FM","DATA-FM","PKT" -> "DATA-FM"
        "FM-N","NFM","DATA-FM-N" -> "DATA-FM-N"
        else -> null
    }

    class Decoder {
        private val pending = StringBuilder()
        fun clear() { pending.setLength(0) }
        fun feed(bytes: ByteArray): List<String> = buildList {
            for (byte in bytes) {
                val value=byte.toInt() and 0xff
                if (value==';'.code) {
                    if(pending.isNotEmpty()) add(pending.toString()+";")
                    this@Decoder.clear()
                } else if(value in 0x20..0x7e) {
                    if(pending.length>=256) this@Decoder.clear()
                    pending.append(value.toChar())
                } else if(value!=10 && value!=13) this@Decoder.clear()
            }
        }
    }
}
