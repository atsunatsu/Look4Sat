/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class Ic705CivSessionTest {
    @Test fun pttOffIsNotDelayedByReadResynchronization() = runBlocking {
        val transport = SessionTransport { command, peer ->
            if (command.getOrNull(6) == 0.toByte()) peer.add(reply(0xFB))
        }
        val session = Ic705CivSession(transport, 0xA4.toByte(), ControlDiagnosticsBuffer(), {})
        try {
            assertNull(session.request(IcomCivProtocol.buildPttCommand(true), 0xFB.toByte(), timeoutMillis = 50L))
            assertEquals(0xFB.toByte(), session.request(IcomCivProtocol.buildPttCommand(false), 0xFB.toByte())?.cmd)
            assertEquals(listOf(0x1C, 0x1C), transport.commands)
        } finally { session.close() }
    }

    @Test fun readerKeepsPartialFramesAndDoesNotUseAckAsReadResponse() = runBlocking {
        val transport = SessionTransport { command, peer ->
            peer.add(command) // outgoing echo
            peer.add(reply(0xFB))
            peer.add(reply(0x26, 1, 5, 0, 1)) // wrong VFO selector
            reply(0x26, 0, 1, 0, 1).forEach { peer.add(byteArrayOf(it)) }
        }
        val session = Ic705CivSession(transport, 0xA4.toByte(), ControlDiagnosticsBuffer(), {})
        try {
            val response = session.request(IcomCivProtocol.buildReadVfoModeCommand(true), 0x26) { it.firstOrNull() == 0.toByte() }
            assertEquals(0x26.toByte(), response?.cmd)
            assertArrayEquals(byteArrayOf(0, 1, 0, 1), response?.payload)
        } finally { session.close() }
    }

    @Test fun timedOutPttIsNeverReplayedAndNextSetterUsesReadBarrier() = runBlocking {
        val transport = SessionTransport { command, peer ->
            when (command[4].toInt() and 0xFF) {
                3 -> {
                    peer.add(reply(0xFB)) // late ACK cannot satisfy the read barrier
                    peer.add(reply(3, *IcomCivProtocol.encodeFrequencyBcd(145_500_000L).map { it.toInt() and 0xFF }.toIntArray()))
                }
                5 -> peer.add(reply(0xFB))
            }
        }
        val session = Ic705CivSession(transport, 0xA4.toByte(), ControlDiagnosticsBuffer(), {})
        try {
            assertNull(session.request(IcomCivProtocol.buildPttCommand(true), 0xFB.toByte(), timeoutMillis = 60L))
            assertEquals(0xFB.toByte(), session.request(IcomCivProtocol.buildSetFreqCommand(145_600_000L), 0xFB.toByte())?.cmd)
            assertEquals(listOf(0x1C, 3, 5), transport.commands)
        } finally { session.close() }
    }

    @Test fun idleHeartbeatRequiresThreeFailuresBeforeClosing() = runBlocking {
        val lost = CountDownLatch(1)
        val transport = SessionTransport { _, _ -> }
        val diagnostics = ControlDiagnosticsBuffer()
        val session = Ic705CivSession(transport, 0xA4.toByte(), diagnostics,
            disconnected = { lost.countDown() }, heartbeatMillis = 30L, heartbeatTimeoutMillis = 30L)
        try {
            assertTrue(withContext(Dispatchers.IO) { lost.await(2, TimeUnit.SECONDS) })
            assertEquals(listOf(3, 3, 3), transport.commands)
            assertTrue(diagnostics.exportText().contains("Three consecutive idle CI-V heartbeat failures"))
        } finally { session.close() }
        assertFalse(transport.isConnected)
    }

    @Test fun successfulIdleHeartbeatOnlyQueriesFrequency() = runBlocking {
        val sampled = CountDownLatch(2)
        val lost = CountDownLatch(1)
        val transport = SessionTransport { _, peer ->
            peer.add(reply(3, *IcomCivProtocol.encodeFrequencyBcd(145_500_000L).map { it.toInt() and 0xFF }.toIntArray()))
            sampled.countDown()
        }
        val session = Ic705CivSession(transport, 0xA4.toByte(), ControlDiagnosticsBuffer(),
            disconnected = { lost.countDown() }, heartbeatMillis = 30L, heartbeatTimeoutMillis = 100L)
        try {
            assertTrue(withContext(Dispatchers.IO) { sampled.await(2, TimeUnit.SECONDS) })
            assertEquals(1L, lost.count)
            assertTrue(transport.commands.all { it == 3 })
        } finally { session.close() }
    }

    private fun reply(command: Int, vararg payload: Int) = byteArrayOf(
        0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), command.toByte(),
        *payload.map(Int::toByte).toByteArray(), 0xFD.toByte())
}

private class SessionTransport(private val onWrite: (ByteArray, SessionTransport) -> Unit) : RadioTransport {
    private val input = ConcurrentLinkedQueue<ByteArray>()
    val commands = java.util.Collections.synchronizedList(mutableListOf<Int>())
    @Volatile override var isConnected = true
    fun add(bytes: ByteArray) { input.add(bytes) }
    override suspend fun connect() = true
    override suspend fun disconnect() { isConnected = false }
    override suspend fun write(bytes: ByteArray): Boolean {
        commands += bytes[4].toInt() and 0xFF
        onWrite(bytes, this)
        return true
    }
    override suspend fun readAvailable(maxBytes: Int): ByteArray = input.poll() ?: ByteArray(0)
}
