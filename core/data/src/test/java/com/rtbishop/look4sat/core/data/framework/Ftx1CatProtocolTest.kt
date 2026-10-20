package com.rtbishop.look4sat.core.data.framework

import org.junit.Assert.*
import org.junit.Test

class Ftx1CatProtocolTest {
    @Test fun frequenciesModesAndCtcssMatchThe2508cTables() {
        assertEquals("FA014250000;",Ftx1CatProtocol.frequency(0,14_250_000))
        assertEquals("FB145950000;",Ftx1CatProtocol.frequency(1,145_950_000))
        assertNull(Ftx1CatProtocol.frequency(0,470_000_001))
        assertEquals("MD14;",Ftx1CatProtocol.mode(1,"FM"))
        assertEquals("MD0C;",Ftx1CatProtocol.mode(0,"USB-D"))
        assertEquals("CN10008;",Ftx1CatProtocol.tone(1,88.5))
        assertEquals("CN00049;",Ftx1CatProtocol.tone(0,254.1))
        assertNull(Ftx1CatProtocol.tone(0,88.4))
        assertNull(Ftx1CatProtocol.mode(0,"unsupported"))
    }
    @Test fun decoderHandlesFragmentationCoalescingEchoesAndOversizedNoise() {
        val decoder=Ftx1CatProtocol.Decoder()
        assertEquals(emptyList<String>(),decoder.feed("FA145".encodeToByteArray()))
        assertEquals(listOf("FA145950000;","TX2;"),decoder.feed("950000;TX2;".encodeToByteArray()))
        assertEquals(listOf("FA;","FA145950000;"),decoder.feed("\r\nFA;FA145950000;".encodeToByteArray()))
        decoder.feed("X".repeat(257).encodeToByteArray());decoder.clear()
        assertEquals(listOf("ID0840;"),decoder.feed("ID0840;".encodeToByteArray()))
        assertNull(Ftx1CatProtocol.parseFrequency("FA14595000;",0))
        assertNull(Ftx1CatProtocol.parseFrequency("FA999999999;",0))
        assertNull(Ftx1CatProtocol.parseFrequency("FB145950000;",0))
    }
}
