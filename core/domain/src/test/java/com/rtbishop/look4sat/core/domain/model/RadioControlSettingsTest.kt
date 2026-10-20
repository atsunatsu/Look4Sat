package com.rtbishop.look4sat.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioControlSettingsTest {

    @Test
    fun parsesHostNamesIpv4AndBracketedIpv6() {
        assertEquals(RadioTcpEndpoint("radio.local", 4532), parseRadioTcpEndpoint("radio.local:4532"))
        assertEquals(RadioTcpEndpoint("192.0.2.10", 60000), parseRadioTcpEndpoint("192.0.2.10:60000"))
        assertEquals(RadioTcpEndpoint("2001:db8::10", 4532), parseRadioTcpEndpoint("[2001:db8::10]:4532"))
    }

    @Test
    fun rejectsMalformedAndLegacyUsbSelectors() {
        assertNull(parseRadioTcpEndpoint(""))
        assertNull(parseRadioTcpEndpoint("radio.local"))
        assertNull(parseRadioTcpEndpoint("radio.local:0"))
        assertNull(parseRadioTcpEndpoint("radio.local:65536"))
        assertNull(parseRadioTcpEndpoint("7:0:1:4292:60016"))
        assertNull(parseRadioTcpEndpoint("2001:db8::10:4532"))
        assertNull(parseRadioTcpEndpoint("[2001:db8::10]4532"))
    }

    @Test
    fun exposesOnlyDocumentedBaudRatesForEachRadioFamily() {
        assertEquals(listOf(4800, 9600, 38400), supportedRadioBaudRates(RadioControlSettings.MODEL_YAESU_FT817))
        assertEquals(listOf(4800, 9600, 38400), supportedRadioBaudRates(RadioControlSettings.MODEL_YAESU_FT857))
        assertEquals(listOf(4800, 9600, 19200), supportedRadioBaudRates(RadioControlSettings.MODEL_ICOM_IC705))
        assertEquals(
            listOf(4800, 9600, 19200, 38400, 57600, 115200),
            supportedRadioBaudRates(RadioControlSettings.MODEL_ICOM_IC9700)
        )
        assertEquals(
            listOf(9600, 19200, 4800, 1200, 300),
            supportedRadioBaudRates(RadioControlSettings.MODEL_ICOM_IC910)
        )
        assertEquals(listOf(38400,4800,9600,19200,57600,115200),
            supportedRadioBaudRates(RadioControlSettings.MODEL_YAESU_FTX1))
    }
}
