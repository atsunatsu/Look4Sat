package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingDisplay
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real loopback socket; the peer parses and frames bytes independently of N76Protocol. */
class N76LoopbackTest {
    @Test fun realSocketTracksAndKeysWithAudioOffThenResumesFreshFrequencies() = exercise { radio, peer, _ ->
        assertEquals(listOf(4, 36, 20, 6), peer.packets.take(4).map { it.command })
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_000_010, 145_950_020))
        assertEquals(435_000_010L, peer.rxHz)
        assertEquals(145_950_020L, peer.txHz)
        assertTrue(radio.pttOn())
        assertTrue(peer.transmitting)
        val tuned = peer.packets.count { it.command == 35 }
        assertEquals(FrequencyPairResult.HELD, radio.setTrackingFrequencies(435_005_000, 145_955_000))
        assertEquals(tuned, peer.packets.count { it.command == 35 })
        assertTrue(radio.pttOff())
        assertFalse(peer.transmitting)
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_008_030, 145_958_040))
        assertEquals(435_008_030L, peer.rxHz)
        assertEquals(145_958_040L, peer.txHz)
        val ptt = peer.packets.filter { it.command == 66 }.map { it.hex }
        assertEquals(listOf("ff01000200020042000d", "ff01000200020042001a"), ptt)
        assertFalse(peer.packets.any { it.command in listOf(12, 14, 31, 32, 33, 34) })
        assertFalse(radio.remoteState.value.phoneMicrophone)
        assertTrue(radio.setSplitMode(false))
        assertTrue(peer.packets.last { it.command == 35 }.payload.all { it == 0.toByte() })
    }

    @Test fun rejectedControlPttIsReportedWithoutDisconnectingAWorkingCatLink() = exercise { radio, peer, diagnostics ->
        radio.setTrackingFrequencies(435_000_000, 145_950_000)
        peer.rejectPtt = true
        assertFalse(radio.pttOn())
        assertFalse(peer.transmitting)
        assertTrue(radio.isConnected)
        assertTrue(diagnostics.events.value.any { it.stage == "n76/reply/rejected" && "status=5" in it.message })
        peer.rejectPtt = false
        assertTrue(radio.pttOn())
        assertTrue(radio.pttOff())
    }

    @Test fun aLostStatusReplyEndsTheOwnedPttAndNeverReconnectsOrReplaysIt() = exercise { radio, peer, diagnostics ->
        radio.setTrackingFrequencies(435_000_000, 145_950_000)
        assertTrue(radio.pttOn())
        peer.suppressStatus = true
        assertNull(radio.readPttState())
        assertFalse(radio.isConnected)
        assertTrue(peer.released.await(2, TimeUnit.SECONDS))
        assertFalse(peer.transmitting)
        assertTrue(diagnostics.events.value.any { it.stage == "n76/reply/timeout" })
        assertEquals(1, peer.packets.count { it.command == 66 && it.payload.contentEquals(byteArrayOf(0, 13)) })
    }

    @Test fun idleStatusTimeoutRecoversOnTheSameSocket() = exercise { radio, peer, diagnostics ->
        radio.setTrackingFrequencies(435_000_000, 145_950_000)
        peer.suppressStatus = true
        assertNull(radio.readPttState())
        assertTrue(radio.isConnected)
        assertFalse(radio.remoteState.value.transmitStateKnown)
        peer.suppressStatus = false
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_003_000, 145_953_000))
        assertTrue(radio.isConnected)
        assertNull(radio.remoteState.value.error)
        assertEquals(435_003_000L, peer.rxHz)
        assertTrue(diagnostics.events.value.any { it.stage == "n76/status/recovered" })
    }

    private fun exercise(block: suspend (N76Controller, Peer, ControlDiagnosticsBuffer) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val diagnostics = ControlDiagnosticsBuffer()
        Peer().use { peer ->
            val radio = N76Controller(TcpRadioTransport("127.0.0.1", peer.port), scope, diagnostics, replyTimeoutMillis = 250)
            try {
                assertTrue(radio.connect())
                assertTrue(radio.prepareTrackingContext("loopback-pass"))
                assertTrue(radio.updateTrackingDisplay(RadioTrackingDisplay("TEST", 123.0, -25.0)))
                block(radio, peer, diagnostics)
            } finally { radio.disconnect(); scope.cancel() }
        }
    }

    private data class Packet(val command: Int, val payload: ByteArray, val hex: String)
    private class Peer : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val packets = Collections.synchronizedList(mutableListOf<Packet>())
        val released = CountDownLatch(1)
        @Volatile var transmitting = false
        @Volatile var rxHz = 0L
        @Volatile var txHz = 0L
        @Volatile var rejectPtt = false
        @Volatile var suppressStatus = false
        @Volatile private var socket: Socket? = null
        private val worker = thread(isDaemon = true, name = "n76-local-simulator") {
            try {
                val connected = server.accept().also { socket = it; it.tcpNoDelay = true }
                val input = DataInputStream(connected.getInputStream())
                while (!connected.isClosed) {
                    val header = ByteArray(8).also(input::readFully)
                    check(header.take(3) == listOf(0xff.toByte(), 1.toByte(), 0.toByte()))
                    check(header[4] == 0.toByte() && header[5] == 2.toByte())
                    val payload = ByteArray(header[3].toInt() and 255).also(input::readFully)
                    val command = ((header[6].toInt() and 127) shl 8) or (header[7].toInt() and 255)
                    packets += Packet(command, payload, (header + payload).joinToString("") { "%02x".format(it.toInt() and 255) })
                    if (command == 6 || command == 20 && suppressStatus) continue
                    var status = 0
                    val response = when (command) {
                        4 -> byteArrayOf(0, 1, 3, 1, 0, 137.toByte(), 0, 0, 32, 0)
                        36 -> byteArrayOf(0, 0, 0, 0)
                        20 -> byteArrayOf(if (transmitting) 0xc0.toByte() else 0x80.toByte(), 0, 0, 0)
                        35 -> {
                            if (payload.size >= 8 && payload.any { it != 0.toByte() }) {
                                rxHz = uint32(payload, 0); txHz = uint32(payload, 4)
                            }
                            byteArrayOf()
                        }
                        66 -> {
                            if (rejectPtt) status = 5
                            else when {
                                payload.contentEquals(byteArrayOf(0, 13)) -> transmitting = true
                                payload.contentEquals(byteArrayOf(0, 26)) -> { transmitting = false; released.countDown() }
                            }
                            byteArrayOf()
                        }
                        else -> byteArrayOf()
                    }
                    val reply = byteArrayOf(-1, 1, 0, (response.size + 1).toByte(), 0, 2,
                        ((command shr 8) or 0x80).toByte(), command.toByte(), status.toByte()) + response
                    // Separate writes exercise actual serial-like fragmentation, not a queued mock reply.
                    connected.getOutputStream().apply { write(reply, 0, 5); flush(); write(reply, 5, reply.size - 5); flush() }
                }
            } catch (_: java.io.IOException) { /* Expected when the test closes either end. */ }
        }
        private fun uint32(bytes: ByteArray, offset: Int): Long =
            (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255) } and 0x3fffffffL
        override fun close() {
            socket?.close(); server.close(); worker.join(2_000)
        }
    }
}
