/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.N76ControlOptions
import com.rtbishop.look4sat.core.domain.model.N76FirmwareMode
import com.rtbishop.look4sat.core.domain.model.N76Power
import org.json.JSONObject

internal fun N76ControlOptions.toPreferenceJson(): String = JSONObject().apply {
    put("firmware", firmwareMode.name); put("satelliteInfo", sendSatelliteInfo); put("poll", pollMillis)
    put("power", power.name); put("squelch", openSquelchOnTrack)
    put("forceRx", forceRxCtcss); put("rxTone", rxCtcssHz ?: JSONObject.NULL)
    put("forceTx", forceTxCtcss); put("txTone", txCtcssHz ?: JSONObject.NULL)
    put("speaker", speakerMonitor); put("recordRadio", recordRadio); put("recordMic", recordMicrophone)
    put("recordOnTrack", recordOnTrack); put("folder", outputFolderUri)
    put("input", inputDeviceKey ?: JSONObject.NULL); put("gain", micGainDb)
}.toString()

internal fun n76OptionsFromJson(text: String?): N76ControlOptions = runCatching {
    val json = JSONObject(text ?: "{}")
    N76ControlOptions(
        firmwareMode = N76FirmwareMode.entries.firstOrNull { it.name == json.optString("firmware") } ?: N76FirmwareMode.AUTO,
        sendSatelliteInfo = json.optBoolean("satelliteInfo", true), pollMillis = json.optLong("poll", 1_000),
        power = N76Power.entries.firstOrNull { it.name == json.optString("power") } ?: N76Power.UNCHANGED,
        openSquelchOnTrack = json.optBoolean("squelch", false),
        forceRxCtcss = json.optBoolean("forceRx", false), rxCtcssHz = json.optDouble("rxTone", Double.NaN).takeIf { it.isFinite() },
        forceTxCtcss = json.optBoolean("forceTx", false), txCtcssHz = json.optDouble("txTone", Double.NaN).takeIf { it.isFinite() },
        speakerMonitor = json.optBoolean("speaker", true), recordRadio = json.optBoolean("recordRadio", false),
        recordMicrophone = json.optBoolean("recordMic", false), recordOnTrack = json.optBoolean("recordOnTrack", false),
        outputFolderUri = json.optString("folder", ""), inputDeviceKey = json.optString("input", "").takeIf { it.isNotBlank() && it != "null" },
        micGainDb = json.optInt("gain", 0)
    ).normalized()
}.getOrDefault(N76ControlOptions())
