/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.core.domain.model

enum class N76FirmwareMode { AUTO, SATELLITE, EXACT }
enum class N76Power { UNCHANGED, LOW, MEDIUM, HIGH }

data class N76ControlOptions(
    val firmwareMode: N76FirmwareMode = N76FirmwareMode.AUTO,
    val sendSatelliteInfo: Boolean = true,
    val pollMillis: Long = 1_000,
    val power: N76Power = N76Power.UNCHANGED,
    val openSquelchOnTrack: Boolean = false,
    val forceRxCtcss: Boolean = false,
    val rxCtcssHz: Double? = null,
    val forceTxCtcss: Boolean = false,
    val txCtcssHz: Double? = null,
    val speakerMonitor: Boolean = true,
    val recordRadio: Boolean = false,
    val recordMicrophone: Boolean = false,
    val recordOnTrack: Boolean = false,
    val outputFolderUri: String = "",
    val inputDeviceKey: String? = null,
    val micGainDb: Int = 0
) {
    fun normalized(): N76ControlOptions = copy(
        pollMillis = pollMillis.coerceIn(250, 15_000) / 250 * 250,
        rxCtcssHz = rxCtcssHz?.takeIf { it.isFinite() && it in 60.0..260.0 },
        txCtcssHz = txCtcssHz?.takeIf { it.isFinite() && it in 60.0..260.0 },
        micGainDb = micGainDb.coerceIn(0, 24) / 6 * 6
    )
    fun satelliteFirmware(version: Int): Boolean = when (firmwareMode) {
        N76FirmwareMode.AUTO -> version >= 137
        N76FirmwareMode.SATELLITE -> true
        N76FirmwareMode.EXACT -> false
    }
}

enum class RadioRecordingAction { START, STOP, PLAY_LAST, STOP_PLAYBACK }
