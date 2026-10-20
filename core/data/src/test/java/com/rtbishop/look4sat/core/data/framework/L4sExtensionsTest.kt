package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.data.repository.RadioProfileBackup
import com.rtbishop.look4sat.core.domain.repository.NativeCwOptions
import com.rtbishop.look4sat.core.domain.repository.RadioProfileRecordCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class L4sExtensionsTest {
    @Test fun extendedRepliesSurviveFragmentationAndBadCrc() {
        for(command in L4sProtocol.CW_OPTIONS..L4sProtocol.PASS_INFO) {
            val body=L4sProtocol.body(command or 0x8000,12,7,byteArrayOf(1))
            val wire=L4sProtocol.frame(body)
            val decoder=L4sProtocol.Decoder()
            val replies=(wire+wire).flatMap {decoder.feed(byteArrayOf(it))}.mapNotNull(L4sProtocol::reply)
            assertEquals(2,replies.size);assertEquals(command,replies.first().command)
            val bad=wire.copyOf().also {it[15]=(it[15].toInt() xor 1).toByte()}
            assertEquals(1,L4sProtocol.Decoder().feed(bad+wire).size)
        }
    }

    @Test fun cwOptionsRejectInvalidLimitsAndRetainAllFields() {
        val bytes=byteArrayOf(1,25,70,4,(-56).toByte(),3,12,1)
        val value=checkNotNull(L4sProtocol.cwOptions(bytes))
        assertEquals(NativeCwOptions(25,70,4,200,3,12,true),value)
        for((index,bad) in listOf(0 to 2,1 to 9,2 to 71,3 to 7,4 to 0,5 to 4,6 to 4,7 to 2))
            assertNull(L4sProtocol.cwOptions(bytes.copyOf().also {it[index]=bad.toByte()}))
        assertNull(L4sProtocol.cwOptions(bytes+0))
    }

    @Test fun diagnosticCountersAreUnsignedAndLengthIsStrict() {
        val p=ByteArray(36);p[0]=1;p[1]=11;p[2]=4;p[3]=6
        for(index in 0..7)L4sProtocol.put32(p,4+index*4,0xfffffff0L+index)
        val value=checkNotNull(L4sProtocol.diagnostics(p))
        assertEquals(0xfffffff0L,value.requests);assertEquals(0xfffffff7L,value.lengthErrors)
        assertTrue(value.summary().contains("crcErrors="));assertEquals(6,value.rssi)
        assertNull(L4sProtocol.diagnostics(p.copyOf(35)))
    }

    @Test fun handsetMacroParserHandlesEightSlotsAndBusyStatusWithoutInventingPresets() {
        for(slot in 0..7) {
            val text="F${slot+1} "+"A".repeat(89)
            val value=checkNotNull(L4sProtocol.macroStatus(macroReply(8,slot,text)))
            assertEquals(text,value.text);assertFalse(value.busy)
        }
        val busy=checkNotNull(L4sProtocol.macroStatus(macroReply(2)))
        assertTrue(busy.waitingForConfirmation);assertEquals(255,busy.slot);assertEquals("",busy.text)
        assertNull(L4sProtocol.macroStatus(macroReply(8,8,"CQ")))
        assertNull(L4sProtocol.macroStatus(macroReply(8,0,"!")))
        assertNull(L4sProtocol.macroStatus(macroReply(8,0,"CQ")+0))
    }

    @Test fun entirePresetBatchIsValidatedBeforeAnyRadioWrite() = runTest {
        var calls=0
        for(presets in listOf(List(7){"CQ"},List(8){if(it==7)"A".repeat(93) else "CQ"},List(8){"!"})) {
            val result=runCatching {HandsetMacroSync.stage(presets,1) {calls++;macroReply(0)}}
            assertTrue(result.isFailure);assertEquals(0,calls)
        }
    }

    @Test fun fullBatchUsesGenerationAndEightSlotsThenWaitsForHandsetConfirmation() = runTest {
        val calls=mutableListOf<ByteArray>()
        val texts=List(8){"CQ F${it+1}"}
        val result=HandsetMacroSync.stage(texts,123) { data ->
            calls+=data
            when(data[0].toInt()) {0->macroReply(0);3->macroReply(2);else->macroReply(1)}
        }
        assertTrue(result.waitingForConfirmation);assertEquals(11,calls.size)
        assertEquals(7L,L4sProtocol.u32(calls[1],1));assertEquals(123L,L4sProtocol.u32(calls[1],5))
        for(index in 0..7) {
            val data=calls[index+2]
            assertEquals(index,data[1].toInt());assertEquals(texts[index],data.copyOfRange(3,data.size).toString(Charsets.US_ASCII))
        }
        assertEquals(3,calls.last()[0].toInt())
        assertTrue(calls.none {it[0].toInt() in setOf(4,5)})
    }

    @Test fun busyOrReadOnlyStorageCannotStartAnUpload() = runTest {
        for(status in listOf(macroReply(2),macroReply(0).also {it[8]=1})) {
            var calls=0
            assertTrue(runCatching {HandsetMacroSync.stage(List(8){"CQ"},1) {calls++;status}}.isFailure)
            assertEquals(1,calls)
        }
    }

    @Test fun versionTwoProfilesRoundTripAndDetectCorruptedCwOptions() {
        val raw=ByteArray(64)
        "L4SM".toByteArray().copyInto(raw);raw[4]=2;raw[6]=3;raw[7]=25;raw[8]=70;raw[9]=4
        L4sProtocol.put32(raw,30,435100000);L4sProtocol.put32(raw,34,145900000)
        raw[46]=45;raw[47]=3;raw[62]=12
        L4sProtocol.put16(raw,60,RadioProfileRecordCodec.crc(raw))
        val profile=checkNotNull(RadioProfileRecordCodec.decode(0,raw))
        assertTrue(profile.compatible)
        val restored=RadioProfileBackup.decode(RadioProfileBackup.encode(listOf(profile),1)).single()
        assertEquals(profile.rawHex,restored.rawHex)
        for(index in listOf(46,47,62))assertFalse(checkNotNull(RadioProfileRecordCodec.decode(0,
            raw.copyOf().also {it[index]=(it[index].toInt() xor 1).toByte()})).compatible)
    }

    private fun macroReply(phase: Int,slot: Int=255,text: String="") = ByteArray(10+text.length).also {
        it[0]=1;it[1]=slot.toByte();it[2]=phase.toByte();L4sProtocol.put32(it,4,7)
        it[9]=text.length.toByte();text.toByteArray(Charsets.US_ASCII).copyInto(it,10)
    }
}
