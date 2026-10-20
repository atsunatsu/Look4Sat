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
import com.rtbishop.look4sat.core.domain.model.RadioTcpEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTransportTest {
    @Test fun onlyL4sNativeCdcUsesShortBoundedWriteTimeout() {
        assertEquals(150,usbRadioWriteTimeoutMillis(true,UsbSerialDriverKind.CDC_ACM))
        assertEquals(1000,usbRadioWriteTimeoutMillis(false,UsbSerialDriverKind.CDC_ACM))
        assertEquals(1000,usbRadioWriteTimeoutMillis(true,UsbSerialDriverKind.CP210X))
        assertEquals(1000,usbRadioWriteTimeoutMillis(true,UsbSerialDriverKind.FTDI))
    }
    @Test
    fun nativeCdcDtrOptInNeverAssertsRts() {
        assertEquals(0, cdcControlLineState(false))
        assertEquals(1, cdcControlLineState(true))
        assertEquals(0, cdcControlLineState(true) and 2)
    }

    @Test
    fun tcpEndpointParsesDnsIpv4AndBracketedIpv6() {
        assertEquals(RadioTcpEndpoint("radio.local", 4_532), parseTcpEndpoint("radio.local:4532"))
        assertEquals(RadioTcpEndpoint("192.0.2.10", 12_345), parseTcpEndpoint("192.0.2.10:12345"))
        assertEquals(RadioTcpEndpoint("2001:db8::10", 4_532), parseTcpEndpoint("[2001:db8::10]:4532"))
    }

    @Test
    fun tcpEndpointRejectsMissingOrInvalidPorts() {
        assertNull(parseTcpEndpoint("radio.local"))
        assertNull(parseTcpEndpoint("radio.local:0"))
        assertNull(parseTcpEndpoint("radio.local:65536"))
        assertNull(parseTcpEndpoint("[2001:db8::10]4532"))
        assertNull(parseTcpEndpoint("7:0:1:4292:60016"))
        assertNull(parseTcpEndpoint("2001:db8::10:4532"))
    }

    @Test
    fun usbSelectorSupportsStableVidPidAndLegacyForm() {
        assertEquals(
            UsbSerialSelector(7, 0, 1, 0x10C4, 0xEA70),
            parseUsbSerialSelector("7:0:1:4292:60016")
        )
        assertEquals(
            UsbSerialSelector(7, 0, 1, null, null),
            parseUsbSerialSelector("7:0:1")
        )
        assertNull(parseUsbSerialSelector("7:0"))
        assertNull(parseUsbSerialSelector("7:0:1:bad:60016"))
    }

    @Test
    fun targetedUsbBridgeVendorsResolveToInternalDrivers() {
        assertEquals(UsbSerialDriverKind.CP210X, usbSerialDriverForVendor(0x10C4))
        assertEquals(UsbSerialDriverKind.FTDI, usbSerialDriverForVendor(0x0403))
        assertEquals(UsbSerialDriverKind.CH34X, usbSerialDriverForVendor(0x1A86))
        assertNull(usbSerialDriverForVendor(0xFFFF))
    }

    @Test
    fun supportedRadioModelsResolveToTheirProtocolVariants() {
        val ftx1=radioProfile(RadioControlSettings.MODEL_YAESU_FTX1)
        assertTrue(ftx1.isFtx1)
        assertNull(ftx1.yaesuVariant)
        assertFalse(ftx1.isIcom)
        assertEquals(1,ftx1.serialStopBits)
        assertEquals(
            YaesuCatVariant.FT817,
            radioProfile(RadioControlSettings.MODEL_YAESU_FT817).yaesuVariant
        )
        assertEquals(2, radioProfile(RadioControlSettings.MODEL_YAESU_FT817).serialStopBits)
        assertEquals(
            YaesuCatVariant.FT857,
            radioProfile(RadioControlSettings.MODEL_YAESU_FT857).yaesuVariant
        )
        assertEquals(2, radioProfile(RadioControlSettings.MODEL_YAESU_FT857).serialStopBits)
        assertEquals(
            IcomCivVariant.IC705,
            radioProfile(RadioControlSettings.MODEL_ICOM_IC705).icomVariant
        )
        assertEquals(
            IcomCivProtocol.ADDR_IC9700,
            radioProfile(RadioControlSettings.MODEL_ICOM_IC9700).civAddress
        )
        val ic910 = radioProfile(RadioControlSettings.MODEL_ICOM_IC910)
        val ic820 = radioProfile(RadioControlSettings.MODEL_ICOM_IC820)
        assertEquals(IcomCivVariant.IC820, ic820.icomVariant)
        assertEquals(0x42.toByte(), ic820.civAddress)
        assertEquals(1, ic820.serialStopBits)
        assertFalse(ic820.capabilities.ptt)
        assertEquals(0x43.toByte(), radioProfile(RadioControlSettings.MODEL_ICOM_IC820, 0x43).civAddress)
        assertEquals(IcomCivVariant.IC910, ic910.icomVariant)
        assertEquals(IcomCivProtocol.ADDR_IC910, ic910.civAddress)
        assertEquals(true, ic910.supportsSatelliteMode)
        assertEquals(false, radioProfile(RadioControlSettings.MODEL_ICOM_IC705).supportsSatelliteMode)
        assertEquals(1, radioProfile(RadioControlSettings.MODEL_ICOM_IC705).serialStopBits)
        assertEquals(1, radioProfile(RadioControlSettings.MODEL_ICOM_IC9700).serialStopBits)
        assertEquals(1, ic910.serialStopBits)
        assertEquals(0xAA.toByte(), radioProfile(RadioControlSettings.MODEL_ICOM_IC705, 0xAA).civAddress)
        assertEquals(false, radioProfile(RadioControlSettings.MODEL_YAESU_FT857)
            .canSetTxFrequencyWhileTransmitting)
        assertEquals(true, radioProfile(RadioControlSettings.MODEL_ICOM_IC9700).capabilities.fullDuplex)
        assertEquals(
            RadioControlSettings.MODEL_YAESU_FT817,
            radioProfile("Unknown radio").model
        )
    }

    @Test
    fun usbSerialLineConfigurationEncodes8n1And8n2ForEveryDriver() {
        assertEquals(
            UsbSerialLineConfiguration(0, 0x0800, 0x0008, 0xC3),
            usbSerialLineConfiguration(1)
        )
        assertEquals(
            UsbSerialLineConfiguration(2, 0x0802, 0x1008, 0xC7),
            usbSerialLineConfiguration(2)
        )
        assertNull(usbSerialLineConfiguration(0))
        assertNull(usbSerialLineConfiguration(3))
    }

    @Test
    fun restrictedCp2105SecondPortRejectsTwoStopBits() {
        val eightN1 = requireNotNull(usbSerialLineConfiguration(1))
        val eightN2 = requireNotNull(usbSerialLineConfiguration(2))

        assertEquals(true, isCp210xLineConfigurationSupported(2, 1, eightN1))
        assertEquals(false, isCp210xLineConfigurationSupported(2, 1, eightN2))
        assertEquals(true, isCp210xLineConfigurationSupported(2, 0, eightN2))
        assertEquals(true, isCp210xLineConfigurationSupported(4, 1, eightN2))
    }

    @Test
    fun ftdiAndCh34xBaudConfigurationsAreDefinedForCatRates() {
        listOf(4_800, 9_600, 19_200, 38_400, 115_200).forEach { rate ->
            assertNotNull("FTDI divisor missing for $rate", calculateFtdiBaudDivisor(rate, 0, true))
            assertNotNull("CH34x registers missing for $rate", calculateCh34xBaudRegisters(rate))
        }
        assertNull(calculateFtdiBaudDivisor(0, 0, true))
        assertNull(calculateCh34xBaudRegisters(0))
    }

    @Test
    fun ftdiReadStatusBytesAreRemovedFromEveryUsbPacket() {
        val packets = byteArrayOf(
            0x10, 0x20, 1, 2, 3, 4, 5, 6,
            0x30, 0x40, 7, 8, 9, 10
        )
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            filterFtdiStatusBytes(packets, packets.size, maxPacketSize = 8)
        )
    }

    @Test
    fun ftdiReadBufferIsPacketAlignedAndCappedToUsbTransferLimit() {
        assertEquals(64, ftdiReadBufferSize(maxBytes = 1, maxPacketSize = 64))
        assertEquals(128, ftdiReadBufferSize(maxBytes = 63, maxPacketSize = 64))
        assertEquals(16_384, ftdiReadBufferSize(maxBytes = Int.MAX_VALUE, maxPacketSize = 64))
        assertEquals(0, ftdiReadBufferSize(maxBytes = 0, maxPacketSize = 64))
        assertEquals(0, ftdiReadBufferSize(maxBytes = 1, maxPacketSize = 2))
    }

    @Test
    fun oversizedUsbReadIsSplitWithoutDiscardingRemainingBytes() {
        val (first, remaining) = splitReadChunk(byteArrayOf(1, 2, 3, 4, 5), maxBytes = 2)

        assertArrayEquals(byteArrayOf(1, 2), first)
        assertArrayEquals(byteArrayOf(3, 4, 5), remaining)
        val (second, empty) = splitReadChunk(remaining, maxBytes = 8)
        assertArrayEquals(byteArrayOf(3, 4, 5), second)
        assertArrayEquals(ByteArray(0), empty)
    }
}
