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

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ic705ControllerTest {
    @Test fun ic820NormalCwIncludesTheDocumentedFilterSelector() = runTest {
        val link=ScriptedCivTransport(civAddress=0x42)
        val radio=Ic705Controller(null,"USB",0x42,link,IcomCivVariant.IC820)
        assertTrue(radio.connect());link.payloads.clear()
        assertTrue(radio.setMode("CW"))
        assertEquals(listOf("06:03:01","04"),link.payloads)
        radio.disconnect()
    }
    @Test fun ic910VerifiesTheManualFmCodeAndFallsBackForOtherFirmware() = runTest {
        for (reject04 in listOf(false,true)) {
            val link=ScriptedCivTransport(civAddress=0x60,rejectIc910Fm04=reject04)
            val controller=Ic705Controller(null,"USB",0x60,link,IcomCivVariant.IC910)
            assertTrue(controller.connect()); link.payloads.clear()
            assertTrue(controller.setMode("FM"))
            assertEquals("FM",controller.readFrequencyAndMode()!!.second)
            assertTrue(link.payloads.contains("06:04"))
            assertEquals(reject04,link.payloads.contains("06:05"))
            controller.disconnect()
        }
    }
    @Test fun splitIsEnabledAndReadBackWithoutSwitchingToTxVfo() = runTest {
        val transport = ScriptedCivTransport()
        val radio = Ic705Controller(null, "USB", transport = transport)
        assertTrue(radio.connect())
        transport.payloads.clear()
        assertTrue(radio.setSplitMode(true))
        assertEquals(listOf("0F:01", "0F"), transport.payloads)
    }

    @Test fun txDataModeUsesUnselectedVfoCommandWithoutSelectingTx() = runTest {
        val transport = ScriptedCivTransport()
        val radio = Ic705Controller(null, "USB", transport = transport)
        assertTrue(radio.connect())
        transport.payloads.clear()
        assertTrue(radio.setTxDataMode(true, "USB"))
        assertTrue(radio.setTxDataMode(false, "USB"))
        assertEquals(listOf("26:01:01:01:01", "26:01", "26:01:01:00:01", "26:01"), transport.payloads)
    }

    @After fun closeSimulatedTransports() = runBlocking {
        ScriptedCivTransport.instances.forEach { it.disconnect() }
        ScriptedCivTransport.instances.clear()
    }
    @Test fun modernIcomReadPttUsesStatusQueryAndIc820NeverSendsIt()=runTest {
        for(variant in IcomCivVariant.entries) {
            val transport=ScriptedCivTransport()
            val radio=Ic705Controller(null,"USB",transport=transport,variant=variant,dedicatedSatelliteMode=false)
            assertTrue(radio.connect())
            transport.payloads.clear()
            if(variant==IcomCivVariant.IC820) {
                assertFalse(radio.supportsPttReadback);assertNull(radio.readPttState())
                assertFalse(transport.payloads.contains("1C:00"))
            } else {
                assertTrue(radio.supportsPttReadback);assertEquals(false,radio.readPttState())
                assertEquals(listOf("1C:00"),transport.payloads)
            }
        }
    }
    @Test
    fun ic820FrequencyAndModeWritesRequireReadbackAndUseConfiguredAddress() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0x43, trackFrequencyWrites = true)
        val radio = Ic705Controller(null, "USB", 0x43, transport, IcomCivVariant.IC820)
        assertTrue(radio.connect())
        transport.payloads.clear()
        assertTrue(radio.setFrequency(145_580_000L))
        assertTrue(radio.setMode("LSB"))
        assertEquals(145_580_000L to "LSB", radio.readFrequencyAndMode())
        assertEquals(listOf("05:00:00:58:45:01", "03", "06:00", "04", "03", "04"), transport.payloads)
        assertTrue(transport.destinations.all { it == 0x43 })
        radio.disconnect()
    }

    @Test
    fun ic820RejectsIgnoredFrequencyWritesAndUnsupportedFeaturesWithoutSendingCommands() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0x42)
        val radio = Ic705Controller(null, "USB", 0x42, transport, IcomCivVariant.IC820)
        assertTrue(radio.connect())
        assertFalse(radio.setFrequency(145_580_000L)) // ACK alone is not success.
        transport.payloads.clear()
        assertFalse(radio.setFrequency(28_500_000L))
        assertFalse(radio.setMode("AM"))
        assertFalse(radio.setMode("NFM"))
        assertFalse(radio.setMode("RTTY"))
        assertFalse(radio.setDataMode(true, "USB"))
        assertFalse(radio.setTxDataMode(true, "USB"))
        assertTrue(radio.setDataMode(false, "USB"))
        assertFalse(radio.setCtcssMode(true))
        assertFalse(radio.setCtcssTone(67.0))
        assertTrue(radio.setCtcssMode(false))
        assertTrue(radio.configureTxCtcss(null))
        assertFalse(radio.configureTxCtcss(67.0))
        assertFalse(radio.pttOn())
        assertFalse(radio.pttOff())
        radio.disconnect()
        assertTrue(transport.payloads.isEmpty())
    }

    @Test
    fun ic820BandAccessUsesItsOwnMainSubSelectorsWithoutExchangingBands() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0x42, ic820BandAccess = true)
        val radio = Ic705Controller(null, "USB", 0x42, transport, IcomCivVariant.IC820)
        assertTrue(radio.connect())
        transport.payloads.clear()
        assertTrue(radio.setBand(435_100_000L))
        assertEquals(listOf("07:D1", "03", "07:D0", "03"), transport.payloads)
        transport.payloads.clear()
        assertTrue(radio.setBand(145_900_000L))
        assertEquals(listOf("07:D1", "03"), transport.payloads)
        transport.payloads.clear()
        assertFalse(radio.setBand(1_296_000_000L))
        assertTrue(transport.payloads.isEmpty())
        radio.disconnect()
    }

    @Test
    fun ic820OrdinarySplitAndManualVfoCommandsNeverUseModernSatelliteCommands() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0x42, trackFrequencyWrites = true)
        val radio = Ic705Controller(null, "USB", 0x42, transport, IcomCivVariant.IC820)
        assertTrue(radio.connect())
        transport.payloads.clear()
        assertTrue(radio.setSplitMode(true))
        assertEquals("0F:00", transport.payloads.single())
        assertTrue(radio.setSplitModes("USB", "LSB"))
        assertTrue(radio.setWorkingFrequency(145_900_000L))
        assertTrue(radio.setTxVfoFrequency(145_950_000L))
        assertEquals("07:00", transport.payloads.last())
        assertFalse(transport.commandBytes.any { it in setOf(0x25, 0x26, 0x1A, 0x16, 0x1C) })
        radio.disconnect()
        assertEquals("0F:01", transport.payloads.last())
    }

    @Test
    fun ic820MalformedLegacyFrequencyReplyCannotEstablishConnection() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0x42, frequencyPayloadOverride = byteArrayOf(0, 0, 0x59, 0x45))
        val radio = Ic705Controller(null, "USB", 0x42, transport, IcomCivVariant.IC820)
        assertFalse(radio.connect())
        assertFalse(transport.isConnected)
    }

    @Test
    fun dataModeUsesDocumentedIcomCommandAndIc910RejectsEnable() = runTest {
        for ((variant, address, supported) in listOf(
            Triple(IcomCivVariant.IC705, 0xA4, true),
            Triple(IcomCivVariant.IC9700, 0xA2, true),
            Triple(IcomCivVariant.IC910, 0x60, false)
        )) {
            val transport = ScriptedCivTransport(civAddress = address)
            val radio = Ic705Controller(null, "USB", address.toByte(), transport, variant)
            assertTrue(radio.connect())
            transport.payloads.clear()
            assertEquals(supported, radio.setDataMode(true, "USB"))
            if (variant == IcomCivVariant.IC705) {
                assertEquals(listOf("26:00:01:01:01", "26:00"), transport.payloads)
                assertTrue(radio.setDataMode(false, "USB"))
                assertEquals(listOf("26:00:01:00:01", "26:00"), transport.payloads.takeLast(2))
            } else if (supported) {
                assertEquals("1A:06:01:02", transport.payloads.single())
                assertTrue(radio.setDataMode(false, "USB"))
                assertEquals("1A:06:00:00", transport.payloads.last())
            } else {
                assertTrue(transport.payloads.isEmpty())
                assertTrue(radio.setDataMode(false, "USB"))
            }
            radio.disconnect()
        }
    }

    @Test
    fun usbLsbCommandsAndReadbacksUseCorrectDuplexTargetsOnAllIcoms() = runTest {
        for ((variant, address) in listOf(
            IcomCivVariant.IC705 to 0xA4, IcomCivVariant.IC9700 to 0xA2, IcomCivVariant.IC910 to 0x60
        )) {
            val transport = ScriptedCivTransport(civAddress = address)
            val radio = Ic705Controller(null, "USB", address.toByte(), transport, variant)
            assertTrue(radio.connect())
            assertTrue(radio.setSplitMode(true))
            transport.payloads.clear()
            assertTrue(radio.setSplitModes("USB", "LSB"))
            if (variant == IcomCivVariant.IC705) {
                assertEquals(listOf("07:00", "26:00:01:00:01", "26:01:00:00:01", "26:00", "26:01"), transport.payloads)
            } else {
                assertEquals(listOf("07:D0", "06:01", "04", "07:D1", "06:00", "04", "07:D0"), transport.payloads)
            }
            assertTrue(radio.setSplitModes("LSB", "USB"))
            radio.disconnect()
        }
    }

    @Test
    fun wrongSidebandReadbackRejectsSetupAndRestoresMain() = runTest {
        for ((variant, address) in listOf(
            IcomCivVariant.IC705 to 0xA4, IcomCivVariant.IC9700 to 0xA2, IcomCivVariant.IC910 to 0x60
        )) {
            val transport = ScriptedCivTransport(civAddress = address, modeReadbackOverride = 0x05)
            val radio = Ic705Controller(null, "USB", address.toByte(), transport, variant)
            assertTrue(radio.connect())
            assertFalse(radio.setSplitModes("USB", "LSB"))
            if (variant != IcomCivVariant.IC705) assertEquals("07:D0", transport.payloads.last())
            radio.disconnect()
        }
    }

    @Test
    fun readFrequencyAndModeCombinesSeparateCivReplies() = runTest {
        val transport = ScriptedCivTransport()
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )
        assertTrue(controller.connect())

        assertEquals(145_590_000L to "USB", controller.readFrequencyAndMode())
        assertEquals(listOf(0x03, 0x03, 0x04), transport.commandBytes)
    }

    @Test
    fun ic705ConnectionUsesReadOnlyFrequencyWithoutRequiringVfoAck() = runTest {
        val transport = ScriptedCivTransport(acknowledgeConnect = false)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )

        assertTrue(controller.connect())
        assertTrue(controller.isConnected)
        assertEquals(listOf(0x03), transport.commandBytes)
    }

    @Test
    fun splitModesTargetBothVfosAndRequireReadback() = runTest {
        val transport = ScriptedCivTransport()
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )
        assertTrue(controller.connect())

        assertTrue(controller.setSplitModes(rxMode = "USB", txMode = "FM"))

        assertEquals(
            listOf(
                "07:00",
                "26:00:01:00:01",
                "26:01:05:00:01",
                "26:00",
                "26:01"
            ),
            transport.payloads.drop(1)
        )
    }

    @Test
    fun ic705FrequencyChangeDoesNotRewriteToneOrSwitchVfo() = runTest {
        val transport = ScriptedCivTransport()
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )
        assertTrue(controller.connect())
        assertTrue(controller.setCtcssMode(true))
        transport.payloads.clear()

        assertTrue(controller.setFrequency(145_900_000L))

        assertEquals(0x05, transport.payloads.first().substringBefore(':').toInt(16))
        assertEquals(1, transport.payloads.size)
    }

    @Test
    fun bandPreparationDoesNotWriteBandStackingMemory() = runTest {
        val transport = ScriptedCivTransport()
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )
        assertTrue(controller.connect())
        transport.payloads.clear()

        assertTrue(controller.setBand(435_100_000L))

        assertTrue(transport.payloads.isEmpty())
    }

    @Test
    fun ic705TxFrequencyUpdateDoesNotSwitchAwayFromRxEvenWithToneEnabled() = runTest {
        val transport = ScriptedCivTransport()
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )
        assertTrue(controller.connect())
        assertTrue(controller.setVfo(vfoA = false))
        assertTrue(controller.setCtcssMode(true))
        assertTrue(controller.setVfo(vfoA = true))
        transport.payloads.clear()

        assertTrue(controller.setTxVfoFrequency(435_100_000L))

        assertEquals("25:01", transport.payloads.first().take(5))
        assertEquals(1, transport.payloads.size)
    }

    @Test
    fun txCtcssConfigurationTargetsTxAndReturnsToRxVfo() = runTest {
        val transport = ScriptedCivTransport()
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )
        assertTrue(controller.connect())
        transport.payloads.clear()

        assertTrue(controller.configureTxCtcss(88.5))

        assertEquals(
            listOf("07:01", "1B:00:00:08:85", "16:42:01", "07:00"),
            transport.payloads
        )
    }

    @Test
    fun ic9700UsesSatelliteMainSubInsteadOfSelectedVfoCommands() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0xA2)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC9700,
            transport = transport,
            variant = IcomCivVariant.IC9700
        )
        assertTrue(controller.connect())

        assertTrue(controller.setSplitMode(true))
        assertTrue(controller.setSplitModes(rxMode = "USB", txMode = "FM"))
        assertTrue(controller.setWorkingFrequency(145_900_000L))
        assertTrue(controller.setTxVfoFrequency(435_100_000L))

        val satelliteCommands = transport.payloads.drop(3)
        assertEquals("0F:00", satelliteCommands.first())
        assertEquals("16:5A:01", satelliteCommands[1])
        assertTrue(satelliteCommands.contains("07:D0"))
        assertTrue(satelliteCommands.contains("07:D1"))
        assertFalse(satelliteCommands.any { it.startsWith("25:") || it.startsWith("26:") })
    }

    @Test
    fun ic9700RestoresMainWhenSubFrequencyCommandFails() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0xA2, acknowledgeSetFrequency = false)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC9700,
            transport = transport,
            variant = IcomCivVariant.IC9700
        )
        assertTrue(controller.connect())

        assertFalse(controller.setTxVfoFrequency(435_100_000L))
        assertEquals("07:D0", transport.payloads.last())
    }

    @Test
    fun ic9700RestoresMainWhenSubSelectionFails() = runTest {
        val transport = ScriptedCivTransport(
            civAddress = 0xA2,
            acknowledgeTxSelection = false
        )
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC9700,
            transport = transport,
            variant = IcomCivVariant.IC9700
        )
        assertTrue(controller.connect())
        transport.payloads.clear()

        assertFalse(controller.setTxVfoFrequency(435_100_000L))
        assertEquals(listOf("07:D1", "07:D0"), transport.payloads)
        transport.payloads.clear()

        assertNull(controller.readTxVfoFrequency())
        assertEquals(listOf("07:D1", "07:D0"), transport.payloads)
    }

    @Test
    fun ic9700CanUseOrdinarySplitFallback() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0xA2)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC9700,
            transport = transport,
            variant = IcomCivVariant.IC9700,
            dedicatedSatelliteMode = false
        )
        assertTrue(controller.connect())

        assertTrue(controller.setSplitMode(true))
        assertTrue(controller.setSplitModes(rxMode = "USB", txMode = "FM"))

        val commands = transport.payloads.drop(3)
        assertEquals(listOf("16:5A:00", "16:5A", "0F:01"), commands.take(3))
        assertFalse(commands.any { it == "07:D0" || it == "07:D1" })
        assertTrue(commands.any { it.startsWith("26:00") })
        assertTrue(commands.any { it.startsWith("26:01") })
    }

    @Test
    fun ic910UsesDocumentedSatelliteCommandAndMainSubControl() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0x60)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC910,
            transport = transport,
            variant = IcomCivVariant.IC910
        )
        assertTrue(controller.connect())

        assertTrue(controller.setSplitMode(true))
        assertTrue(controller.setSplitModes(rxMode = "USB", txMode = "FM"))
        assertTrue(controller.setWorkingFrequency(435_100_000L))
        assertTrue(controller.setTxVfoFrequency(145_900_000L))

        val commands = transport.payloads.drop(3)
        assertEquals(listOf("0F:00", "1A:07:01", "1A:07"), commands.take(3))
        assertTrue(commands.contains("07:D0"))
        assertTrue(commands.contains("07:D1"))
        assertFalse(commands.any { it.startsWith("25:") || it.startsWith("26:") })
    }

    @Test
    fun satelliteModeRequiresMatchingReadback() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0xA2, satelliteReadbackOverride = false)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC9700,
            transport = transport,
            variant = IcomCivVariant.IC9700
        )
        assertTrue(controller.connect())

        assertFalse(controller.setSplitMode(true))
        assertEquals(listOf("16:5A:01", "16:5A"), transport.payloads.takeLast(2))
    }

    @Test
    fun ic910OrdinarySplitFallbackAvoidsUnsupportedSelectedVfoCommands() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0x60)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC910,
            transport = transport,
            variant = IcomCivVariant.IC910,
            dedicatedSatelliteMode = false
        )
        assertTrue(controller.connect())

        assertTrue(controller.setSplitMode(true))
        assertTrue(controller.setSplitModes(rxMode = "USB", txMode = "FM"))
        assertTrue(controller.setWorkingFrequency(435_100_000L))
        assertTrue(controller.setTxVfoFrequency(145_900_000L))

        val commands = transport.payloads.drop(3)
        assertEquals(listOf("1A:07:00", "1A:07", "0F:01"), commands.take(3))
        assertTrue(commands.contains("07:00"))
        assertTrue(commands.contains("07:01"))
        assertFalse(commands.any { it.startsWith("25:") || it.startsWith("26:") })
    }

    @Test
    fun ic910RejectsModesOutsideItsDocumentedSsbCwFmSet() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0x60)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC910,
            transport = transport,
            variant = IcomCivVariant.IC910
        )
        assertTrue(controller.connect())
        transport.payloads.clear()

        assertFalse(controller.setMode("AM"))
        assertFalse(controller.setSplitModes(rxMode = "USB", txMode = "RTTY"))
        assertTrue(transport.payloads.isEmpty())
    }
}

