/*
 * Copyright 2026 Ylian Saint-Hilaire
 * Copyright 2026 Look4Sat contributors (Kotlin mono/32 kHz adaptation).
 * SPDX-License-Identifier: Apache-2.0
 * Adapted from HTCommander/src/lib/sbc at 33a13be1b55daa32184ed0c96b6171c8cf70e539.
 * License: licenses/HTCommander-Apache-2.0.txt
 */
package com.rtbishop.look4sat.core.data.framework

import kotlin.math.abs

/** N76's voice format only: SBC, 32 kHz, mono, 8 subbands. No mSBC/stereo fallback. */
internal class N76SbcCodec {
    private val analysis = Array(2) { Array(8) { IntArray(5) } }
    private val history = LongArray(4)
    private val synthesis = Array(2) { Array(8) { IntArray(10) } }
    private var analysisIndex = 0
    private var synthesisIndex = 0

    fun reset() {
        analysis.forEach { bank -> bank.forEach { it.fill(0) } }
        synthesis.forEach { bank -> bank.forEach { it.fill(0) } }
        history.fill(0); analysisIndex = 0; synthesisIndex = 0
    }

    fun encode(pcm: ShortArray, offset: Int = 0): ByteArray {
        require(offset >= 0 && pcm.size - offset >= SAMPLES_PER_FRAME)
        val samples = IntArray(128)
        repeat(16) { block -> analyze(pcm, offset + block * 8, samples, block * 8) }
        val scales = IntArray(8) { subband ->
            var maximum = 0
            repeat(16) { block -> maximum = maximum or abs(samples[block * 8 + subband]) }
            if (maximum == 0) 0 else 31 - Integer.numberOfLeadingZeros(maximum)
        }
        val allocation = allocation(scales, 18, snr = false)
        val result = ByteArray(44)
        result[0] = 0x9c.toByte(); result[1] = 0x71; result[2] = 18
        val bits = Bits(result, 32)
        scales.forEach { bits.put(it, 4) }
        for (block in 0 until 16) for (subband in 0 until 8) {
            val count = allocation[subband]
            if (count == 0) continue
            val range = (1L shl count) - 1
            val value = ((((samples[block * 8 + subband] * range) shr (scales[subband] + 1)) + range) shr 1).toInt()
            bits.put(value, count)
        }
        result[3] = crc(result).toByte()
        return result
    }

    fun frameLength(bytes: ByteArray, offset: Int = 0): Int? {
        if (offset < 0 || bytes.size - offset < 4 || bytes[offset] != 0x9c.toByte()) return null
        val header = bytes[offset + 1].toInt() and 255
        val bitpool = bytes[offset + 2].toInt() and 255
        if (header ushr 6 != 1 || header and 0x0c != 0 || header and 1 != 1 || bitpool !in 2..128) return null
        val blocks = ((header ushr 4) and 3) * 4 + 4
        return 8 + (blocks * bitpool + 7) / 8
    }

    fun decode(bytes: ByteArray, offset: Int = 0): ShortArray? {
        val length = frameLength(bytes, offset) ?: return null
        if (bytes.size - offset < length) return null
        val frame = bytes.copyOfRange(offset, offset + length)
        if (crc(frame) != frame[3].toInt() and 255) return null
        val blocks = ((frame[1].toInt() ushr 4) and 3) * 4 + 4
        val bits = Bits(frame, 32)
        val scales = IntArray(8) { bits.get(4) }
        val allocation = allocation(scales, frame[2].toInt() and 255, frame[1].toInt() and 2 != 0)
        val scale = 14 - scales.max()
        val samples = IntArray(blocks * 8)
        for (block in 0 until blocks) for (subband in 0 until 8) {
            val count = allocation[subband]
            if (count == 0) continue
            val sample = ((bits.get(count).toLong() shl 1) or 1) * N76SbcTables.rangeScale[count - 1]
            samples[block * 8 + subband] = ((sample - (1L shl 28)) shr (28 - (scales[subband] + 1 + scale))).toShort().toInt()
        }
        val output = ShortArray(blocks * 8)
        repeat(blocks) { synthesize(samples, it * 8, scale, output) }
        return output
    }

    private fun analyze(pcm: ShortArray, offset: Int, output: IntArray, outOffset: Int) {
        val index = analysisIndex shr 1
        val odd = analysisIndex and 1
        val slot = if (index != 0) 5 - index else 0
        val order = intArrayOf(7, 3, 6, 0, 5, 1, 4, 2)
        repeat(8) { analysis[odd][it][slot] = pcm[offset + order[it]].toInt() }
        val temp = LongArray(8) { subband ->
            (0 until 5).sumOf { j -> analysis[odd][subband][j].toLong() * N76SbcTables.analysisWindow[subband][index + j] }
        }
        val values = longArrayOf(temp[0] + history[0], temp[2] + temp[3], temp[4] + temp[5], temp[6] + temp[7],
            history[1], history[2], history[3], temp[1])
        history.fill(0)
        repeat(5) { j ->
            history[0] += analysis[odd][0][j].toLong() * N76SbcTables.analysisWindowB[0][index + j]
            for (i in 1..3) history[i] += analysis[odd][i * 2][j].toLong() * N76SbcTables.analysisWindowB[i * 2][index + j] -
                analysis[odd][i * 2 + 1][j].toLong() * N76SbcTables.analysisWindowB[i * 2 + 1][index + j]
        }
        val y = IntArray(8) { saturate((values[it] + (1 shl 14)) shr 15) }
        repeat(8) { i ->
            val sample = (0 until 8).sumOf { j -> y[j].toLong() * N76SbcTables.cosMatrix[i][j] }
            output[outOffset + i] = saturate((sample + (1 shl 12)) shr 13)
        }
        analysisIndex = (analysisIndex + 1) % 10
    }

