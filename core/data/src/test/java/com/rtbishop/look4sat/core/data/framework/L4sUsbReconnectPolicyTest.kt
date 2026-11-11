package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L4sUsbReconnectPolicyTest {
    private val configured = RadioControlSettings(
        enabled = true,
        radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1,
        catTransport = RadioControlSettings.TRANSPORT_USB,
        txRadioAddress = "17:0:1:14007:65535",
        rxRadioAddress = "",
        txRadioName = "UV-K1",
        rxRadioName = "",
        baudRate = 38_400
    )

    @Test fun matchesDeviceAfterAddressChanges() {
        assertTrue(matchesConfiguredL4sUsbDevice(configured, 0x36B7, 0xFFFF))
        assertTrue(matchesConfiguredL4sUsbDevice(configured.copy(txRadioAddress = "35:0:1:14007:65535"), 0x36B7, 0xFFFF))
    }

    @Test fun rejectsUnrelatedDevicesAndDisabledRadio() {
        assertFalse(matchesConfiguredL4sUsbDevice(configured, 0x0403, 0x6001))
        assertFalse(matchesConfiguredL4sUsbDevice(configured.copy(enabled = false), 0x36B7, 0xFFFF))
        assertFalse(matchesConfiguredL4sUsbDevice(configured.copy(catTransport = RadioControlSettings.TRANSPORT_BLUETOOTH), 0x36B7, 0xFFFF))
        assertFalse(matchesConfiguredL4sUsbDevice(configured.copy(txRadioAddress = "invalid"), 0x36B7, 0xFFFF))
    }
}
