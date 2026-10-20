/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.model

enum class RadioProtocolFamily {
    YAESU_CAT,
    YAESU_ASCII_CAT,
    ICOM_CIV,
    L4S,
    VERO_GAIA
}

enum class RadioBand {
    HF,
    SIX_METERS,
    TWO_METERS,
    SEVENTY_CENTIMETERS,
    TWENTY_THREE_CENTIMETERS
}

data class RadioCapabilities(
    val fullDuplex: Boolean,
    val receiveOnly: Boolean,
    val frequencyReadback: Boolean,
    val singleRadioSplit: Boolean,
    val satelliteMode: Boolean,
    val dataMode: Boolean,
    val ptt: Boolean,
    val narrowFm: Boolean,
    val canSetTxFrequencyWhileTransmitting: Boolean,
    val bands: Set<RadioBand>
)

data class RadioModelDescriptor(
    val model: String,
    val protocolFamily: RadioProtocolFamily,
    val capabilities: RadioCapabilities,
    val baudRates: List<Int>,
    val serialStopBits: Int,
    val defaultCivAddress: Int? = null
)

object RadioModelCatalog {
    private val hfVhfUhfBands = setOf(
        RadioBand.HF,
        RadioBand.SIX_METERS,
        RadioBand.TWO_METERS,
        RadioBand.SEVENTY_CENTIMETERS
    )
    private val satelliteBands = setOf(
        RadioBand.TWO_METERS,
        RadioBand.SEVENTY_CENTIMETERS,
        RadioBand.TWENTY_THREE_CENTIMETERS
    )

