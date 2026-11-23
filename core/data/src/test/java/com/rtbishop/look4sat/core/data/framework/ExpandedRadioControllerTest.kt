package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.model.RadioLanLogin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ExpandedRadioControllerTest {
    @Test
    fun ft847AddressesBothSatelliteVfosAndChecksKeyingReadback() = runTest {
        val vfos = mutableMapOf(0 to byteArrayOf(0x14, 0x59, 0, 0, 1))
        var keyed = false
        val transport = TestRadioWire { frame ->
            val opcode = frame.last().toInt() and 0xFF
            val role = opcode and 0xF0
            when (opcode) {
                0x4E -> {
                    vfos[0x10] = byteArrayOf(0x43, 0x51, 0, 0, 1)
                    vfos[0x20] = byteArrayOf(0x14, 0x59, 0, 0, 0)
                    null
                }
                0x08 -> { keyed = true; null }
                0x88 -> { keyed = false; null }
                0xF7 -> byteArrayOf(if (keyed) 0 else 0x80.toByte())
                0x03, 0x13, 0x23 -> vfos[role]?.copyOf()
                0x01, 0x11, 0x21 -> {
                    frame.copyInto(checkNotNull(vfos[role]), endIndex = 4)
                    null
                }
                else -> null
            }
        }
        val radio = Ft847Controller(transport, satelliteLayout = true)
        assertTrue(radio.connect())
        assertTrue(radio.setSplitMode(true))
        assertTrue(radio.setWorkingFrequency(435_101_236))
        assertTrue(radio.setTxVfoFrequency(145_909_995))
        assertEquals(435_101_240L, radio.readWorkingFrequency())
        assertEquals(145_910_000L, radio.readTxVfoFrequency())
        assertTrue(radio.pttOn())
        assertTrue(radio.pttOff())
        assertFalse(keyed)
        assertEquals(1, transport.commands.count { (it.last().toInt() and 0xFF) == 0x08 })
    }

    @Test
    fun kenwoodSerialUsesModelIdentityAndTransverterOffset() = runTest {
        var raw = "00028900000"
        var mode = "OM02"
        val transport = TestRadioWire { bytes ->
            when (val frame = bytes.decodeToString()) {
                "ID;" -> "ID022;".encodeToByteArray()
                "FA;" -> "FA$raw;".encodeToByteArray()
                "CB;" -> "CB0;".encodeToByteArray()
                "TB;" -> "TB0;".encodeToByteArray()
                "XV;" -> "XV1;".encodeToByteArray()
                "XO;" -> "XO000116000000;".encodeToByteArray()
                "OM0;" -> "$mode;".encodeToByteArray()
                else -> {
                    if (frame.startsWith("FA")) raw = frame.drop(2).dropLast(1)
                    if (frame.startsWith("OM0")) mode = frame.dropLast(1)
                    null
                }
            }
        }
        val radio = KenwoodController(transport, KenwoodModel.TS_990, satelliteLayout = false)
        assertTrue(radio.connect())
        assertTrue(radio.setMode("USB"))
        assertTrue(radio.setFrequency(145_910_020))
        assertEquals("00029910020", raw)
        assertEquals(145_910_020L to "USB", radio.readFrequencyAndMode())
        assertTrue(radio.setDataMode(true, "USB"))
        assertEquals("OM0D", mode)
        assertFalse(radio.pttOn())
    }

    @Test
    fun ts590DataSwitchDoesNotRewriteTheVerifiedSideband() = runTest {
        var data = false
        val wire = TestRadioWire { bytes ->
            when (bytes.decodeToString()) {
                "ID;" -> "ID023;".encodeToByteArray()
                "FA;" -> "FA00029000000;".encodeToByteArray()
                "MD;" -> "MD2;".encodeToByteArray()
                "DA;" -> "DA${if (data) 1 else 0};".encodeToByteArray()
                "DA1;" -> { data = true; null }
                "DA0;" -> { data = false; null }
                else -> error("Unexpected TS-590SG command")
            }
        }
        val radio = KenwoodController(wire, KenwoodModel.TS_590SG, satelliteLayout = false)
        assertTrue(radio.connect())
        assertFalse(radio.setDataMode(true, "LSB"))
        assertTrue(radio.setDataMode(true, "USB"))
        assertTrue(data)
        assertTrue(radio.setDataMode(false, "USB"))
        assertFalse(data)
    }

    @Test
    fun kenwoodLanChecksBothAuthenticationRepliesAndWireEncoding() = runTest {
        for ((model, charset, login) in listOf(
            Triple(KenwoodModel.TS_990, Charsets.UTF_16LE, "##ID24demo12"),
            Triple(KenwoodModel.TS_890, Charsets.UTF_8, "##ID00204demo12")
        )) {
            val credentials = RadioLanLogin("de", "mo12", administrator = true)
            assertEquals(login, kenwoodLoginFrame(model, credentials))
            val radio = KenwoodController(TestRadioWire { bytes ->
                val answer = when (String(bytes, charset)) {
                    "##CN;" -> "##CN1;"
                    "$login;" -> "##ID1;"
                    "ID;" -> "ID${model.id};"
                    "FA;" -> "FA00029000000;"
                    else -> null
                }
                answer?.toByteArray(charset)
            }, model, false, useLanLogin = true, login = credentials)
            assertTrue(radio.connect())
            radio.disconnect()
        }
        assertNull(kenwoodLoginFrame(KenwoodModel.TS_990, RadioLanLogin("wrong;field", "pin")))
    }

    @Test
    fun smartSdrUsesLiveSliceRolesAndRejectsTuneWithoutStatusReadback() = runTest {
        var reportTune = true
        val transport = TestRadioWire { bytes ->
            val command = bytes.decodeToString().trim()
            val sequence = command.substringAfter('C').substringBefore('|')
            val body = command.substringAfter('|')
            val status = when {
                body == "sub slice all" -> "S21|slice 7 in_use=1 tx=1 mode=LSB RF_frequency=145.910000\n" +
                    "S21|slice 3 in_use=1 tx=0 active=1 mode=USB RF_frequency=435.100000\n"
                body == "sub radio all" -> "S21|radio full_duplex_enabled=0\n"
                body == "radio set full_duplex_enabled=1" -> "S21|radio full_duplex_enabled=1\n"
                reportTune && body.startsWith("slice tune 7 ") -> "S21|slice 7 RF_frequency=145.910303\n"
                else -> ""
            }
            "R999|50000016|\nR$sequence|0|\n$status".encodeToByteArray()
        }
        val radio = FlexRadioController(transport)
        assertTrue(radio.connect())
        assertTrue(radio.setSplitMode(true))
        assertEquals(435_100_000L, radio.readWorkingFrequency())
        assertTrue(radio.setTxVfoFrequency(145_910_303))
        reportTune = false
        assertFalse(radio.setTxVfoFrequency(145_910_500))
        radio.disconnect()
        assertTrue(transport.commands.any { it.decodeToString().contains("radio set full_duplex_enabled=0") })
    }

    @Test
    fun sharedCivBusDoesNotCloseWhileEitherEndpointIsConnected() = runTest {
        val transport = TestRadioWire { null }
        val bus = SharedCivTransport(transport)
        val tx = bus.endpoint()
        val rx = bus.endpoint()
        assertTrue(tx.connect())
        assertTrue(rx.connect())
        assertEquals(1, transport.openCount)
        tx.disconnect()
        assertTrue(rx.isConnected)
        rx.disconnect()
        assertFalse(transport.isConnected)
    }
}

private class TestRadioWire(private val respond: (ByteArray) -> ByteArray?) : RadioTransport {
    private val replies = ArrayDeque<Byte>()
    val commands = mutableListOf<ByteArray>()
    var openCount = 0
        private set
    override var isConnected = false
        private set
    override suspend fun connect(): Boolean {
        openCount++
        isConnected = true
        return true
    }
    override suspend fun disconnect() { isConnected = false; replies.clear() }
    override suspend fun write(bytes: ByteArray): Boolean {
        if (!isConnected) return false
        commands += bytes.copyOf()
        respond(bytes)?.forEach { replies.addLast(it) }
        return true
    }
    override suspend fun readAvailable(maxBytes: Int): ByteArray =
        ByteArray(minOf(maxBytes, replies.size, 3)) { replies.removeFirst() }
}