private class ScriptedCivTransport(
    private val acknowledgeConnect: Boolean = true,
    private val civAddress: Int = 0xA4,
    private val acknowledgeSetFrequency: Boolean = true,
    private val satelliteReadbackOverride: Boolean? = null,
    private val acknowledgeTxSelection: Boolean = true,
    private val modeReadbackOverride: Int? = null,
    private val trackFrequencyWrites: Boolean = false,
    private val ic820BandAccess: Boolean = false,
    private val frequencyPayloadOverride: ByteArray? = null,
    private val rejectIc910Fm04: Boolean = false
) : RadioTransport {
    companion object { val instances = mutableListOf<ScriptedCivTransport>() }
    init { instances += this }
    private val replies = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    val commandBytes = mutableListOf<Int>()
    val payloads = mutableListOf<String>()
    val destinations = mutableListOf<Int>()
    private var mainFrequency = 145_590_000L
    private var subFrequency = 435_100_000L
    private var mainSelected = true
    private var mainMode = 0x01
    private var subMode = 0x05
    private var mainDataMode = 0
    private var subDataMode = 0
    private var satelliteMode = false
    private var splitMode = false
    override var isConnected = false
        private set

    override suspend fun connect(): Boolean {
        isConnected = true
        return true
    }

    override suspend fun disconnect() {
        isConnected = false
        replies.clear()
    }

    override suspend fun write(bytes: ByteArray): Boolean {
        if (!isConnected) return false
        val payload = bytes.copyOfRange(4, bytes.lastIndex)
        destinations += bytes[2].toInt() and 0xFF
        val command = payload.first().toInt() and 0xFF
        commandBytes += command
        payloads += payload.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        when (command) {
            0x07 -> {
                val selectsTx = payload.getOrNull(1)?.let { it == 0x01.toByte() || it == 0xD1.toByte() } == true
                val acknowledged = (payload.size < 2 && acknowledgeConnect) ||
                    (payload.size >= 2 && (!selectsTx || acknowledgeTxSelection))
                if (acknowledged && payload.size >= 2) {
                    mainSelected = payload[1] == 0x00.toByte() ||
                        payload[1] == (if (ic820BandAccess) 0xD1 else 0xD0).toByte()
                }
                if (acknowledged) replies += ack()
            }
            0x03 -> {
                val frequency = if (mainSelected || (!trackFrequencyWrites && !ic820BandAccess)) mainFrequency else subFrequency
                val data = frequencyPayloadOverride ?: IcomCivProtocol.encodeFrequencyBcd(frequency)
                replies += response(0x03, *data.map { it.toInt() and 0xFF }.toIntArray())
            }
            0x04 -> replies += response(0x04, modeReadbackOverride ?: if (mainSelected) mainMode else subMode, 0x02)
            0x05 -> if (acknowledgeSetFrequency) {
                if (trackFrequencyWrites) {
                    val frequency = IcomCivProtocol.decodeFrequencyBcd(payload.copyOfRange(1, 6))
                    if (mainSelected) mainFrequency = frequency else subFrequency = frequency
                }
                replies += ack()
            }
            0x0F -> if (payload.size >= 2) {
                splitMode = payload[1] == 1.toByte()
                replies += ack()
            } else replies += response(0x0F, if (splitMode) 1 else 0)
            0x16 -> when {
                payload.size >= 3 && payload[1] == 0x5A.toByte() -> {
                    satelliteMode = payload[2] == 0x01.toByte()
                    replies += ack()
                }
                payload.size == 2 && payload[1] == 0x5A.toByte() -> {
                    val enabled = satelliteReadbackOverride ?: satelliteMode
                    replies += response(0x16, 0x5A, if (enabled) 0x01 else 0x00)
                }
                else -> replies += ack()
            }
            0x1A -> when {
                payload.size >= 3 && payload[1] == 0x07.toByte() -> {
                    satelliteMode = payload[2] == 0x01.toByte()
                    replies += ack()
                }
                payload.size == 2 && payload[1] == 0x07.toByte() -> {
                    val enabled = satelliteReadbackOverride ?: satelliteMode
                    replies += response(0x1A, 0x07, if (enabled) 0x01 else 0x00)
                }
                else -> replies += ack()
            }
            0x1B -> replies += ack()
            0x1C -> if (payload.size >= 3) replies += ack() else replies += response(0x1C,0x00,0x00)
            0x25 -> replies += ack()
            0x06 -> {
                if (rejectIc910Fm04 && payload.getOrNull(1) == 0x04.toByte()) {
                    replies += response(0xFA)
                    return true
                }
                if (payload.size >= 2) {
                    if (mainSelected) mainMode = payload[1].toInt() and 0xFF
                    else subMode = payload[1].toInt() and 0xFF
                }
                replies += ack()
            }
            0x26 -> {
                if (payload.size >= 3) {
                    if (payload[1].toInt() == 0) mainMode = payload[2].toInt() and 0xFF
                    else subMode = payload[2].toInt() and 0xFF
                    if (payload.size >= 4) {
                        if (payload[1].toInt() == 0) mainDataMode = payload[3].toInt() else subDataMode = payload[3].toInt()
                    }
                    replies += ack()
                } else {
                    val mode = modeReadbackOverride ?: if (payload[1].toInt() == 0) mainMode else subMode
                    val otherSelector = if (payload[1].toInt() == 0) 0x01 else 0x00
                    val data = if (payload[1].toInt() == 0) mainDataMode else subDataMode
                    replies += response(0x26, otherSelector, mode, data, 0x01)
                    replies += response(0x26, payload[1].toInt(), mode, data, 0x01)
                }
            }
        }
        return true
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray =
        replies.poll()?.let { it.copyOf(minOf(it.size, maxBytes)) } ?: ByteArray(0)

    private fun ack(): ByteArray = response(0xFB)

    private fun response(command: Int, vararg payload: Int): ByteArray = byteArrayOf(
        0xFE.toByte(),
        0xFE.toByte(),
        0xE0.toByte(),
        civAddress.toByte(),
        command.toByte(),
        *payload.map(Int::toByte).toByteArray(),
        0xFD.toByte()
    )
}
