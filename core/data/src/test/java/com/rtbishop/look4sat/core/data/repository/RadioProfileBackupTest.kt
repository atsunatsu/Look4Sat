package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.repository.*
import org.junit.Assert.*
import org.junit.Test

class RadioProfileBackupTest {
    private fun profile(slot:Int,name:String="TEST",uuid:String="FM",rx:Long=435100000):StoredRadioProfile {
        val raw=ByteArray(64);"L4SM".toByteArray().copyInto(raw);raw[4]=1;raw[6]=3;raw[7]=20;raw[8]=60;raw[9]=4
        val key=radioSatelliteProfileKey(name,uuid)
        key.chunked(2).map {it.toInt(16).toByte()}.toByteArray().copyInto(raw,14)
        fun put(offset:Int,hz:Long){repeat(4){raw[offset+it]=(hz ushr (8*it)).toByte()}}
        put(30,rx);put(34,145900000);put(38,10);put(42,-20)
        name.take(12).toByteArray().copyInto(raw,48)
        val crc=RadioProfileRecordCodec.crc(raw,60);raw[60]=crc.toByte();raw[61]=(crc ushr 8).toByte()
        return checkNotNull(RadioProfileRecordCodec.decode(slot,raw)).copy(metadata=RadioProfileMetadata(name,uuid,"FM Voice"),alias="Alias")
    }
    @Test fun backupRoundTripPreservesRawSettingsNamesAndAliases() {
        val original=listOf(profile(4))
        val json=RadioProfileBackup.encode(original,7)
        val decoded=RadioProfileBackup.decode(json).single()
        assertEquals(original.single().rawHex,decoded.rawHex)
        assertEquals(original.single().metadata,decoded.metadata);assertEquals("Alias",decoded.alias)
        assertFalse(json.contains("calibration"));assertFalse(json.contains("CwPreset"))
    }
    @Test fun mergeKeepsConflictsByDefaultAndNeverDeletesUnmentionedProfiles() {
        val original=profile(3);val other=profile(7,"OTHER")
        val incoming=profile(0,rx=435200000)
        val kept=RadioProfileRecordCodec.merge(listOf(original,other),listOf(incoming),emptySet())
        assertArrayEquals(RadioProfileRecordCodec.bytes(original.rawHex),kept[3])
        assertArrayEquals(RadioProfileRecordCodec.bytes(other.rawHex),kept[7])
        val replaced=RadioProfileRecordCodec.merge(listOf(original,other),listOf(incoming),setOf(original.key))
        assertArrayEquals(RadioProfileRecordCodec.bytes(incoming.rawHex),replaced[3])
        assertArrayEquals(RadioProfileRecordCodec.bytes(other.rawHex),replaced[7])
    }
    @Test fun capacityDuplicateIdentityAndMalformedBackupRejectWholeOperation() {
        val full=(0..55).map {profile(it,"SAT$it")}
        assertThrows(IllegalArgumentException::class.java) {RadioProfileRecordCodec.merge(full,listOf(profile(0,"EXTRA")),emptySet())}
        val one=profile(0)
        assertThrows(IllegalArgumentException::class.java) {RadioProfileBackup.decode(RadioProfileBackup.encode(listOf(one,one),1))}
        val json=RadioProfileBackup.encode(listOf(one),1)
        assertThrows(IllegalArgumentException::class.java) {RadioProfileBackup.decode(json.replace("\"version\": 1","\"version\": 2"))}
        assertThrows(IllegalArgumentException::class.java) {RadioProfileBackup.decode(json.replace("TEST","OTHER"))}
        assertThrows(IllegalArgumentException::class.java) {RadioProfileBackup.decode(" ".repeat(262145))}
        assertThrows(IllegalArgumentException::class.java) {RadioProfileBackup.decode("{\"deep\":"+"[".repeat(17)+"0"+"]".repeat(17)+"}")}
        val bad=one.copy(rawHex="00"+one.rawHex.drop(2))
        assertThrows(IllegalArgumentException::class.java) {RadioProfileBackup.decode(RadioProfileBackup.encode(listOf(bad),1))}
    }
    @Test fun unknownExistingRecordsRemainOccupiedAndCannotBeOverwrittenByImport() {
        val valid=profile(3)
        val unknown=valid.copy(rawHex="00"+valid.rawHex.drop(2),compatible=false)
        val merged=RadioProfileRecordCodec.merge(listOf(unknown),listOf(profile(0,"OTHER")),emptySet())
        assertArrayEquals(RadioProfileRecordCodec.bytes(unknown.rawHex),merged[3])
        assertThrows(IllegalArgumentException::class.java) {RadioProfileRecordCodec.merge(listOf(unknown),listOf(valid),setOf(valid.key))}
    }
}
