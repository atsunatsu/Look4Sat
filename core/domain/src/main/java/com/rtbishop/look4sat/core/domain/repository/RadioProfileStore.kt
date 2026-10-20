package com.rtbishop.look4sat.core.domain.repository

data class RadioProfileStoreStatus(
    val phase:Int=0,val progress:Int=0,val error:Int=0,val busy:Boolean=false,val readOnly:Boolean=false,
    val ioPending:Boolean=false,val used:Int=0,val capacity:Int=56,val generation:Long=0,val transaction:Long=0,
    val nextSlot:Int=0,val correctionStepHz:Long=10,val kind:Int=0,val added:Int=0,val replaced:Int=0,val deleted:Int=0,
    val dirty:Boolean=false
) {
    val waitingForConfirmation get()=phase==6
    val complete get()=phase==9
}

data class RadioProfileMetadata(val satelliteName:String,val transponderUuid:String,val description:String="")

data class StoredRadioProfile(val slot:Int,val rawHex:String,val key:String,val label:String,val compatible:Boolean,
    val metadata:RadioProfileMetadata?=null,val alias:String?=null) {
    val displayName get()=alias?.takeIf {it.isNotBlank()} ?: metadata?.let {"${it.satelliteName} · ${it.description}"}
        ?: "$label · ${key.take(8)}"
}

data class RadioProfileManagerState(val loading:Boolean=false,val status:RadioProfileStoreStatus?=null,
    val profiles:List<StoredRadioProfile> = emptyList(),val importProfiles:List<StoredRadioProfile> = emptyList(),
    val error:String?=null,val message:String?=null,val canCancel:Boolean=false)

object RadioProfileRecordCodec {
    fun crc(bytes:ByteArray,size:Int=bytes.size):Int {
        var value=0
        for(index in 0 until size){value=value xor ((bytes[index].toInt() and 255) shl 8)
            repeat(8){value=((value shl 1) xor if(value and 0x8000!=0)0x1021 else 0) and 65535}}
        return value
    }
    fun bytes(hex:String):ByteArray {
        require(hex.length==128 && hex.all {it in '0'..'9'||it.lowercaseChar() in 'a'..'f'}) {"Invalid profile record"}
        return hex.chunked(2).map {it.toInt(16).toByte()}.toByteArray()
    }
    fun read32(p:ByteArray,offset:Int):Long=(0..3).fold(0L){v,i->v or ((p[offset+i].toLong() and 255) shl (8*i))}
    fun decode(slot:Int,raw:ByteArray):StoredRadioProfile? {
        require(slot in 0..55 && raw.size==64)
        if(raw.all {it==(-1).toByte()})return null
        fun u(index:Int)=raw[index].toInt() and 255
        val storedCrc=u(60)+(u(61) shl 8)
        val versionValid=when(u(4)) {
            1 -> u(46)==0 && u(47)==0 && u(62)==0 && u(63)==0 && crc(raw,60)==storedCrc
            2 -> u(46) in 1..200 && u(47) in 0..3 && (u(62)==0 || u(62) in 5..44) && u(63)==0 &&
                crc(raw.copyOf().also {it[60]=0;it[61]=0})==storedCrc
            else -> false
        }
        val valid=raw.copyOfRange(0,4).toString(Charsets.US_ASCII)=="L4SM" && versionValid &&
            u(5)<=3 && u(6)<=7 && u(7) in 10..45 && u(8) in 45..120 && u(8)%5==0 && u(9)<=6 &&
            (10..12).all {u(it)<=50} && u(13)<=9 && listOf(30,34).all {read32(raw,it).let {v->v==0L||v in 18000000L..1300000000L}} &&
            listOf(38,42).all {read32(raw,it).toInt() in -100000..100000}
        fun hex(p:ByteArray)=p.joinToString(""){"%02x".format(it.toInt() and 255)}
        val label=raw.copyOfRange(48,60).takeWhile {it!=0.toByte()}.map {if(it.toInt() in 32..126)it.toInt().toChar() else '?'}.joinToString("")
        return StoredRadioProfile(slot,hex(raw),hex(raw.copyOfRange(14,30)),label.ifBlank {"Unknown"},valid)
    }
    fun merge(existing:List<StoredRadioProfile>,incoming:List<StoredRadioProfile>,overwrite:Set<String>):List<ByteArray> {
        require(incoming.size<=56 && incoming.map {it.key}.distinct().size==incoming.size){"Duplicate profiles in backup"}
        val slots=MutableList(56){ByteArray(64){-1}}
        existing.forEach {slots[it.slot]=bytes(it.rawHex)}
        incoming.forEach {profile ->
            require(profile.compatible){"Unsupported or damaged profile"}
            val old=existing.find {it.key==profile.key}
            if(old!=null) {
                require(old.compatible || old.rawHex==profile.rawHex){"Cannot overwrite an unknown record"}
                if(profile.key in overwrite)slots[old.slot]=bytes(profile.rawHex)
            } else {
                val slot=slots.indexOfFirst {record->record.all {it==(-1).toByte()}}
                require(slot>=0){"Profile store is full; no existing profile was removed"}
                slots[slot]=bytes(profile.rawHex)
            }
        }
        return slots
    }
}
