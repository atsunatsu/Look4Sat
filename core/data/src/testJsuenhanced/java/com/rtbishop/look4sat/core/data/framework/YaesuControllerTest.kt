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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class YaesuControllerTest {
    @Test fun txStatusReadbackHandlesBothLegacyVariants()=runTest {
        for(variant in YaesuCatVariant.entries) {
            val transport=ScriptedYaesuTransport()
            val radio=Ft817Controller(null,"USB",transport,variant)
            assertTrue(radio.connect());assertTrue(radio.supportsPttReadback)
            assertEquals(false,radio.readPttState())
            assertTrue(radio.pttOn());assertEquals(true,radio.readPttState())
            assertTrue(radio.pttOff());assertEquals(false,radio.readPttState())
        }
    }
    @Test fun failedWriteDoesNotLeaveYaesuFalselyConnected()=runTest {
        val transport=ScriptedYaesuTransport()
        val radio=Ft817Controller(null,"USB",transport)
        assertTrue(radio.connect());transport.disconnect()
        assertFalse(radio.setFrequency(145500000));assertFalse(radio.isConnected)
    }
    @Test
    fun ft817AcceptsUndocumentedAckValueAndConfirmsPttState() = runTest {
        val transport = ScriptedYaesuTransport(ack = 0x55)
        val controller = Ft817Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport,
            variant = YaesuCatVariant.FT817
        )

        assertTrue(controller.connect())
        assertTrue(controller.setFrequency(145_500_000L))
        assertTrue(controller.pttOn())
        assertTrue(controller.pttOff())
        assertEquals(
            listOf(0x03, 0x01, 0x08, 0xF7, 0x88, 0xF7),
            transport.commands
        )
    }

    @Test
    fun ft857UsesSharedLegacyCommandsWithoutTxStatusPolling() = runTest {
        val transport = ScriptedYaesuTransport()
        val controller = Ft817Controller(
            bluetoothManager = null,
            deviceAddress = "TCP",
            transport = transport,
            variant = YaesuCatVariant.FT857
        )

        assertTrue(controller.connect())
        assertTrue(controller.pttOn())
        assertTrue(controller.pttOff())
        assertEquals(listOf(0x03, 0x08, 0x88), transport.commands)
    }

    @Test
    fun missingCommandAckDisablesFutureAckReadsWithoutBreakingCat() = runTest {
        val transport = ScriptedYaesuTransport(ack = null)
        val controller = Ft817Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport,
            variant = YaesuCatVariant.FT817
        )

        assertTrue(controller.connect())
        assertTrue(controller.setFrequency(145_500_000L))
        assertTrue(controller.setMode("USB"))
        assertTrue(controller.pttOn())
        assertEquals(listOf(0x03, 0x01, 0x07, 0x08, 0xF7), transport.commands)
    }
}

private class ScriptedYaesuTransport(private val ack: Int? = 0x00) : RadioTransport {
    private val replies = ArrayDeque<Byte>()
    val commands = mutableListOf<Int>()
    private var transmitting = false
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
        if (!isConnected || bytes.size != 5) return false
        val command = bytes.last().toInt() and 0xFF
        commands += command
        when (command) {
            0x03 -> enqueue(0x14, 0x55, 0x00, 0x00, 0x01)
            0xF7 -> enqueue(if (transmitting) 0x00 else 0xFF)
            else -> {
                if (command == 0x08) transmitting = true
                if (command == 0x88) transmitting = false
                ack?.let { enqueue(it) }
            }
        }
        return true
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray {
        if (maxBytes <= 0 || replies.isEmpty()) return ByteArray(0)
        return ByteArray(minOf(maxBytes, replies.size)) { replies.removeFirst() }
    }

    private fun enqueue(vararg bytes: Int) {
        bytes.forEach { replies += it.toByte() }
    }
}