    private val descriptors = listOf(
        RadioModelDescriptor(
            model = RadioControlSettings.MODEL_YAESU_FT817,
            protocolFamily = RadioProtocolFamily.YAESU_CAT,
            capabilities = RadioCapabilities(
                fullDuplex = false,
                receiveOnly = false,
                frequencyReadback = true,
                singleRadioSplit = false,
                satelliteMode = false,
                dataMode = true,
                ptt = true,
                narrowFm = true,
                canSetTxFrequencyWhileTransmitting = true,
                bands = hfVhfUhfBands
            ),
            baudRates = listOf(4_800, 9_600, 38_400),
            serialStopBits = 2
        ),
        RadioModelDescriptor(
            model = RadioControlSettings.MODEL_YAESU_FT857,
            protocolFamily = RadioProtocolFamily.YAESU_CAT,
            capabilities = RadioCapabilities(
                fullDuplex = false,
                receiveOnly = false,
                frequencyReadback = true,
                singleRadioSplit = false,
                satelliteMode = false,
                dataMode = true,
                ptt = true,
                narrowFm = true,
                canSetTxFrequencyWhileTransmitting = false,
                bands = hfVhfUhfBands
            ),
            baudRates = listOf(4_800, 9_600, 38_400),
            serialStopBits = 2
        ),
        RadioModelDescriptor(
            model=RadioControlSettings.MODEL_YAESU_FTX1,protocolFamily=RadioProtocolFamily.YAESU_ASCII_CAT,
            capabilities=RadioCapabilities(fullDuplex=false,receiveOnly=false,frequencyReadback=true,
                singleRadioSplit=true,satelliteMode=false,dataMode=true,ptt=true,narrowFm=true,
                canSetTxFrequencyWhileTransmitting=false,bands=hfVhfUhfBands),
            baudRates=listOf(38_400,4_800,9_600,19_200,57_600,115_200),serialStopBits=1
        ),
        RadioModelDescriptor(
            model = RadioControlSettings.MODEL_ICOM_IC705,
            protocolFamily = RadioProtocolFamily.ICOM_CIV,
            capabilities = RadioCapabilities(
                fullDuplex = false,
                receiveOnly = false,
                frequencyReadback = true,
                singleRadioSplit = true,
                satelliteMode = false,
                dataMode = true,
                ptt = true,
                narrowFm = true,
                canSetTxFrequencyWhileTransmitting = true,
                bands = hfVhfUhfBands
            ),
            baudRates = listOf(4_800, 9_600, 19_200),
            serialStopBits = 1,
            defaultCivAddress = 0xA4
        ),
        RadioModelDescriptor(
            model = RadioControlSettings.MODEL_ICOM_IC9700,
            protocolFamily = RadioProtocolFamily.ICOM_CIV,
            capabilities = RadioCapabilities(
                fullDuplex = true,
                receiveOnly = false,
                frequencyReadback = true,
                singleRadioSplit = true,
                satelliteMode = true,
                dataMode = true,
                ptt = true,
                narrowFm = true,
                canSetTxFrequencyWhileTransmitting = true,
                bands = satelliteBands
            ),
            baudRates = listOf(4_800, 9_600, 19_200, 38_400, 57_600, 115_200),
            serialStopBits = 1,
            defaultCivAddress = 0xA2
        ),
        RadioModelDescriptor(
            model = RadioControlSettings.MODEL_ICOM_IC910,
            protocolFamily = RadioProtocolFamily.ICOM_CIV,
            capabilities = RadioCapabilities(
                fullDuplex = true,
                receiveOnly = false,
                frequencyReadback = true,
                singleRadioSplit = true,
                satelliteMode = true,
                dataMode = false,
                ptt = true,
                narrowFm = false,
                canSetTxFrequencyWhileTransmitting = true,
                bands = satelliteBands
            ),
            baudRates = listOf(9_600, 19_200, 4_800, 1_200, 300),
            serialStopBits = 1,
            defaultCivAddress = 0x60
        ),
        RadioModelDescriptor(
            model = RadioControlSettings.MODEL_ICOM_IC820,
            protocolFamily = RadioProtocolFamily.ICOM_CIV,
            capabilities = RadioCapabilities(
                fullDuplex = true,
                receiveOnly = false,
                frequencyReadback = true,
                // A/B split is same-band; cross-band SATL requires manual radio setup.
                singleRadioSplit = false,
                satelliteMode = false,
                dataMode = false,
                ptt = false,
                narrowFm = false,
                canSetTxFrequencyWhileTransmitting = false,
                bands = setOf(RadioBand.TWO_METERS, RadioBand.SEVENTY_CENTIMETERS)
            ),
            baudRates = listOf(1_200, 300, 4_800, 9_600),
            serialStopBits = 1,
            defaultCivAddress = 0x42
        )
    ) + listOf(RadioControlSettings.MODEL_QUANSHENG_UVK1, RadioControlSettings.MODEL_QUANSHENG_UVK5V3).map { model ->
        RadioModelDescriptor(
            model, RadioProtocolFamily.L4S,
            RadioCapabilities(
                fullDuplex = false, receiveOnly = false, frequencyReadback = true,
                singleRadioSplit = true, satelliteMode = false, dataMode = false,
                ptt = true, narrowFm = true, canSetTxFrequencyWhileTransmitting = false,
                bands = setOf(RadioBand.TWO_METERS, RadioBand.SEVENTY_CENTIMETERS)
            ),
            baudRates = listOf(38_400), serialStopBits = 1
        )
    }
    private val n76 = RadioModelDescriptor(
        model = RadioControlSettings.MODEL_VERO_N76,
        protocolFamily = RadioProtocolFamily.VERO_GAIA,
        capabilities = RadioCapabilities(
            fullDuplex = false, receiveOnly = false, frequencyReadback = true,
            singleRadioSplit = true, satelliteMode = false, dataMode = false,
            ptt = true, narrowFm = false, canSetTxFrequencyWhileTransmitting = false,
            bands = setOf(RadioBand.TWO_METERS, RadioBand.SEVENTY_CENTIMETERS)
        ),
        baudRates = listOf(9_600), serialStopBits = 1
    )
    private val byModel = (descriptors + n76).associateBy(RadioModelDescriptor::model)

    val supportedModels: List<String> = (descriptors + n76).map(RadioModelDescriptor::model)

    val bluetoothModels: List<String> = listOf(
        RadioControlSettings.MODEL_YAESU_FT817, RadioControlSettings.MODEL_YAESU_FT857,
        RadioControlSettings.MODEL_ICOM_IC705, RadioControlSettings.MODEL_ICOM_IC9700,
        RadioControlSettings.MODEL_VERO_N76
    )

    fun modelsForTransport(transport: String): List<String> =
        when (transport) {
            RadioControlSettings.TRANSPORT_BLUETOOTH -> bluetoothModels
            RadioControlSettings.TRANSPORT_USB -> supportedModels.filterNot { it == n76.model }
            else -> supportedModels.filterNot { it == RadioControlSettings.MODEL_YAESU_FTX1 || it == n76.model }
        }

    fun resolve(model: String): RadioModelDescriptor =
        byModel[model] ?: checkNotNull(byModel[RadioControlSettings.MODEL_YAESU_FT817])
}

fun radioModelDescriptor(model: String): RadioModelDescriptor = RadioModelCatalog.resolve(model)
