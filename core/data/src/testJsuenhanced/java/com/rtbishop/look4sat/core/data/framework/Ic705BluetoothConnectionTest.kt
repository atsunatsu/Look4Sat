/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class Ic705BluetoothConnectionTest {
    @Test fun readOnlyHandshakeAlsoWorksOverRealLocalTcpTransport() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val peer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val request = ByteArray(6)
                    var count = 0
                    while (count < request.size) {
                        val read = socket.getInputStream().read(request, count, request.size - count)
                        check(read > 0) { "Client ended before complete CI-V request" }
                        count += read
                    }
                    assertArrayEquals(IcomCivProtocol.buildReadFreqCommand(), request)
                    socket.getOutputStream().write(byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 3) +
                        IcomCivProtocol.encodeFrequencyBcd(435_500_000L) + 0xFD.toByte())
                    socket.getOutputStream().flush()
                }
            }
            val diagnostics = ControlDiagnosticsBuffer()
            val transport = TcpRadioTransport(server.inetAddress.hostAddress!!, server.localPort)
            try {
                assertTrue(Ic705Controller(null, "local simulator", transport = transport, diagnostics = diagnostics).connect())
                peer.await()
                assertTrue(diagnostics.exportText().contains("frequencyHz=435500000"))
                assertFalse(diagnostics.exportText().contains("civ/connect/fallback"))
            } finally {
                transport.disconnect()
                peer.cancel()
            }
        }
    }

    @Test fun replySlowerThanOldFiveHundredMillisecondWindowStillConnects() = runBlocking {
        val socket = SimulatedSppSocket(replyDelayMillis = 700L)
        val transport = BluetoothSppRadioTransport(SimulatedConnector(socket))
        assertTrue(Ic705Controller(null, "test", transport = transport).connect())
        assertEquals(listOf(3), socket.commands)
        transport.disconnect()
    }

    @Test fun blockedConnectTimesOutClosesSocketAndFallsBack() = runBlocking {
        val first = SimulatedSppSocket(blockConnect = true)
        val connector = SimulatedConnector(first, SimulatedSppSocket())
        val diagnostics = ControlDiagnosticsBuffer()
        val transport = BluetoothSppRadioTransport(connector, diagnostics)
        assertTrue(Ic705Controller(null, "test", transport = transport, diagnostics = diagnostics).connect())
        assertTrue(first.closed)
        assertEquals(listOf(true, false), connector.modes)
        assertTrue(diagnostics.exportText().contains("bluetooth/connect-timeout"))
        transport.disconnect()
    }

    @Test fun bothPhysicalConnectionsFailWithReasonsAndNoCivWrites() = runBlocking {
        val first = SimulatedSppSocket(connectError = IOException("first failed"))
        val second = SimulatedSppSocket(connectError = IOException("second failed"))
        val diagnostics = ControlDiagnosticsBuffer()
        val transport = BluetoothSppRadioTransport(SimulatedConnector(first, second), diagnostics)
        assertFalse(Ic705Controller(null, "test", transport = transport, diagnostics = diagnostics).connect())
        assertTrue(first.commands.isEmpty() && second.commands.isEmpty())
        val log = diagnostics.exportText()
        assertTrue(log.contains("first failed") && log.contains("second failed"))
        assertTrue(log.contains("civ/connect/transport-failed"))
        assertFalse(log.contains("civ/connect/probe-attempt"))
    }

    @Test fun successfulHandshakeOnlyReadsFrequencyAndHandlesEchoNoiseAndFragments() = runBlocking {
        val socket = SimulatedSppSocket(echo = true, fragment = true)
        val diagnostics = ControlDiagnosticsBuffer()
        val transport = BluetoothSppRadioTransport(SimulatedConnector(socket), diagnostics)
        val radio = Ic705Controller(null, "test", transport = transport, diagnostics = diagnostics)
        assertTrue(radio.connect())
        assertEquals(listOf(3), socket.commands)
        assertTrue(diagnostics.exportText().contains("civ/connect/ready"))
        assertFalse(diagnostics.exportText().contains("civ/reply/timeout"))
        // Cleanup at the transport level: this test must never send PTT or mode writes.
        transport.disconnect()
    }

    @Test fun missingFirstReplyRetriesReadOnlyProbeOnSameConnection() = runBlocking {
        val socket = SimulatedSppSocket(dropReplies = 1)
        val transport = BluetoothSppRadioTransport(SimulatedConnector(socket))
        assertTrue(Ic705Controller(null, "test", transport = transport).connect())
        assertEquals(listOf(3, 3), socket.commands)
        transport.disconnect()
    }

    @Test fun protocolFailureOnSecureConnectionFallsBackToInsecure() = runBlocking {
        val first = SimulatedSppSocket(dropReplies = 99)
        val second = SimulatedSppSocket()
        val connector = SimulatedConnector(first, second)
        val diagnostics = ControlDiagnosticsBuffer()
        val transport = BluetoothSppRadioTransport(connector, diagnostics)
        assertTrue(Ic705Controller(null, "test", transport = transport, diagnostics = diagnostics).connect())
        assertEquals(listOf(true, false), connector.modes)
        assertEquals(listOf(3, 3, 3), first.commands)
        assertEquals(listOf(3), second.commands)
        assertFalse(first.isConnected)
        val log = diagnostics.exportText()
        assertTrue(log.contains("civ/reply/timeout"))
        assertTrue(log.contains("civ/connect/fallback"))
        assertTrue(log.contains("bluetooth/close"))
        assertTrue(log.contains("origin=app"))
        transport.disconnect()
    }

    @Test fun explicitNgIsDistinguishedFromTimeoutAndIsNotRetriedThreeTimes() = runBlocking {
        val first = SimulatedSppSocket(reject = true)
        val second = SimulatedSppSocket(reject = true)
        val diagnostics = ControlDiagnosticsBuffer()
        val transport = BluetoothSppRadioTransport(SimulatedConnector(first, second), diagnostics)
        assertFalse(Ic705Controller(null, "test", transport = transport, diagnostics = diagnostics).connect())
        assertEquals(listOf(3), first.commands)
        assertEquals(listOf(3), second.commands)
        val log = diagnostics.exportText()
        assertTrue(log.contains("civ/reply/rejected"))
        assertFalse(log.contains("civ/reply/timeout"))
        assertTrue(log.contains("civ/disconnect"))
        assertFalse(transport.isConnected)
    }

    @Test fun wrongAddressAndEchoAloneNeverCountAsSuccessfulHandshake() = runBlocking {
        val first = SimulatedSppSocket(replyAddress = 0xA2, echo = true)
        val second = SimulatedSppSocket(dropReplies = 99, echo = true)
        val diagnostics = ControlDiagnosticsBuffer()
        val transport = BluetoothSppRadioTransport(SimulatedConnector(first, second), diagnostics)
        assertFalse(Ic705Controller(null, "test", transport = transport, diagnostics = diagnostics).connect())
        assertEquals(3, first.commands.size)
        assertEquals(3, second.commands.size)
        assertTrue(diagnostics.exportText().contains("civ/reply/timeout"))
        assertFalse(transport.isConnected)
    }

    @Test fun rfcommConnectFailureIsLoggedAndUsesSecondSocket() = runBlocking {
        val first = SimulatedSppSocket(connectError = IOException("socket refused"))
        val second = SimulatedSppSocket()
        val connector = SimulatedConnector(first, second)
        val diagnostics = ControlDiagnosticsBuffer()
        val transport = BluetoothSppRadioTransport(connector, diagnostics)
        assertTrue(Ic705Controller(null, "test", transport = transport, diagnostics = diagnostics).connect())
        assertEquals(listOf(true, false), connector.modes)
        assertTrue(diagnostics.exportText().contains("bluetooth/connect-failed"))
        assertTrue(diagnostics.exportText().contains("socket refused"))
        assertTrue(first.closed)
        transport.disconnect()
    }

    @Test fun writeFailureRetainsIoReasonAndStillAllowsAlternativeConnection() = runBlocking {
        val first = SimulatedSppSocket(writeError = true)
        val second = SimulatedSppSocket()
        val diagnostics = ControlDiagnosticsBuffer()
        val transport = BluetoothSppRadioTransport(SimulatedConnector(first, second), diagnostics)
        assertTrue(Ic705Controller(null, "test", transport = transport, diagnostics = diagnostics).connect())
        val log = diagnostics.exportText()
        assertTrue(log.contains("bluetooth/write-failed"))
        assertTrue(log.contains("civ/write/failed"))
        assertTrue(first.closed)
        transport.disconnect()
    }

    @Test fun readFailureIsNotMisreportedAsCivTimeout() = runBlocking {
        val first = SimulatedSppSocket(readError = true)
        val second = SimulatedSppSocket()
        val diagnostics = ControlDiagnosticsBuffer()
        val transport = BluetoothSppRadioTransport(SimulatedConnector(first, second), diagnostics)
        assertTrue(Ic705Controller(null, "test", transport = transport, diagnostics = diagnostics).connect())
        val log = diagnostics.exportText()
        assertTrue(log.contains("bluetooth/read-failed"))
        assertTrue(log.contains("civ/read/disconnected"))
        assertFalse(log.contains("civ/reply/timeout"))
        transport.disconnect()
    }

    @Test fun permissionFailureIsExportedWithoutCreatingSockets() = runBlocking {
        val connector = SimulatedConnector(prepareError = SecurityException("BLUETOOTH_CONNECT missing"))
        val diagnostics = ControlDiagnosticsBuffer()
        val transport = BluetoothSppRadioTransport(connector, diagnostics)
        assertFalse(Ic705Controller(null, "test", transport = transport, diagnostics = diagnostics).connect())
        assertTrue(connector.modes.isEmpty())
        assertTrue(diagnostics.exportText().contains("SecurityException"))
        assertTrue(diagnostics.exportText().contains("civ/connect/transport-failed"))
    }

    @Test fun cancellationClosesBlockedSocketAndDoesNotStartFallback() = runBlocking {
        val socket = SimulatedSppSocket(blockConnect = true)
        val connector = SimulatedConnector(socket, SimulatedSppSocket())
        val diagnostics = ControlDiagnosticsBuffer()
        val transport = BluetoothSppRadioTransport(connector, diagnostics)
        val connection = async { Ic705Controller(null, "test", transport = transport, diagnostics = diagnostics).connect() }
        assertTrue(withContext(Dispatchers.IO) { socket.connectStarted.await(2, TimeUnit.SECONDS) })
        connection.cancelAndJoin()
        assertTrue(socket.closed)
        assertEquals(listOf(true), connector.modes)
        assertTrue(diagnostics.exportText().contains("connection cancelled by app"))
    }
}

