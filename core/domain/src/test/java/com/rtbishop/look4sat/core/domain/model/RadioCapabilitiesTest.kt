package com.rtbishop.look4sat.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioCapabilitiesTest {

    @Test fun bluetoothPickerPreservesLegacyAdaptersWithoutOfferingUnsupportedL4sOrFtx1Transport() {
        assertEquals(listOf(RadioControlSettings.MODEL_YAESU_FT817,RadioControlSettings.MODEL_YAESU_FT857,
            RadioControlSettings.MODEL_ICOM_IC705,RadioControlSettings.MODEL_ICOM_IC9700,RadioControlSettings.MODEL_VERO_N76),
            RadioModelCatalog.modelsForTransport(RadioControlSettings.TRANSPORT_BLUETOOTH))
        assertTrue(RadioControlSettings.MODEL_YAESU_FTX1 in RadioModelCatalog.modelsForTransport(RadioControlSettings.TRANSPORT_USB))
        assertFalse(RadioControlSettings.MODEL_YAESU_FTX1 in RadioModelCatalog.modelsForTransport(RadioControlSettings.TRANSPORT_TCP))
        val ftx1=radioModelDescriptor(RadioControlSettings.MODEL_YAESU_FTX1)
        assertEquals(RadioProtocolFamily.YAESU_ASCII_CAT,ftx1.protocolFamily)
        assertEquals(1,ftx1.serialStopBits)
        assertTrue(ftx1.capabilities.singleRadioSplit)
        assertFalse(ftx1.capabilities.fullDuplex)
    }

    @Test
    fun catalogDefinesEverySupportedModelExactlyOnce() {
        assertEquals(
            listOf(
                RadioControlSettings.MODEL_YAESU_FT817,
                RadioControlSettings.MODEL_YAESU_FT857,
                RadioControlSettings.MODEL_YAESU_FTX1,
                RadioControlSettings.MODEL_ICOM_IC705,
                RadioControlSettings.MODEL_ICOM_IC9700,
                RadioControlSettings.MODEL_ICOM_IC910,
                RadioControlSettings.MODEL_ICOM_IC820,
                RadioControlSettings.MODEL_QUANSHENG_UVK1,
                RadioControlSettings.MODEL_QUANSHENG_UVK5V3,
                RadioControlSettings.MODEL_VERO_N76
            ),
            RadioModelCatalog.supportedModels
        )
        assertEquals(RadioModelCatalog.supportedModels, RadioControlSettings.SUPPORTED_RADIOS)
    }

    @Test
    fun capabilitiesDescribeProtocolAndDuplexBoundaries() {
        val ft817 = radioModelDescriptor(RadioControlSettings.MODEL_YAESU_FT817)
        assertEquals(RadioProtocolFamily.YAESU_CAT, ft817.protocolFamily)
        assertFalse(ft817.capabilities.fullDuplex)
        assertFalse(ft817.capabilities.singleRadioSplit)
        assertTrue(ft817.capabilities.dataMode)
        assertTrue(ft817.capabilities.narrowFm)
        assertNull(ft817.defaultCivAddress)

        val ic705 = radioModelDescriptor(RadioControlSettings.MODEL_ICOM_IC705)
        assertEquals(RadioProtocolFamily.ICOM_CIV, ic705.protocolFamily)
        assertTrue(ic705.capabilities.singleRadioSplit)
        assertFalse(ic705.capabilities.fullDuplex)
        assertFalse(ic705.capabilities.satelliteMode)
        assertEquals(0xA4, ic705.defaultCivAddress)

        val ic9700 = radioModelDescriptor(RadioControlSettings.MODEL_ICOM_IC9700)
        assertTrue(ic9700.capabilities.fullDuplex)
        assertTrue(ic9700.capabilities.satelliteMode)
        assertTrue(ic9700.capabilities.dataMode)
        assertTrue(RadioBand.TWENTY_THREE_CENTIMETERS in ic9700.capabilities.bands)

        val ic910 = radioModelDescriptor(RadioControlSettings.MODEL_ICOM_IC910)
        assertTrue(ic910.capabilities.fullDuplex)
        assertTrue(ic910.capabilities.satelliteMode)
        assertFalse(ic910.capabilities.dataMode)
        assertFalse(ic910.capabilities.narrowFm)
    }

    @Test
    fun ic820UsesDocumentedDefaultsAndConservativeCatCapabilities() {
        val radio = radioModelDescriptor(RadioControlSettings.MODEL_ICOM_IC820)
        assertEquals(0x42, radio.defaultCivAddress)
        assertEquals(listOf(1_200, 300, 4_800, 9_600), radio.baudRates)
        assertEquals(1, radio.serialStopBits)
        assertTrue(radio.capabilities.fullDuplex)
        assertTrue(radio.capabilities.frequencyReadback)
        assertFalse(radio.capabilities.singleRadioSplit)
        assertFalse(radio.capabilities.satelliteMode)
        assertFalse(radio.capabilities.ptt)
        assertFalse(radio.capabilities.dataMode)
        assertFalse(radio.capabilities.narrowFm)
        assertFalse(radio.capabilities.canSetTxFrequencyWhileTransmitting)
        assertEquals(setOf(RadioBand.TWO_METERS, RadioBand.SEVENTY_CENTIMETERS), radio.capabilities.bands)
    }

    @Test
    fun n76UsesBluetoothNativeSatelliteVfoAndIndependentPttWithoutClaimingFullDuplex() {
        val radio = radioModelDescriptor(RadioControlSettings.MODEL_VERO_N76)
        assertEquals(RadioProtocolFamily.VERO_GAIA, radio.protocolFamily)
        assertTrue(radio.capabilities.singleRadioSplit)
        assertTrue(radio.capabilities.frequencyReadback)
        assertFalse(radio.capabilities.fullDuplex)
        assertTrue(radio.capabilities.ptt)
        assertFalse(radio.capabilities.dataMode)
        assertFalse(radio.capabilities.canSetTxFrequencyWhileTransmitting)
        assertFalse(radio.model in RadioModelCatalog.modelsForTransport(RadioControlSettings.TRANSPORT_USB))
        assertFalse(radio.model in RadioModelCatalog.modelsForTransport(RadioControlSettings.TRANSPORT_TCP))
    }

    @Test
    fun unknownModelsUseTheLegacyFt817Fallback() {
        assertEquals(
            radioModelDescriptor(RadioControlSettings.MODEL_YAESU_FT817),
            radioModelDescriptor("Unknown radio")
        )
    }
}
