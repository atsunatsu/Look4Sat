/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IcomCivProtocolTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun parseFreqModePayload_ic705ReadFreqReply() {
        // Reply captured from an IC-705 to CMD 0x03: frequency only, no mode byte
        val reply = bytes(0xFE, 0xFE, 0xE0, 0xA4, 0x03, 0x60, 0x74, 0x95, 0x45, 0x01, 0xFD)
        val response = IcomCivProtocol.parseResponse(reply, IcomCivProtocol.CMD_READ_FREQ)
        assertNotNull(response)
        assertEquals(145957460L to "", IcomCivProtocol.parseFreqModePayload(response!!.payload))
    }

    @Test
    fun parseFreqModePayload_withModeByte() {
        assertEquals(
            435611000L to "USB",
            IcomCivProtocol.parseFreqModePayload(bytes(0x00, 0x10, 0x61, 0x35, 0x04, 0x01, 0x01))
        )
    }

    @Test
    fun parseFreqModePayload_tooShort() {
        assertNull(IcomCivProtocol.parseFreqModePayload(bytes(0x60, 0x74, 0x95, 0x45)))
    }
}
