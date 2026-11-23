package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.model.*
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UsbPttRadioControllerTest {
    private class Cat : IRadioController {
        override var isConnected = false
        var catPttCalls = 0
        override suspend fun connect(): Boolean { isConnected = true; return true }
        override suspend fun disconnect() { isConnected = false }
        override suspend fun setFrequency(frequencyHz: Long) = true
        override suspend fun setMode(mode: String) = true
        override suspend fun setCtcssMode(enabled: Boolean) = true
        override suspend fun setCtcssTone(toneHz: Double) = true
        override suspend fun readFrequencyAndMode() = 145_900_000L to "USB"
        override suspend fun pttOn(): Boolean { catPttCalls++; return true }
        override suspend fun pttOff(): Boolean { catPttCalls++; return true }
        override suspend fun startCw(text: String, wpm: Int) = true
    }
    private class Line : RadioTransport {
        override var isConnected = false
        var configuration: UsbPttSettings? = null
        var failureOnRelease = false
        var cancelOnArm = false
        val levels = mutableListOf<Boolean>()
        override fun reserveUsbPtt(settings: UsbPttSettings): Boolean { configuration = settings; return true }
        override suspend fun connect(): Boolean { isConnected = true; return true }
        override suspend fun disconnect() { isConnected = false }
        override suspend fun setUsbPtt(active: Boolean): Boolean {
            levels += if (active) configuration!!.activeHigh else !configuration!!.activeHigh
            if (active && cancelOnArm) throw CancellationException("cancelled USB write")
            return isConnected && (active || !failureOnRelease)
        }
        override suspend fun write(bytes: ByteArray) = true
        override suspend fun readAvailable(maxBytes: Int) = byteArrayOf()
    }

    @Test fun bothLinesAndPolaritiesInitializeInactiveAndReleaseWithoutCatPtt() = runTest {
        for (pin in UsbPttLine.entries) for (high in listOf(false, true)) {
            val cat = Cat(); val line = Line()
            val radio = UsbPttRadioController(cat, line, UsbPttSettings(true, line = pin, activeHigh = high), false)
            assertTrue(radio.connect())
            assertEquals(listOf(!high), line.levels)
            assertTrue(radio.pttOn())
            assertTrue(radio.pttOff())
            assertEquals(listOf(!high, high, !high), line.levels)
            assertEquals(0, cat.catPttCalls)
            assertFalse(radio.supportsPttReadback)
            radio.disconnect()
            assertFalse(line.isConnected)
        }
    }

    @Test fun emergencyInvalidatesOldArmAndReleaseFailureRemainsVisible() = runTest {
        val line = Line(); val radio = UsbPttRadioController(Cat(), line, UsbPttSettings(true), false)
        assertTrue(radio.connect())
        val old = radio.pttSafetyGeneration()
        radio.invalidatePendingPttOn()
        assertFalse(radio.pttOnIfGeneration(old))
        assertFalse(radio.pttOnIfGeneration(radio.pttSafetyGeneration()) { false })
        assertEquals(1, line.levels.size)
        assertTrue(radio.pttOn())
        line.failureOnRelease = true
        assertFalse(radio.pttOff())
    }

    @Test fun partialUsbArmIsReleasedOnCancellation() = runTest {
        val line = Line(); val radio = UsbPttRadioController(Cat(), line, UsbPttSettings(true), false)
        assertTrue(radio.connect())
        line.cancelOnArm = true
        assertTrue(runCatching { radio.pttOn() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf(false, true, false), line.levels)
    }

    @Test fun nativeCwStillUsesNativeStopWhenExternalVoicePttIsSelected() = runTest {
        val cat = Cat(); val radio = UsbPttRadioController(cat, Line(), UsbPttSettings(true), false)
        assertTrue(radio.connect())
        assertTrue(radio.startCw("CQ", 20))
        assertTrue(radio.pttOff())
        assertEquals(1, cat.catPttCalls)
    }

    @Test fun sharedPortDoesNotOpenOrCloseAnIndependentConnection() = runTest {
        val line = Line()
        val cat = object : IRadioController by Cat() {
            override val isConnected get() = line.isConnected
            override suspend fun connect() = line.connect()
            override suspend fun disconnect() = line.disconnect()
        }
        val radio = UsbPttRadioController(cat, line, UsbPttSettings(true, UsbPttPort.CAT), true)
        assertTrue(radio.connect())
        assertTrue(radio.pttOn())
        radio.disconnect()
        assertEquals(false, line.levels.last())
        assertFalse(line.isConnected)
    }

    @Test fun configurationDetectsSharedInterfacesAndPreservesL4sHandshake() {
        val original = RadioControlSettings(true, RadioControlSettings.MODEL_ICOM_IC820,
            "7:0:1:1:2", "7:2:3:1:2", "TX", "RX", 1200, catTransport = RadioControlSettings.TRANSPORT_USB)
        assertNull(original.usbPttConfigurationError())
        val separate = original.copy(usbPtt = UsbPttSettings(true, address = "7:4:5:1:2"))
        assertNull(separate.usbPttConfigurationError())
        assertEquals("PORT_IN_USE", original.copy(usbPtt = UsbPttSettings(true, address = "7:3:3:1:2")).usbPttConfigurationError())
        assertEquals("LINE_RESERVED", original.copy(catRts = true, usbPtt = UsbPttSettings(true, UsbPttPort.CAT)).usbPttConfigurationError())
        assertEquals("HANDSHAKE", original.copy(radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1,
            usbPtt = UsbPttSettings(true, UsbPttPort.CAT, line = UsbPttLine.DTR)).usbPttConfigurationError())
    }
}
