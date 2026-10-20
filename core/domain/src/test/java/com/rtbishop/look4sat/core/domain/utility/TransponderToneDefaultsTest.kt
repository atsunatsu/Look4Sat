package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.repository.radioContextKey
import org.junit.Assert.*
import org.junit.Test

class TransponderToneDefaultsTest {
    private fun radio(info:String,mode:String="FM")=SatRadio("voice",info,true,435000000,null,mode,
        145000000,null,mode,false,12345)
    @Test fun explicitSatnogsCtcssAndPlDescriptionsProvideDefaults() {
        assertEquals(67.0,radio("Mode V/U FM Voice CTCSS 67.0 Hz").defaultTxCtcssHz()!!,0.0)
        assertEquals(88.5,radio("FM repeater PL: 88.5").defaultTxCtcssHz()!!,0.0)
        assertEquals(67.0,radio("TX CTCSS 67,0 Hz","FMN").defaultTxCtcssHz()!!,0.0)
    }
    @Test fun receiveAndActivationTonesAreNotUsedForOrdinaryUplink() {
        assertNull(radio("RX CTCSS 88.5").defaultTxCtcssHz())
        assertNull(radio("ARMING CTCSS 74.4 Hz").defaultTxCtcssHz())
        assertEquals(67.0,radio("TX CTCSS 67.0 Hz; ARM CTCSS 74.4 Hz").defaultTxCtcssHz()!!,0.0)
    }
    @Test fun missingAmbiguousInvalidAndNonFmTonesAreNotGuessed() {
        assertNull(radio("SO-50 Voice Repeater").defaultTxCtcssHz())
        assertNull(radio("CTCSS 67.0 or CTCSS 88.5").defaultTxCtcssHz())
        assertNull(radio("CTCSS 67.1").defaultTxCtcssHz())
        assertNull(radio("CTCSS 67.0","USB").defaultTxCtcssHz())
        assertNull(radio("CTCSS 67.0").copy(uplinkLow=null).defaultTxCtcssHz())
    }
    @Test fun contextIdentityIsStableAndIsolatedBetweenTransponders() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223",radioContextKey("abc"))
        assertNotEquals(radioContextKey("voice"),radioContextKey("beacon"))
        assertEquals(50,CtcssTones.tenths.distinct().size)
    }
}
