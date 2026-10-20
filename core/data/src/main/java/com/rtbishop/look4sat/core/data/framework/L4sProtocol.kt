package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.CwTransmitState
import com.rtbishop.look4sat.core.domain.repository.CwTransmitText
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioCapabilities
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioState

internal object L4sProtocol {
    const val HELLO = 0x700
    const val OPEN = 0x701
    const val KEEPALIVE = 0x702
    const val CLOSE = 0x703
    const val STATUS = 0x704
    const val PAIR = 0x705
    const val MODE = 0x706
    const val TONE = 0x707
    const val PTT = 0x708
    const val CW = 0x709
    const val STOP = 0x70A
    const val CONTEXT = 0x70B
    const val SETTINGS = 0x70C
    const val PROFILE = 0x70D
    const val PROFILE_STORE = 0x70E
    const val CW_OPTIONS = 0x70F
    const val MACROS = 0x710
    const val DIAGNOSTICS = 0x711
    const val PASS_INFO = 0x712
    const val APPLIED = 0
    const val ACCEPTED = 1
    private const val MAX_BODY = 120
    private val mask = intArrayOf(0x16,0x6c,0x14,0xe6,0x2e,0x91,0x0d,0x40,0x21,0x35,0xd5,0x40,0x13,0x03,0xe9,0x80)

    data class Reply(val command: Int, val sequence: Int, val session: Long, val result: Int, val data: ByteArray)

    fun u16(p: ByteArray, offset: Int): Int = (p[offset].toInt() and 255) or ((p[offset+1].toInt() and 255) shl 8)
    fun u32(p: ByteArray, offset: Int): Long = u16(p,offset).toLong() or (u16(p,offset+2).toLong() shl 16)
    fun put16(p: ByteArray, offset: Int, n: Int) { p[offset]=n.toByte();p[offset+1]=(n ushr 8).toByte() }
    fun put32(p: ByteArray, offset: Int, n: Long) { put16(p,offset,n.toInt());put16(p,offset+2,(n ushr 16).toInt()) }
    fun crc(p: ByteArray): Int {
        var crc=0
        p.forEach { value ->
            crc=crc xor ((value.toInt() and 255) shl 8)
            repeat(8) { crc=((crc shl 1) xor if(crc and 0x8000 != 0) 0x1021 else 0) and 0xffff }
        }
        return crc
    }

    fun body(command: Int, sequence: Int, session: Long, data: ByteArray = byteArrayOf()): ByteArray =
        ByteArray(12+data.size).also { p ->
            require(p.size <= MAX_BODY)
            put16(p,0,command);put16(p,2,8+data.size);p[4]=1
            put16(p,6,sequence);put32(p,8,session);data.copyInto(p,12)
        }

    fun frame(body: ByteArray): ByteArray {
        require(body.size in 12..MAX_BODY)
        val p=ByteArray(body.size+8)
        p[0]=0xab.toByte();p[1]=0xcd.toByte();put16(p,2,body.size)
        body.copyInto(p,4);put16(p,4+body.size,crc(body))
        for(i in 0 until body.size+2) p[4+i]=(p[4+i].toInt() xor mask[i%16]).toByte()
        p[p.lastIndex-1]=0xdc.toByte();p[p.lastIndex]=0xba.toByte()
        return p
    }

    fun reply(body: ByteArray): Reply? {
        if(body.size < 12 || body[4]!=1.toByte() || u16(body,2)!=body.size-4 || u16(body,0) !in 0x8700..0x8712) return null
        return Reply(u16(body,0) and 0x7fff,u16(body,6),u32(body,8),body[5].toInt() and 255,body.copyOfRange(12,body.size))
    }

    class Decoder {
        private var buffer=byteArrayOf()
        fun feed(chunk: ByteArray): List<ByteArray> {
            buffer=(buffer+chunk).takeLast(4096).toByteArray()
            val result=mutableListOf<ByteArray>()
            while(buffer.size>=4) {
                if(buffer[0]!=0xab.toByte() || buffer[1]!=0xcd.toByte()) { buffer=buffer.copyOfRange(1,buffer.size);continue }
                val size=u16(buffer,2)
                if(size !in 12..MAX_BODY) { buffer=buffer.copyOfRange(1,buffer.size);continue }
                if(buffer.size<size+8) break
                val plain=buffer.copyOfRange(4,size+6)
                for(i in plain.indices) plain[i]=(plain[i].toInt() xor mask[i%16]).toByte()
                val body=plain.copyOfRange(0,size)
                if(buffer[size+6]!=0xdc.toByte() || buffer[size+7]!=0xba.toByte() || crc(body)!=u16(plain,size)) {
                    buffer=buffer.copyOfRange(1,buffer.size);continue
                }
                result+=body;buffer=buffer.copyOfRange(size+8,buffer.size)
            }
            return result
        }
    }