    private fun synthesize(samples: IntArray, offset: Int, scale: Int, output: ShortArray) {
        val index = if (synthesisIndex != 0) 10 - synthesisIndex else 0
        val odd = index and 1
        val cosine = N76SbcTables.cos16
        val sums = IntArray(4) { (samples[offset + it] + samples[offset + 7 - it]) shr 1 }
        val diffs = IntArray(4) { (samples[offset + it] - samples[offset + 7 - it]) shr 1 }
        val a = longArrayOf(
            ((sums[0] + sums[3]) - (sums[2] + sums[1])).toLong() * cosine[4],
            diffs[0].toLong() * cosine[5] - diffs[1].toLong() * cosine[1] + diffs[2].toLong() * cosine[7] + diffs[3].toLong() * cosine[3],
            (sums[0] - sums[3]).toLong() * cosine[6] + (sums[2] - sums[1]).toLong() * cosine[2],
            diffs[0].toLong() * cosine[7] - diffs[1].toLong() * cosine[5] + diffs[2].toLong() * cosine[3] - diffs[3].toLong() * cosine[1])
        val b = longArrayOf(
            -diffs[0].toLong() * cosine[3] + diffs[1].toLong() * cosine[7] + diffs[2].toLong() * cosine[1] + diffs[3].toLong() * cosine[5],
            (sums[3] - sums[0]).toLong() * cosine[2] + (sums[2] - sums[1]).toLong() * cosine[6],
            -diffs[0].toLong() * cosine[1] - diffs[1].toLong() * cosine[3] - diffs[2].toLong() * cosine[5] - diffs[3].toLong() * cosine[7],
            (-(sums[0] + sums[3]) - (sums[2] + sums[1])).toLong() shl 13)
        val shift = 12 + scale
        for (i in 0..3) {
            a[i] = (a[i] + (1L shl (shift - 1))) shr shift
            b[i] = (b[i] + (1L shl (shift - 1))) shr shift
        }
        val out0 = synthesis[odd]; val out1 = synthesis[1 - odd]
        for (i in 0..3) {
            out0[i][index] = saturate(a[i])
            out1[i][index] = saturate(if (i == 0) -a[0] else b[i - 1])
            if (i != 0) out0[8 - i][index] = saturate(-a[i])
        }
        out0[4][index] = 0
        for (i in 4..7) out1[i][index] = saturate(b[7 - i])
        repeat(8) { subband ->
            val sum = (0 until 10).sumOf { j -> out0[subband][j].toLong() * N76SbcTables.synthesisWindow[subband][synthesisIndex + j] }
            output[offset + subband] = saturate((sum + (1 shl 12)) shr 13).toShort()
        }
        synthesisIndex = (synthesisIndex + 1) % 10
    }

    private fun allocation(scales: IntArray, pool: Int, snr: Boolean): IntArray {
        val need = IntArray(8) { i ->
            if (snr) scales[i] else if (scales[i] == 0) -5 else (scales[i] - N76SbcTables.loudness[i]).let { if (it > 0) it shr 1 else it }
        }
        var slice = maxOf(0, need.max()) + 1
        var count = 0
        var next = 0
        while (next < pool) {
            val previousSlice = slice--
            count = next
            need.forEach { n -> next += (if (n >= previousSlice && n < previousSlice + 15) 1 else 0) + (if (n == previousSlice) 1 else 0) }
        }
        val result = IntArray(8) { (need[it] - slice).let { n -> if (n < 2) 0 else n.coerceAtMost(16) } }
        for (i in 0..7) {
            if (count >= pool) break
            val more = if (result[i] in 1..15) 1 else if (need[i] == slice + 1 && pool > count + 1) 2 else 0
            result[i] += more; count += more
        }
        for (i in 0..7) {
            if (count >= pool) break
            if (result[i] < 16) { result[i]++; count++ }
        }
        check(result.sum() == pool)
        return result
    }

    private fun crc(frame: ByteArray): Int {
        var value = 0x0f
        for (index in intArrayOf(1, 2, 4, 5, 6, 7)) {
            value = value xor (frame[index].toInt() and 255)
            repeat(8) { value = ((value shl 1) xor if (value and 0x80 != 0) 0x1d else 0) and 255 }
        }
        return value
    }
    private fun saturate(value: Long) = value.coerceIn(-32768, 32767).toInt()
    private class Bits(private val bytes: ByteArray, private var position: Int) {
        fun put(value: Int, count: Int) {
            repeat(count) { index ->
                if (value ushr (count - index - 1) and 1 != 0) bytes[position / 8] = (bytes[position / 8].toInt() or (1 shl (7 - position % 8))).toByte()
                position++
            }
        }
        fun get(count: Int): Int {
            var value = 0
            repeat(count) { value = (value shl 1) or ((bytes[position / 8].toInt() ushr (7 - position % 8)) and 1); position++ }
            return value
        }
    }
    companion object { const val SAMPLE_RATE = 32_000; const val SAMPLES_PER_FRAME = 128 }
}
