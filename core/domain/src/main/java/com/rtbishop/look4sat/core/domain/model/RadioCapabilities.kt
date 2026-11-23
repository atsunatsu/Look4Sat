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
    VERO_GAIA,
    RIGCTLD,
    KENWOOD_CAT,
    FLEX_SMARTSDR
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

    private fun icomSingleLeg(
        model: String, address: Int, baudRates: List<Int>, bands: Set<RadioBand>, data: Boolean, ptt: Boolean
    ) = RadioModelDescriptor(model, RadioProtocolFamily.ICOM_CIV,
        RadioCapabilities(false, false, true, false, false, data, ptt, false, false, bands),
        baudRates, 1, address)

    private fun kenwood(
        model: String, baudRates: List<Int>, bands: Set<RadioBand>,
        data: Boolean = false, satellite: Boolean = false, stopBits: Int = 1
    ) = RadioModelDescriptor(model, RadioProtocolFamily.KENWOOD_CAT,
        RadioCapabilities(satellite, false, true, satellite, satellite, data, false, false, false, bands),
        baudRates, stopBits)

    private val kenwoodModels = listOf(
        kenwood(RadioControlSettings.MODEL_KENWOOD_TH_D74, listOf(9_600), hfVhfUhfBands),
        kenwood(RadioControlSettings.MODEL_KENWOOD_TH_D75, listOf(9_600), hfVhfUhfBands),
        kenwood(RadioControlSettings.MODEL_KENWOOD_TS_2000, listOf(9_600, 4_800, 19_200, 57_600),
            hfVhfUhfBands + RadioBand.TWENTY_THREE_CENTIMETERS, satellite = true),
        kenwood(RadioControlSettings.MODEL_KENWOOD_TS_790, listOf(4_800), satelliteBands, satellite = true, stopBits = 2),
        kenwood(RadioControlSettings.MODEL_KENWOOD_TS_990, listOf(115_200, 57_600, 38_400, 19_200, 9_600), hfVhfUhfBands, data = true),
        kenwood(RadioControlSettings.MODEL_KENWOOD_TS_890, listOf(115_200, 57_600, 38_400, 19_200, 9_600), hfVhfUhfBands, data = true),
        kenwood(RadioControlSettings.MODEL_KENWOOD_TS_590, listOf(115_200, 57_600, 38_400, 19_200, 9_600), hfVhfUhfBands, data = true),
        kenwood(RadioControlSettings.MODEL_KENWOOD_TS_590SG, listOf(115_200, 57_600, 38_400, 19_200, 9_600), hfVhfUhfBands, data = true)
    )

    private val descriptors = listOf(
        RadioModelDescriptor(RadioControlSettings.MODEL_YAESU_FT847, RadioProtocolFamily.YAESU_CAT,
            RadioCapabilities(true, false, true, true, true, false, true, true, false, hfVhfUhfBands),
            listOf(4_800, 9_600, 57_600), 2),
        RadioModelDescriptor(RadioControlSettings.MODEL_YAESU_FT991, RadioProtocolFamily.YAESU_ASCII_CAT,
            RadioCapabilities(false, false, true, false, false, true, true, true, false, hfVhfUhfBands),
            listOf(38_400, 4_800, 9_600, 19_200), 2),
        RadioModelDescriptor(RadioControlSettings.MODEL_ICOM_IC9100, RadioProtocolFamily.ICOM_CIV,
            RadioCapabilities(true, false, true, true, true, true, true, false, true,
                hfVhfUhfBands + RadioBand.TWENTY_THREE_CENTIMETERS),
            listOf(19_200, 9_600, 4_800, 1_200, 300), 1, 0x7C),
        RadioModelDescriptor(RadioControlSettings.MODEL_ICOM_IC821, RadioProtocolFamily.ICOM_CIV,
            RadioCapabilities(true, false, true, true, false, false, false, false, false,
                setOf(RadioBand.TWO_METERS, RadioBand.SEVENTY_CENTIMETERS)),
            listOf(19_200, 9_600, 4_800, 1_200, 300), 1, 0x4C),
        icomSingleLeg(RadioControlSettings.MODEL_ICOM_IC7300, 0x94, listOf(115_200, 19_200, 9_600, 4_800),
            setOf(RadioBand.HF, RadioBand.SIX_METERS), data = true, ptt = true),
        icomSingleLeg(RadioControlSettings.MODEL_ICOM_IC905, 0xAC, listOf(115_200, 19_200, 9_600, 4_800),
            satelliteBands, data = true, ptt = true),
        icomSingleLeg(RadioControlSettings.MODEL_ICOM_IC7100, 0x88, listOf(19_200, 9_600, 4_800),
            hfVhfUhfBands, data = true, ptt = true),
        icomSingleLeg(RadioControlSettings.MODEL_ICOM_IC706, 0x48, listOf(19_200, 9_600, 4_800, 1_200, 300),
            hfVhfUhfBands - RadioBand.SEVENTY_CENTIMETERS, data = false, ptt = false),
        icomSingleLeg(RadioControlSettings.MODEL_ICOM_IC706MKII, 0x4C, listOf(19_200, 9_600, 4_800, 1_200, 300),
            hfVhfUhfBands - RadioBand.SEVENTY_CENTIMETERS, data = false, ptt = false),
        icomSingleLeg(RadioControlSettings.MODEL_ICOM_IC706MKIIG, 0x58, listOf(19_200, 9_600, 4_800, 1_200, 300),
            hfVhfUhfBands, data = false, ptt = false),
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
    private val sdr = RadioModelDescriptor(
        RadioControlSettings.MODEL_SDR_RIGCTLD, RadioProtocolFamily.RIGCTLD,
        RadioCapabilities(false, true, true, false, false, false, false, false, false, emptySet()),
        listOf(9_600), 1
    )
    private val flex = RadioModelDescriptor(
        RadioControlSettings.MODEL_FLEX_SMARTSDR, RadioProtocolFamily.FLEX_SMARTSDR,
        RadioCapabilities(true, false, true, true, true, true, false, true, false, hfVhfUhfBands),
        listOf(9_600), 1
    )
    private val byModel = (descriptors + n76 + kenwoodModels + sdr + flex).associateBy(RadioModelDescriptor::model)

    val supportedModels: List<String> = (descriptors + n76 + kenwoodModels + flex).map(RadioModelDescriptor::model)

    val bluetoothModels: List<String> = listOf(
        RadioControlSettings.MODEL_YAESU_FT817, RadioControlSettings.MODEL_YAESU_FT857,
        RadioControlSettings.MODEL_YAESU_FTX1,
        RadioControlSettings.MODEL_ICOM_IC705, RadioControlSettings.MODEL_QUANSHENG_UVK1,
        RadioControlSettings.MODEL_QUANSHENG_UVK5V3, RadioControlSettings.MODEL_ICOM_IC9700,
        RadioControlSettings.MODEL_VERO_N76
    )

    fun modelsForTransport(transport: String, receive: Boolean = false): List<String> =
        when (transport) {
            RadioControlSettings.TRANSPORT_BLUETOOTH -> bluetoothModels
            RadioControlSettings.TRANSPORT_USB -> supportedModels.filterNot { it == n76.model || it == flex.model }
            else -> supportedModels.filterNot { it == RadioControlSettings.MODEL_YAESU_FTX1 || it == n76.model }
        }

    fun resolve(model: String): RadioModelDescriptor =
        byModel[model] ?: checkNotNull(byModel[RadioControlSettings.MODEL_YAESU_FT817])
}

fun radioModelDescriptor(model: String): RadioModelDescriptor = RadioModelCatalog.resolve(model)