    fun capabilities(data: ByteArray): Pair<String, RemoteRadioCapabilities>? {
        if(data.size!=42 || data.copyOfRange(0,4).toString(Charsets.US_ASCII)!="L4S1" || data[4]!=3.toByte()) return null
        val bits=u32(data,5).toInt()
        val caps=RemoteRadioCapabilities(
            u16(data,9).toLong(),data[11].toInt() and 255,data[12].toInt() and 255,data[13].toInt() and 255,
            u16(data,14),u32(data,16),u32(data,20),data[40].toInt() and 255,data[41].toInt() and 255,
            bits and 1!=0,bits and 2!=0,bits and 4!=0,bits and 8!=0,bits and 16!=0,bits and 32!=0,bits and 64!=0,bits and 128!=0,bits and 256!=0,bits and 512!=0,
            bits and 1024!=0,bits and 2048!=0,bits and 4096!=0,bits and 8192!=0
        )
        if(caps.frequencyStepHz!=10L || caps.maxCwText !in 1..92 || caps.minimumWpm !in 1..45 ||
            caps.maximumWpm !in caps.minimumWpm..45 || caps.watchdogMillis!=1500 || !caps.atomicPair) return null
        return data.copyOfRange(24,40).toString(Charsets.US_ASCII).trimEnd('\u0000') to caps
    }

    fun snapshot(reply: Reply, old: RemoteRadioState): RemoteRadioState? {
        val p=reply.data
        if(p.size!=24 || p[16].toInt() !in 0..3 || p[17].toInt() !in 0..3 ||
            p[19].toInt() !in 0..3 || p[20].toInt() !in 0..5) return null
        return old.copy(
            transmitStateKnown=true,
            session=reply.session, rxFrequencyHz=u32(p,0).takeIf { it>0 },txFrequencyHz=u32(p,4).takeIf { it>0 },
            appliedRevision=u32(p,8),pendingRevision=u32(p,12),rxMode=modeName(p[16].toInt()),
            txMode=modeName(p[17].toInt()),txEnabled=p[18]!=0.toByte(),txSource=p[19].toInt(),
            cwState=CwTransmitState.entries[p[20].toInt()],cwPosition=p[21].toInt() and 255,
            cwLength=p[22].toInt() and 255,error=(p[23].toInt() and 255).takeIf { it!=0 }?.let(::error)
        )
    }

    fun settingsSnapshot(reply: Reply, old: RemoteRadioState): RemoteRadioState? {
        if(reply.result!=APPLIED) return snapshot(reply,old)
        val p=reply.data
        if(p.size!=55 || p[24].toInt() !in 0..3 || p[25].toInt() !in 0..7 || p[26].toInt() !in 10..45 ||
            p[27].toInt() !in 45..120 || p[27].toInt()%5!=0 || p[28].toInt() !in 0..6 || (29..31).any { p[it].toInt() !in 0..50 }) return null
        val tones=listOf(u16(p,32),u16(p,34),u16(p,36))
        if(tones.any { it!=0 && it !in com.rtbishop.look4sat.core.domain.utility.CtcssTones.tenths }) return null
        val base=snapshot(reply.copy(data=p.copyOfRange(0,24)),old) ?: return null
        return base.copy(txCtcssHz=tones[0].takeIf {it!=0}?.div(10.0),rxCtcssHz=tones[1].takeIf {it!=0}?.div(10.0),
            activationCtcssHz=tones[2].takeIf {it!=0}?.div(10.0),nativePower=p[25].toInt(),nativeCwWpm=old.nativeCwOptions?.wpm ?: p[26].toInt(),
            nativeMenuOpen=p[38].toInt() and 1 !=0,settingsSequence=reply.sequence,
            nativeSettingsModified=p[38].toInt() and 8 !=0,nativeProfileValid=p[38].toInt() and 16 !=0,
            nativeProfileKey=p.copyOfRange(39,55).joinToString("") { "%02x".format(it.toInt() and 255) },
            nativeSavedMode=p[24].toInt(),nativeSavedTxCtcssHz=(p[29].toInt()).takeIf {it>0}
                ?.let { com.rtbishop.look4sat.core.domain.utility.CtcssTones.tenths[it-1]/10.0 })
    }

