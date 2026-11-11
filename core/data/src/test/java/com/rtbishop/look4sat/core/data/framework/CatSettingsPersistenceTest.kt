package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CatSettingsPersistenceTest {
    private class Radio : IRadioController {
        override var isConnected = false
        override val supportsPttReadback = true
        var transmitting = false
        var frequency = 435_100_000L
        val commands = mutableListOf<String>()
        override suspend fun connect(): Boolean { isConnected = true; commands += "connect"; return true }
        override suspend fun disconnect() { isConnected = false; commands += "disconnect" }
        override suspend fun readPttState() = transmitting
        override suspend fun setFrequency(frequencyHz: Long): Boolean { frequency = frequencyHz; commands += "freq:$frequencyHz"; return true }
        override suspend fun setBand(frequencyHz: Long): Boolean { commands += "band:$frequencyHz"; return true }
        override suspend fun setMode(mode: String): Boolean { commands += "mode:$mode"; return true }
        override suspend fun setCtcssMode(enabled: Boolean) = true
        override suspend fun setCtcssTone(toneHz: Double) = true
        override suspend fun readFrequencyAndMode() = frequency to "CW"
        override suspend fun pttOn(): Boolean { commands += "ptt:on"; return true }
        override suspend fun pttOff(): Boolean { commands += "ptt:off"; return true }
    }

    private fun service(scope: CoroutineScope, settings: MutableStateFlow<RadioControlSettings>, tx: Radio, rx: Radio): RadioTrackingService {
        val repo = Proxy.newProxyInstance(ISettingsRepo::class.java.classLoader, arrayOf(ISettingsRepo::class.java)) { _, method, args ->
            when (method.name) {
                "getRadioControlSettings" -> settings
                "updateRadioControlSettings" -> { settings.value = args!![0] as RadioControlSettings; Unit }
                else -> error("Unexpected settings access: ${method.name}")
            }
        } as ISettingsRepo
        val satellites = Proxy.newProxyInstance(ISatelliteRepo::class.java.classLoader, arrayOf(ISatelliteRepo::class.java)) { _, method, _ ->
            error("Unexpected satellite access: ${method.name}")
        } as ISatelliteRepo
        return RadioTrackingService(scope, null, satellites, repo,
            controllerFactory = { _, address -> if (address == "TX") tx else rx })
    }
    private fun settings(model: String = RadioControlSettings.MODEL_YAESU_FT817, single: Boolean = false) =
        MutableStateFlow(RadioControlSettings(true, model, "TX", if (single) "" else "RX", "", "", 4800,
            splitMode = single, catTransport = "USB"))

    @Test fun missingDeviceDoesNotPreventSavingCatConfiguration() = runTest {
        val settings = settings(RadioControlSettings.MODEL_QUANSHENG_UVK1, true)
        val service = service(backgroundScope, settings, Radio(), Radio())
        val changed = settings.value.copy(txRadioAddress = "device not attached", l4sCorrectionEditStepHz = 20)
        assertTrue(service.applyRadioControlSettings(changed))
        assertEquals(changed, settings.value)
        service.disconnectRadios()
    }

    @Test fun realTransmitStateRejectsSavingWithoutStoppingTheTransmission() = runTest {
        val settings = settings()
        val tx = Radio(); val rx = Radio()
        val service = service(backgroundScope, settings, tx, rx)
        service.connectRadios()
        tx.transmitting = true
        val before = settings.value
        val commands = tx.commands.toList()
        assertFalse(service.applyRadioControlSettings(before.copy(baudRate = 9600)))
        assertEquals(before, settings.value)
        assertEquals(commands, tx.commands)
        tx.transmitting = false
        assertTrue(service.applyRadioControlSettings(before.copy(baudRate = 9600)))
        service.disconnectRadios()
    }

    @Test fun changingCatRtsDtrPersistsAndReopensTheAlreadyConnectedUsbSerialSession() = runTest {
        val settings = settings(RadioControlSettings.MODEL_ICOM_IC820, true)
        val tx = Radio()
        val service = service(backgroundScope, settings, tx, Radio())
        service.connectRadios()
        assertTrue(tx.isConnected)
        assertFalse(settings.value.catRts)
        assertFalse(settings.value.catDtr)

        assertTrue(service.applyRadioControlSettings(settings.value.copy(catRts = true, catDtr = true)))
        assertTrue(settings.value.catRts)
        assertTrue(settings.value.catDtr)
        assertTrue(tx.isConnected)
        assertEquals(2, tx.commands.count { it == "connect" })
        assertTrue(tx.commands.contains("disconnect"))

        assertTrue(service.applyRadioControlSettings(settings.value.copy(catRts = false, catDtr = false)))
        assertTrue(tx.isConnected)
        assertFalse(settings.value.catRts)
        assertFalse(settings.value.catDtr)
        assertEquals(3, tx.commands.count { it == "connect" })
        assertFalse(tx.commands.contains("ptt:on"))
        service.disconnectRadios()
    }

    @Test fun savingCatSettingsDoesNotReconnectAUsbRadioThatWasManuallyDisconnected() = runTest {
        val settings = settings(RadioControlSettings.MODEL_ICOM_IC820, true)
        val tx = Radio()
        val service = service(backgroundScope, settings, tx, Radio())
        assertTrue(service.applyRadioControlSettings(settings.value.copy(catRts = true)))
        assertFalse(tx.isConnected)
        assertEquals(0, tx.commands.count { it == "connect" })
        service.disconnectRadios()
    }

    @Test fun changingNonConnectionSettingsDoesNotRestartAWorkingUsbCatLink() = runTest {
        val settings = settings(RadioControlSettings.MODEL_ICOM_IC820, true)
        val tx = Radio()
        val service = service(backgroundScope, settings, tx, Radio())
        service.connectRadios()
        assertTrue(service.applyRadioControlSettings(settings.value.copy(dialSettleMillis = 900L)))
        assertTrue(tx.isConnected)
        assertEquals(1, tx.commands.count { it == "connect" })
        service.disconnectRadios()
    }

    @Test fun ic820SinglePresetUsesOnePhysicalConnectionAndDoesNotEnablePtt() = runTest {
        val tx = Radio(); val rx = Radio()
        val service = service(backgroundScope, settings(RadioControlSettings.MODEL_ICOM_IC820, true), tx, rx)
        service.connectRadios()
        assertTrue(service.state.value.splitMode)
        assertEquals(1, service.state.value.physicalConnectionCount)
        assertTrue(tx.isConnected); assertFalse(rx.isConnected)
        assertFalse(tx.commands.contains("ptt:on"))
        service.disconnectRadios()
    }

    @Test fun legacyPresetUpdatesOnlySelectedVfoAndNeverGuessesAnUnknownFrequency() = runTest {
        val radio = Radio()
        val result = tunePresetHalfDuplex(radio, false, 435_100_100, 145_900_100)
        assertTrue(result.success); assertEquals(435_100_100L, result.rxHz); assertNull(result.txHz)
        assertEquals(listOf("freq:435100100"), radio.commands)
        radio.commands.clear(); radio.frequency = 7_100_000
        assertFalse(tunePresetHalfDuplex(radio, false, 435_100_100, 145_900_100).success)
        assertTrue(radio.commands.isEmpty())
    }
}