private class SimulatedConnector(
    vararg sockets: SimulatedSppSocket,
    private val prepareError: Exception? = null
) : SppConnector {
    private val sockets = ArrayDeque(sockets.toList())
    val modes = mutableListOf<Boolean>()
    override fun prepare() { prepareError?.let { throw it } }
    override fun create(secure: Boolean): SppSocket {
        modes += secure
        return sockets.removeFirst()
    }
}

private class SimulatedSppSocket(
    private var dropReplies: Int = 0,
    private val reject: Boolean = false,
    private val echo: Boolean = false,
    private val fragment: Boolean = false,
    private val replyAddress: Int = 0xA4,
    private val connectError: Exception? = null,
    private val writeError: Boolean = false,
    private val readError: Boolean = false,
    private val blockConnect: Boolean = false,
    private val replyDelayMillis: Long = 0L
) : SppSocket {
    @Volatile override var isConnected = false
    @Volatile var closed = false
    val connectStarted = CountDownLatch(1)
    private val closeSignal = CountDownLatch(1)
    val commands = mutableListOf<Int>()
    private val pending = ArrayDeque<Byte>()
    @Volatile private var replyReadyNanos = 0L
    override val input = object : InputStream() {
        override fun available(): Int {
            if (readError) throw IOException("read failed by simulator")
            if (System.nanoTime() < replyReadyNanos) return 0
            return synchronized(pending) { if (fragment) minOf(2, pending.size) else pending.size }
        }
        override fun read(): Int = synchronized(pending) {
            if (pending.isEmpty()) -1 else pending.removeFirst().toInt() and 0xFF
        }
    }
    override val output: OutputStream = object : ByteArrayOutputStream() {
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            if (writeError) throw IOException("write failed by simulator")
            val request = bytes.copyOfRange(offset, offset + count)
            commands += request[4].toInt() and 0xFF
            replyReadyNanos = System.nanoTime() + replyDelayMillis * 1_000_000L
            synchronized(pending) {
                if (echo) {
                    pending.addAll(request.toList())
                    pending.addAll(byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0, 0xA4.toByte(), 0, 0xFD.toByte()).toList())
                }
                if (dropReplies-- > 0) return
                val body = if (reject) byteArrayOf(0xFA.toByte()) else
                    byteArrayOf(3) + IcomCivProtocol.encodeFrequencyBcd(145_500_000L)
                pending.addAll((byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), replyAddress.toByte()) + body + 0xFD.toByte()).toList())
            }
        }
    }
    override fun connect() {
        connectStarted.countDown()
        connectError?.let { throw it }
        if (blockConnect) {
            closeSignal.await()
            throw IOException("connect aborted by close")
        }
        isConnected = true
    }
    override fun close() {
        closed = true
        isConnected = false
        closeSignal.countDown()
    }
}