    fun profileSnapshot(reply: Reply, old: RemoteRadioState): RemoteRadioState? {
        if(reply.result!=APPLIED) return snapshot(reply,old)
        val p=reply.data
        if(p.size!=79 || p[55].toInt() !in 0..9) return null
        val rx=u32(p,56);val tx=u32(p,60)
        if(listOf(rx,tx).any { it!=0L && it !in 18_000_000L..1_300_000_000L }) return null
        val rxCorrection=u32(p,64).toInt().toLong();val txCorrection=u32(p,68).toInt().toLong()
        val step=u16(p,72).toLong()
        if(rxCorrection !in -100_000L..100_000L || txCorrection !in -100_000L..100_000L || step !in 10..10_000 || step%10!=0L) return null
        val base=settingsSnapshot(reply.copy(data=p.copyOfRange(0,55)),old) ?: return null
        return base.copy(nativeBaseFrequencies=rx.takeIf { it>0 }?.let {
            com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(it,tx.takeIf { value->value>0 }) },
            nativeRxCorrectionHz=rxCorrection,nativeTxCorrectionHz=txCorrection,nativeSquelch=p[55].toInt(),
            nativeStepHz=step,nativeProfileGeneration=u32(p,74),nativeProfileCapacity=p[78].toInt() and 255)
    }
    fun storeStatus(data:ByteArray):com.rtbishop.look4sat.core.domain.repository.RadioProfileStoreStatus? {
        if(data.size !in setOf(23,88) || data[0]!=1.toByte())return null
        fun u(i:Int)=data[i].toInt() and 255
        if(u(1)>11 || u(2)>100 || u(5)>56 || u(6)!=56 || u(16)>56 || u16(data,17) !in 10..1000 || u16(data,17)%10!=0)return null
        return com.rtbishop.look4sat.core.domain.repository.RadioProfileStoreStatus(u(1),u(2),u(3),u(4) and 1!=0,
            u(4) and 2!=0,u(4) and 4!=0,u(5),u(6),u32(data,8),u32(data,12),u(16),u16(data,17).toLong(),u(19),u(20),u(21),u(22),u(4) and 8!=0)
    }

    fun cwOptions(p: ByteArray): com.rtbishop.look4sat.core.domain.repository.NativeCwOptions? {
        if (p.size != 8 || p[0] != 1.toByte() || p[7].toInt() !in 0..1) return null
        fun u(i: Int) = p[i].toInt() and 255
        return com.rtbishop.look4sat.core.domain.repository.NativeCwOptions(u(1), u(2), u(3), u(4), u(5), u(6), u(7) != 0)
            .takeIf { it.valid() }
    }

    fun macroStatus(p: ByteArray): com.rtbishop.look4sat.core.domain.repository.HandsetMacroStatus? {
        if (p.size < 10 || p[0] != 1.toByte()) return null
        fun u(i: Int) = p[i].toInt() and 255
        if ((u(1) !in 0..7 && u(1) != 255) || u(2) > 10 || u(8) > 1 || u(9) > 92 || p.size != 10 + u(9)) return null
        val text = p.copyOfRange(10, p.size).toString(Charsets.US_ASCII)
        if (runCatching { CwTransmitText.normalize(text) }.isFailure && text.isNotEmpty()) return null
        return com.rtbishop.look4sat.core.domain.repository.HandsetMacroStatus(u(2), u(3), u32(p, 4), u(8) != 0, u(1), text)
    }

    fun diagnostics(p: ByteArray): com.rtbishop.look4sat.core.domain.repository.NativeRadioDiagnostics? {
        if (p.size != 36 || p[0] != 1.toByte()) return null
        return com.rtbishop.look4sat.core.domain.repository.NativeRadioDiagnostics(
            p[1].toInt() and 255, p[2].toInt() and 255, p[3].toInt() and 255,
            u32(p,4), u32(p,8), u32(p,12), u32(p,16), u32(p,20), u32(p,24), u32(p,28), u32(p,32))
    }

    fun modeNumber(mode: String): Int? = when(mode.uppercase(java.util.Locale.ROOT)) {
        "FM", "FMN", "NFM", "FM-N" -> 0
        "AM" -> 1
        "USB" -> 2
        "CW" -> 3
        else -> null
    }
    fun modeName(mode: Int): String = listOf("FM","AM","USB","CW")[mode]
    fun error(code: Int): String = when(code) {
        2 -> "Invalid L4S command length"
        3 -> "L4S session closed or expired; reconnect"
        4 -> "Radio is busy (use main screen, stop local TX/USB paddle mode)"
        5 -> "Frequency, tone or CW speed outside radio limits"
        6 -> "Unsupported radio mode or L4S command"
        7 -> "Radio rejected TX (check TX lock, battery, busy channel and TOT)"
        8 -> "Remote control cancelled by local operation"
        9 -> "Stale L4S command or frequency revision"
        10 -> "Apply frequencies and modes before transmitting"
        11 -> "L4S watchdog/TOT stopped transmission"
        12 -> "Radio profile storage or verification failed"
        13 -> "Radio satellite profile storage is full; no old profile was overwritten"
        else -> "L4S error $code"
    }
}
