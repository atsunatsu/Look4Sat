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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IcomCivProtocolTest {
    @Test fun selectedVfoNarrowFmIncludesDataByteBeforeFilter() {
        assertArrayEquals(civCommand(0x26,0x00,0x05,0x00,0x02),
            IcomCivProtocol.buildSetVfoModeCommand(true,"NFM"))
        assertArrayEquals(civCommand(0x26,0x01,0x05,0x00,0x02),
            IcomCivProtocol.buildSetVfoModeCommand(false,"FM-N"))
    }
    @Test
    fun ic820UsesItsOwnBandAccessAndSplitTableWithoutChangingModernCommands() {
        assertArrayEquals(civCommand(0x07, 0xD1), IcomCivProtocol.buildIc820BandAccessCommand(main = true))
        assertArrayEquals(civCommand(0x07, 0xD0), IcomCivProtocol.buildIc820BandAccessCommand(main = false))
        assertArrayEquals(civCommand(0x0F, 0x00), IcomCivProtocol.buildIc820SplitModeCommand(true))
        assertArrayEquals(civCommand(0x0F, 0x01), IcomCivProtocol.buildIc820SplitModeCommand(false))
        assertArrayEquals(civCommand(0x07, 0xD0), IcomCivProtocol.buildSelectMainCommand())
        assertArrayEquals(civCommand(0x0F, 0x01), IcomCivProtocol.buildSplitModeCommand(true))
    }

    @Test
    fun ic820FrequencyParserAcceptsActualFiveByteBcdAndRejectsAmbiguousLengths() {
        assertEquals(145_580_000L, IcomCivProtocol.parseIc820FrequencyPayload(byteArrayOf(0, 0, 0x58, 0x45, 1)))
        assertEquals(435_100_000L, IcomCivProtocol.parseIc820FrequencyPayload(byteArrayOf(0, 0, 0x10, 0x35, 4)))
        assertNull(IcomCivProtocol.parseIc820FrequencyPayload(byteArrayOf(0, 0, 0x58, 0x45)))
        assertNull(IcomCivProtocol.parseIc820FrequencyPayload(byteArrayOf(0, 0, 0x58, 0x45, 1, 0)))
        assertNull(IcomCivProtocol.parseIc820FrequencyPayload(byteArrayOf(0, 0, 0x5A, 0x45, 1)))
    }

    @Test
    fun frequencyAndModeUseSeparateStandardReadCommands() {
        assertArrayEquals(
            civCommand(0x03),
            IcomCivProtocol.buildReadFreqCommand()
        )
        assertArrayEquals(
            civCommand(0x04),
            IcomCivProtocol.buildReadModeCommand()
        )
    }

    @Test
    fun realFrequencyAndModeFramesAreParsedThroughBroadcastNoise() {
        val noise = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0x00, 0xA4.toByte(), 0x03, 0x11, 0xFD.toByte()
        )
        val frequencyFrame = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0x03,
            0x00, 0x00, 0x59, 0x45, 0x01, 0xFD.toByte()
        )
        val modeFrame = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0x04,
            0x01, 0x02, 0xFD.toByte()
        )

        val frequency = IcomCivProtocol.parseResponse(noise + frequencyFrame, 0x03)
        val mode = IcomCivProtocol.parseResponse(noise + modeFrame, 0x04)

        assertEquals(145_590_000L, IcomCivProtocol.parseFrequencyPayload(frequency!!.payload))
        assertEquals("USB", IcomCivProtocol.parseModePayload(mode!!.payload))
    }

    @Test
    fun selectedVfoModeCommandsCarrySelectorAndReadbackIsVerified() {
        assertArrayEquals(
            civCommand(0x26, 0x00, 0x01),
            IcomCivProtocol.buildSetVfoModeCommand(selected = true, mode = "USB")
        )
        assertArrayEquals(
            civCommand(0x26, 0x01),
            IcomCivProtocol.buildReadVfoModeCommand(selected = false)
        )
        assertEquals(
            "FM",
            IcomCivProtocol.parseVfoModePayload(byteArrayOf(0x01, 0x05, 0x01), selected = false)
        )
        assertNull(
            IcomCivProtocol.parseVfoModePayload(byteArrayOf(0x00, 0x05, 0x01), selected = false)
        )
    }

    @Test
    fun parseFreqModePayloadIc705ReadFreqReply() {
        val reply = bytes(0xFE, 0xFE, 0xE0, 0xA4, 0x03, 0x60, 0x74, 0x95, 0x45, 0x01, 0xFD)
        val response = IcomCivProtocol.parseResponse(reply, IcomCivProtocol.CMD_READ_FREQ)
        assertNotNull(response)
        assertEquals(145957460L to "", IcomCivProtocol.parseFreqModePayload(response!!.payload))
    }

    @Test
    fun parseFreqModePayloadWithModeByte() {
        assertEquals(
            435611000L to "USB",
            IcomCivProtocol.parseFreqModePayload(bytes(0x00, 0x10, 0x61, 0x35, 0x04, 0x01, 0x01))
        )
    }

    @Test
    fun sameCommandBroadcastForOtherVfoIsSkipped() {
        val wrongVfo = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0x25,
            0x01, 0x00, 0x00, 0x00, 0x35, 0x04, 0xFD.toByte()
        )
        val selectedVfo = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(), 0x25,
            0x00, 0x00, 0x00, 0x59, 0x45, 0x01, 0xFD.toByte()
        )

        val response = IcomCivProtocol.parseResponse(wrongVfo + selectedVfo, 0x25) {
            it.firstOrNull() == IcomCivProtocol.SUB_SELECTED_VFO
        }

        assertEquals(
            145_590_000L,
            IcomCivProtocol.parseVfoFrequencyPayload(requireNotNull(response).payload, selected = true)
        )
        assertNull(IcomCivProtocol.parseVfoFrequencyPayload(response.payload, selected = false))
    }

    @Test
    fun malformedFrequencyBcdIsRejected() {
        assertNull(IcomCivProtocol.parseFrequencyPayload(byteArrayOf(0x00, 0x00, 0x5A, 0x45, 0x01)))
    }

    @Test
    fun digitalVoiceUsesIcomDvModeIdentifier() {
        assertArrayEquals(
            civCommand(0x06, 0x17),
            IcomCivProtocol.buildSetModeCommand("DV")
        )
        assertEquals("DV", IcomCivProtocol.parseModePayload(byteArrayOf(0x17)))
    }

    @Test
    fun narrowFmAndDataModeCommandsMatchIcomReference() {
        assertArrayEquals(
            civCommand(0x06, 0x05, 0x02),
            IcomCivProtocol.buildSetModeCommand("FM-N")
        )
        assertArrayEquals(
            civCommand(0x1A, 0x06, 0x01, 0x02),
            IcomCivProtocol.buildDataModeCommand(enabled = true)
        )
        assertArrayEquals(
            civCommand(0x1A, 0x06, 0x00, 0x00),
            IcomCivProtocol.buildDataModeCommand(enabled = false)
        )
        assertEquals("FM", IcomCivProtocol.parseModePayload(byteArrayOf(0x05, 0x02)))
        assertEquals(true, IcomCivProtocol.parseDataModeState(byteArrayOf(0x06, 0x01, 0x02)))
        assertEquals(false, IcomCivProtocol.parseDataModeState(byteArrayOf(0x06, 0x00, 0x00)))
    }

    @Test
    fun ctcssToneUsesThreeByteCivBcd() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x06, 0x70),
            IcomCivProtocol.encodeCtcssToneBcd(67.0)
        )
        assertArrayEquals(
            civCommand(0x1B, 0x00, 0x00, 0x08, 0x85),
            IcomCivProtocol.buildSetCtcssToneCommand(88.5)
        )
        assertThrows(IllegalArgumentException::class.java) {
            IcomCivProtocol.encodeCtcssToneBcd(Double.NaN)
        }
    }

    @Test
    fun `IC-9700 profile accepts responses from A2 only`() {
        val response = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA2.toByte(),
            IcomCivProtocol.ACK_OK, 0xFD.toByte()
        )

        assertTrue(IcomCivProtocol.ackStatus(response, IcomCivProtocol.ADDR_IC9700) == true)
        assertEquals(null, IcomCivProtocol.ackStatus(response, IcomCivProtocol.ADDR_IC705))
    }
    @Test
    fun pttOnAndOffCommandsUseCivTransceiverStatus() {
        assertArrayEquals(
            byteArrayOf(
                0xFE.toByte(), 0xFE.toByte(), 0xA4.toByte(), 0xE0.toByte(),
                0x1C, 0x00, 0x01, 0xFD.toByte()
            ),
            IcomCivProtocol.buildPttCommand(true)
        )
        assertArrayEquals(
            byteArrayOf(
                0xFE.toByte(), 0xFE.toByte(), 0xA4.toByte(), 0xE0.toByte(),
                0x1C, 0x00, 0x00, 0xFD.toByte()
            ),
            IcomCivProtocol.buildPttCommand(false)
        )
    }

    @Test
    fun pttReadCommandAndResponseAreParsed() {
        assertArrayEquals(
            byteArrayOf(
                0xFE.toByte(), 0xFE.toByte(), 0xA4.toByte(), 0xE0.toByte(),
                0x1C, 0x00, 0xFD.toByte()
            ),
            IcomCivProtocol.buildReadPttCommand()
        )
        assertTrue(IcomCivProtocol.parsePttState(byteArrayOf(0x00, 0x01)) == true)
        assertFalse(IcomCivProtocol.parsePttState(byteArrayOf(0x00, 0x00)) == true)
        assertNull(IcomCivProtocol.parsePttState(byteArrayOf(0x01, 0x01)))
        assertNull(IcomCivProtocol.parsePttState(byteArrayOf(0x00, 0x02)))
    }

    @Test
    fun pttReadbackFrameSurvivesBroadcastNoise() {
        val noise = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0x00, 0xA4.toByte(), 0x03, 0xFD.toByte()
        )
        val response = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(),
            0x1C, 0x00, 0x01, 0xFD.toByte()
        )
        val parsed = IcomCivProtocol.parseResponse(noise + response, 0x1C)
        assertEquals(0x1C.toByte(), parsed?.cmd)
        assertArrayEquals(byteArrayOf(0x00, 0x01), parsed?.payload)
    }

    @Test
    fun ackStatusDistinguishesNakFromNoResponse() {
        val ack = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(),
            0xFB.toByte(), 0xFD.toByte()
        )
        val nak = ack.copyOf().also { it[4] = 0xFA.toByte() }

        assertEquals(true, IcomCivProtocol.ackStatus(ack))
        assertEquals(false, IcomCivProtocol.ackStatus(nak))
        assertNull(IcomCivProtocol.ackStatus(byteArrayOf()))
    }

    @Test
    fun ic9700SatelliteModeAndMainSubCommandsMatchHamlib() {
        assertArrayEquals(civCommand(0x07), IcomCivProtocol.buildEnterVfoModeCommand())
        assertArrayEquals(civCommand(0x16, 0x5A, 0x01), IcomCivProtocol.buildSatelliteModeCommand(true))
        assertArrayEquals(civCommand(0x16, 0x5A, 0x00), IcomCivProtocol.buildSatelliteModeCommand(false))
        assertArrayEquals(civCommand(0x16, 0x5A), IcomCivProtocol.buildReadSatelliteModeCommand())
        assertArrayEquals(civCommand(0x07, 0xD0), IcomCivProtocol.buildSelectMainCommand())
        assertArrayEquals(civCommand(0x07, 0xD1), IcomCivProtocol.buildSelectSubCommand())
        assertEquals(true, IcomCivProtocol.parseSatelliteModeState(byteArrayOf(0x5A, 0x01)))
        assertEquals(false, IcomCivProtocol.parseSatelliteModeState(byteArrayOf(0x5A, 0x00)))
        assertNull(IcomCivProtocol.parseSatelliteModeState(byteArrayOf(0x5A, 0x02)))
    }

    @Test
    fun ic910SatelliteModeCommandsMatchHamlib() {
        assertArrayEquals(civCommand(0x1A, 0x07, 0x01), IcomCivProtocol.buildIc910SatelliteModeCommand(true))
        assertArrayEquals(civCommand(0x1A, 0x07, 0x00), IcomCivProtocol.buildIc910SatelliteModeCommand(false))
        assertArrayEquals(civCommand(0x1A, 0x07), IcomCivProtocol.buildReadIc910SatelliteModeCommand())
        assertEquals(
            true,
            IcomCivProtocol.parseSatelliteModeState(
                byteArrayOf(0x07, 0x01),
                IcomCivProtocol.SUB_IC910_SATELLITE_MODE
            )
        )
        assertEquals(0x60.toByte(), IcomCivProtocol.ADDR_IC910)
    }

    private fun civCommand(vararg payload: Int): ByteArray = byteArrayOf(
        0xFE.toByte(),
        0xFE.toByte(),
        0xA4.toByte(),
        0xE0.toByte(),
        *payload.map(Int::toByte).toByteArray(),
        0xFD.toByte()
    )

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun parseFreqModePayload_tooShort() {
        assertNull(IcomCivProtocol.parseFreqModePayload(bytes(0x60, 0x74, 0x95, 0x45)))
    }
}
