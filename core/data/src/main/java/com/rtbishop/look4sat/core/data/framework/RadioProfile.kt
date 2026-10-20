/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.RadioCapabilities
import com.rtbishop.look4sat.core.domain.model.radioModelDescriptor

enum class YaesuCatVariant {
    FT817,
    FT857
}

enum class IcomCivVariant {
    IC705,
    IC9700,
    IC910,
    IC820
}

data class RadioProfile(
    val model: String,
    val yaesuVariant: YaesuCatVariant? = null,
    val icomVariant: IcomCivVariant? = null,
    val civAddress: Byte? = null,
    val serialStopBits: Int = 1,
    val capabilities: RadioCapabilities,
    val isL4s: Boolean = false,
    val isFtx1: Boolean = false,
    val isN76: Boolean = false
) {
    val isIcom: Boolean get() = icomVariant != null
    val supportsSatelliteMode: Boolean
        get() = capabilities.satelliteMode
    val canSetTxFrequencyWhileTransmitting: Boolean
        get() = capabilities.canSetTxFrequencyWhileTransmitting
}

fun radioProfile(model: String, civAddressOverride: Int? = null): RadioProfile {
    val descriptor = radioModelDescriptor(model)
    val profile = when (descriptor.model) {
        RadioControlSettings.MODEL_VERO_N76 -> RadioProfile(
            model = descriptor.model, capabilities = descriptor.capabilities, isN76 = true
        )
        RadioControlSettings.MODEL_YAESU_FTX1 -> RadioProfile(
            model=descriptor.model,capabilities=descriptor.capabilities,
            serialStopBits=descriptor.serialStopBits,isFtx1=true
        )
        RadioControlSettings.MODEL_YAESU_FT857 -> RadioProfile(
            model = descriptor.model,
            yaesuVariant = YaesuCatVariant.FT857,
            serialStopBits = descriptor.serialStopBits,
            capabilities = descriptor.capabilities
        )
        RadioControlSettings.MODEL_ICOM_IC705 -> RadioProfile(
            model = descriptor.model,
            icomVariant = IcomCivVariant.IC705,
            civAddress = descriptor.defaultCivAddress?.toByte(),
            serialStopBits = descriptor.serialStopBits,
            capabilities = descriptor.capabilities
        )
        RadioControlSettings.MODEL_ICOM_IC9700 -> RadioProfile(
            model = descriptor.model,
            icomVariant = IcomCivVariant.IC9700,
            civAddress = descriptor.defaultCivAddress?.toByte(),
            serialStopBits = descriptor.serialStopBits,
            capabilities = descriptor.capabilities
        )
        RadioControlSettings.MODEL_ICOM_IC910 -> RadioProfile(
            model = descriptor.model,
            icomVariant = IcomCivVariant.IC910,
            civAddress = descriptor.defaultCivAddress?.toByte(),
            serialStopBits = descriptor.serialStopBits,
            capabilities = descriptor.capabilities
        )
        RadioControlSettings.MODEL_ICOM_IC820 -> RadioProfile(
            model = descriptor.model,
            icomVariant = IcomCivVariant.IC820,
            civAddress = descriptor.defaultCivAddress?.toByte(),
            serialStopBits = descriptor.serialStopBits,
            capabilities = descriptor.capabilities
        )
        RadioControlSettings.MODEL_QUANSHENG_UVK1,
        RadioControlSettings.MODEL_QUANSHENG_UVK5V3 -> RadioProfile(
            model = descriptor.model,
            capabilities = descriptor.capabilities,
            serialStopBits = descriptor.serialStopBits,
            isL4s = true
        )
        else -> RadioProfile(
            model = RadioControlSettings.MODEL_YAESU_FT817,
            yaesuVariant = YaesuCatVariant.FT817,
            serialStopBits = descriptor.serialStopBits,
            capabilities = descriptor.capabilities
        )
    }
    return if (profile.isIcom && civAddressOverride in 0..0xFF) {
        profile.copy(civAddress = civAddressOverride?.toByte())
    } else {
        profile
    }
}
