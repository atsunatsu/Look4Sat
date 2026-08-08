/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import java.util.Locale

/**
 * Computes Doppler-corrected reciprocal frequencies for linear transponders.
 *
 * The full physical path:
 *
 * TX→RX (uplink → downlink):
 *   ① 地面发射 f_tx
 *   ② 卫星收到 f_tx × (c - v) / c  （上行多普勒）
 *   ③ 卫星转发 = passband映射(②)    （在卫星上做映射）
 *   ④ 地面听到 ③ × (c - v) / c     （下行多普勒）
 *
 * RX→TX (downlink → uplink):
 *   ④ 地面听到 f_rx
 *   ③ 卫星转发 = f_rx × (c + v) / c  （逆下行多普勒）
 *   ② 卫星收到 = 逆passband映射(③)
 *   ① 地面应发射 = ② × (c + v) / c   （逆上行多普勒）
 *
 * Addresses GitHub issue #91 (Custom frequency Doppler correction).
 */
object DopplerFrequencyCalculator {

    /**
     * Given a downlink frequency (what the user hears), compute the
     * uplink frequency the user should transmit.
     * Full path: ④→③→②→①
     */
    fun computeUplinkFromDownlink(
        downlinkHz: Long,
        transponder: SatRadio,
        orbitalPos: OrbitalPos
    ): Long? {
        if (!isLinearTransponder(transponder)) return null
        // ④→③ 逆下行多普勒：卫星转发的频率
        val satTx = orbitalPos.getUplinkFreq(downlinkHz)
        // ③→② 逆 passband 映射
        val satRx = TransponderMapper.mapDownlinkToUplink(satTx, transponder) ?: return null
        // ②→① 逆上行多普勒：地面应发射的频率
        return orbitalPos.getUplinkFreq(satRx)
    }

    /**
     * Given a downlink frequency (what the user hears), compute the
     * uplink frequency the user should transmit, with an offset applied
     * to the downlink (in Hz).
     * Full path: ④→③→②→①
     *
     * The user-entered downlink frequency already includes the offset, so subtract
     * it before the inverse downlink Doppler.
     */
    fun computeUplinkFromDownlinkWithOffset(
        downlinkHz: Long,
        transponder: SatRadio,
        orbitalPos: OrbitalPos,
        offsetHz: Long
    ): Long? {
        if (!isLinearTransponder(transponder)) return null
        // ④→③ 逆下行多普勒：卫星转发的频率（含 offset）
        val satTxWithOffset = orbitalPos.getUplinkFreq(downlinkHz)
        // ③ 去掉 offset（offset 在卫星本地频率域）
        val satTx = satTxWithOffset - offsetHz
        // ③→② 逆 passband 映射
        val satRx = TransponderMapper.mapDownlinkToUplink(satTx, transponder) ?: return null
        // ②→① 逆上行多普勒：地面应发射的频率
        return orbitalPos.getUplinkFreq(satRx)
    }

    /**
     * Given an uplink frequency (what the user transmits), compute the
     * downlink frequency the user will hear.
     * Full path: ①→②→③→④
     */
    fun computeDownlinkFromUplink(
        uplinkHz: Long,
        transponder: SatRadio,
        orbitalPos: OrbitalPos
    ): Long? {
        if (!isLinearTransponder(transponder)) return null
        // ①→② 上行多普勒：卫星收到的频率
        val satRx = orbitalPos.getDownlinkFreq(uplinkHz)
        // ②→③ passband 映射
        val satTx = TransponderMapper.mapUplinkToDownlink(satRx, transponder) ?: return null
        // ③→④ 下行多普勒：地面听到的
        return orbitalPos.getDownlinkFreq(satTx)
    }

    /**
     * Given an uplink frequency (what the user transmits), compute the
     * downlink frequency the user will hear, with an offset applied
     * to the downlink (in Hz).
     * Full path: ①→②→③→④
     */
    fun computeDownlinkFromUplinkWithOffset(
        uplinkHz: Long,
        transponder: SatRadio,
        orbitalPos: OrbitalPos,
        offsetHz: Long
    ): Long? {
        if (!isLinearTransponder(transponder)) return null
        // ①→② 上行多普勒：卫星收到的频率
        val satRx = orbitalPos.getDownlinkFreq(uplinkHz)
        // ②→③ passband 映射
        val satTx = TransponderMapper.mapUplinkToDownlink(satRx, transponder) ?: return null
        // ③ 加上 offset（offset 在卫星本地频率域）
        // ③→④ 下行多普勒：地面听到的
        return orbitalPos.getDownlinkFreq(satTx + offsetHz)
    }

    /** True if this transponder supports linear passband mapping. */
    fun isLinearTransponder(transponder: SatRadio): Boolean {
        val upLow = transponder.uplinkLow
        val upHigh = transponder.uplinkHigh
        val downLow = transponder.downlinkLow
        val downHigh = transponder.downlinkHigh
        return upLow != null && upHigh != null && downLow != null && downHigh != null
                && upLow != upHigh && downLow != downHigh
    }

    /**
     * True for the radio entry that should drive the standalone Calculator page.
     *
     * A frequency range alone is not enough: some non-user-facing or drifting data entries
     * can also have low/high frequencies. The calculator is meant for the named linear
     * transponder entry, e.g. "Linear Transponder", "Linear Transp.", "SSB Transponder".
     */
    fun isNamedLinearTransponder(transponder: SatRadio): Boolean {
        if (!isLinearTransponder(transponder)) return false

        val info = transponder.info.lowercase(Locale.ENGLISH)
        val modes = listOfNotNull(transponder.downlinkMode, transponder.uplinkMode)
            .joinToString(separator = " ")
            .lowercase(Locale.ENGLISH)
        val hasLinearName = info.contains("linear") || info.contains(" lin") || info.startsWith("lin")
        val hasTransponderName = info.contains("transponder") || info.contains("transp") ||
                info.contains("xponder") || info.contains("xpdr")
        val hasLinearMode = listOf("ssb", "usb", "lsb", "cw").any { modes.contains(it) }

        return (hasLinearName && hasTransponderName) || (hasTransponderName && hasLinearMode) ||
                (hasLinearName && hasLinearMode)
    }

    /**
     * Removes duplicate transponder entries that describe the same physical
     * transponder with different mode labels (e.g. SatNOGS lists AO-7's Mode A
     * as both "Lin SSB" and "Lin CW", and JO-97's U/V transponder as both
     * "CW Transponder" and "SSB Transponder").
     *
     * Entries sharing the same uplink/downlink frequency range are considered
     * the same transponder. The non-CW entry is preferred because its invert
     * flag is more reliable (e.g. JO-97's CW entry wrongly has invert=false).
     */
    fun deduplicateTransponders(radios: List<SatRadio>): List<SatRadio> {
        return radios.groupBy { radio ->
            listOf(radio.uplinkLow, radio.uplinkHigh, radio.downlinkLow, radio.downlinkHigh)
        }.values.map { group ->
            group.firstOrNull { it.downlinkMode?.equals("CW", ignoreCase = true) != true } ?: group.first()
        }
    }
}
