package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class L4sControllerTest {
    @Test fun sharedFirmwareVectorsAndCrcMatchKotlinCodec() {
        val text=checkNotNull(javaClass.getResourceAsStream("/l4s-v1-vectors.json")).bufferedReader().use { it.readText() }
        Regex("\"body\":\"([0-9a-f]+)\",\"frame\":\"([0-9a-f]+)\"").findAll(text).forEach { match ->
            val body=hex(match.groupValues[1]);val wire=hex(match.groupValues[2])
            assertArrayEquals(wire,L4sProtocol.frame(body))
            val decoder=L4sProtocol.Decoder()
            val output=wire.flatMap { decoder.feed(byteArrayOf(it)) }
            assertEquals(1,output.size);assertArrayEquals(body,output.single())
        }
        assertEquals(0x31c3,L4sProtocol.crc("123456789".toByteArray()))
    }

    @Test fun corruptNoiseFramesCannotProduceReplies() {
        val body=L4sProtocol.body(0x8704,1,7,ByteArray(24))
        val good=L4sProtocol.frame(body)
        val bad=good.copyOf().also { it[9]=(it[9].toInt() xor 1).toByte() }
        val decoded=L4sProtocol.Decoder().feed(byteArrayOf(0,1,0xab.toByte(),0xcd.toByte(),0xff.toByte(),0xff.toByte())+bad+good+good)
        assertEquals(2,decoded.size)
        decoded.forEach { assertArrayEquals(body,it) }
    }

    @Test fun handshakeConfiguresAtomicReadbackAndNoSsbtTransmit()=runTest {
        val transport=ScriptedL4sTransport()
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler),tuningStepHz={10}) { 7 }
        assertTrue(radio.connect())
        assertEquals("mock-v1",radio.remoteState.value.firmware)
        assertTrue(transport.dtrOnConnect)
        assertEquals(10,radio.remoteState.value.capabilities!!.frequencyStepHz)
        assertFalse(radio.setSplitModes("USB","USB"))
        assertTrue(radio.setSplitModes("FM","FM"))
        assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100006,145900004))
        assertEquals(435100010L,radio.readWorkingFrequency())
        assertEquals(145900000L,radio.readTxVfoFrequency())
        val writes=transport.commands.count { it==L4sProtocol.PAIR }
        assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100007,145900003))
        assertEquals(writes,transport.commands.count { it==L4sProtocol.PAIR })
        radio.disconnect()
        assertFalse(radio.isConnected)
    }

    @Test fun writeAckWithoutActualFrequencyIsRejected()=runTest {
        val transport=ScriptedL4sTransport(ignorePair=true)
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) { 7 }
        assertTrue(radio.connect())
        assertEquals(FrequencyPairResult.FAILED,radio.setTrackingFrequencies(435100000,145900000))
        assertFalse(radio.pttOn())
        radio.disconnect()
    }

    @Test fun transmissionBlocksBothLegsAndStopForcesNewRevisionBeforeRekey()=runTest {
        val transport=ScriptedL4sTransport()
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) { 7 }
        assertTrue(radio.connect())
        assertTrue(radio.setSplitModes("FM","FM"))
        assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100000,145900000))
        assertTrue(radio.pttOn())
        val writes=transport.commands.count { it==L4sProtocol.PAIR }
        assertEquals(FrequencyPairResult.HELD,radio.setTrackingFrequencies(435100100,145900100))
        assertEquals(writes,transport.commands.count { it==L4sProtocol.PAIR })
        assertTrue(radio.pttOff())
        val revision=radio.remoteState.value.appliedRevision
        assertTrue(radio.pttOn())
        assertTrue(radio.remoteState.value.appliedRevision>revision)
        radio.disconnect()
    }

    @Test fun queuedFrequencyReplyCannotArmTransmission()=runTest {
        val transport=ScriptedL4sTransport(acceptPairOnly=true)
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) { 7 }
        assertTrue(radio.connect())
        assertTrue(radio.setSplitModes("FM","FM"))
        assertEquals(FrequencyPairResult.FAILED,radio.setTrackingFrequencies(435100000,145900000))
        assertFalse(radio.pttOn())
        assertFalse(transport.commands.contains(L4sProtocol.PTT))
        radio.disconnect()
    }

    @Test fun lostCwAcknowledgementRetriesSameSequenceWithoutDuplicateSend()=runTest {
        val transport=ScriptedL4sTransport(dropFirstCwReply=true)
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) { 7 }
        assertTrue(radio.connect())
        assertTrue(radio.setSplitModes("CW","CW"))
        assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100000,145900000))
        assertTrue(radio.startCw("CQ DE TEST",20))
        assertEquals(1,transport.cwStarts)
        assertFalse(radio.setWorkingFrequency(435100100))
        assertTrue(radio.pttOff())
        radio.disconnect()
    }

    @Test fun heartbeatClosureMarksConnectionLostAndNeverAutoRekeys()=runTest {
        val transport=ScriptedL4sTransport()
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) { 7 }
        assertTrue(radio.connect())
        transport.closeSession=true
        advanceTimeBy(600);runCurrent()
        assertFalse(radio.isConnected)
        assertTrue(radio.remoteState.value.error!!.contains("session"))
        assertFalse(transport.commands.contains(L4sProtocol.PTT))
        radio.disconnect()
    }

    private fun hex(value:String)=value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun transientWriteFailureRetriesTheExactSameCwRequest()=runTest {
        val transport=ScriptedL4sTransport()
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) {7}
        assertTrue(radio.connect());assertTrue(radio.setSplitModes("CW","CW"))
        assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100000,145900000))
        transport.failCommand=L4sProtocol.CW;transport.failedWrites=1
        assertTrue(radio.startCw("TEST",20))
        val requests=transport.requests.filter {L4sProtocol.u16(it,0)==L4sProtocol.CW}
        assertEquals(2,requests.size);assertArrayEquals(requests[0],requests[1]);assertEquals(1,transport.cwStarts)
        assertTrue(radio.isConnected);radio.disconnect()
    }

    @Test fun persistentWriteFailureMarksDisconnectedAndNeverRekeys()=runTest {
        val transport=ScriptedL4sTransport()
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) {7}
        assertTrue(radio.connect());transport.failCommand=L4sProtocol.PAIR;transport.failedWrites=2
        assertEquals(FrequencyPairResult.FAILED,radio.setTrackingFrequencies(435100000,145900000))
        assertFalse(radio.isConnected);assertFalse(transport.isConnected)
        assertTrue(radio.remoteState.value.error!!.contains("attached=false"))
        assertFalse(radio.pttOn());assertFalse(transport.commands.contains(L4sProtocol.PTT))
        radio.disconnect()
    }

    @Test fun settingsReadbackLoadsNativePreferencesAndMenuState()=runTest {
        val transport=ScriptedL4sTransport(nativeSettings=true)
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) {7}
        assertTrue(radio.connect())
        assertEquals(67.0,radio.remoteState.value.txCtcssHz!!,0.0)
        assertEquals(744.0/10,radio.remoteState.value.activationCtcssHz!!,0.0)
        assertEquals(25,radio.remoteState.value.nativeCwWpm)
        assertTrue(radio.remoteState.value.nativeProfileValid)
        transport.menuOpen=true;advanceTimeBy(1400);runCurrent()
        assertTrue(radio.remoteState.value.nativeMenuOpen)
        radio.disconnect()
    }

    @Test fun oldFirmwareDoesNotReceiveOptionalDisplayCommand()=runTest {
        val transport=ScriptedL4sTransport()
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) { 7 }
        assertTrue(radio.connect())
        assertTrue(radio.updateTrackingDisplay(com.rtbishop.look4sat.core.domain.repository.RadioTrackingDisplay("ISS",137.0,34.0)))
        assertFalse(transport.commands.contains(L4sProtocol.CONTEXT))
        radio.disconnect()
    }

    @Test fun displayContextUsesCapabilityAndCanUpdateDuringTxWithoutTuning()=runTest {
        val transport=ScriptedL4sTransport(displayContext=true)
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) { 7 }
        assertTrue(radio.connect());assertTrue(radio.setSplitModes("FM","FM"))
        assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100000,145900000))
        assertTrue(radio.pttOn())
        val writes=transport.commands.count { it==L4sProtocol.PAIR }
        assertTrue(radio.updateTrackingDisplay(com.rtbishop.look4sat.core.domain.repository.RadioTrackingDisplay("DOSAAF-85",137.0,-34.0)))
        val context=transport.requests.last { L4sProtocol.u16(it,0)==L4sProtocol.CONTEXT }
        assertEquals(1370,L4sProtocol.u16(context,12))
        assertEquals((-340).toShort(),L4sProtocol.u16(context,14).toShort())
        assertEquals("DOSAAF-85",context.copyOfRange(16,32).toString(Charsets.US_ASCII).trimEnd('\u0000'))
        assertEquals(writes,transport.commands.count { it==L4sProtocol.PAIR })
        assertEquals(1,radio.remoteState.value.txSource)
        radio.disconnect()
    }

    @Test fun localPttStatusKeepsConnectionAndReleaseRequiresFreshPair()=runTest {
        val transport=ScriptedL4sTransport()
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) { 7 }
        assertTrue(radio.connect());assertTrue(radio.setSplitModes("FM","FM"))
        assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100000,145900000))
        val revision=radio.remoteState.value.appliedRevision
        transport.localPtt(true);advanceTimeBy(500);runCurrent()
        assertTrue(radio.isConnected);assertEquals(3,radio.remoteState.value.txSource)
        assertEquals(FrequencyPairResult.HELD,radio.setTrackingFrequencies(435100100,145900100))
        transport.localPtt(false);advanceTimeBy(500);runCurrent()
        assertTrue(radio.isConnected)
        assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100000,145900000))
        assertTrue(radio.remoteState.value.appliedRevision>revision)
        radio.disconnect()
    }

    @Test fun default250HzStepAndLiveConfiguredStepQuantizeBothLegs()=runTest {
        var step=250L
        val transport=ScriptedL4sTransport()
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler),tuningStepHz={step}) {7}
        assertTrue(radio.connect());assertTrue(radio.setSplitModes("FM","FM"))
        assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100125,145900624))
        assertEquals(435100250L,radio.remoteState.value.rxFrequencyHz)
        assertEquals(145900500L,radio.remoteState.value.txFrequencyHz)
        step=1000
        assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100125,145900624))
        assertEquals(435100000L,radio.remoteState.value.rxFrequencyHz)
        assertEquals(145901000L,radio.remoteState.value.txFrequencyHz)
        radio.disconnect()
    }

    @Test fun profileReadbackCorrelatesFullNameAndRequiresFreshPairAfterSave()=runTest {
        val transport=ScriptedL4sTransport(profiles=true)
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) {7}
        assertTrue(radio.connect());assertTrue(radio.selectSatelliteProfile("SATELLITE LONG NAME","FM"))
        assertEquals(com.rtbishop.look4sat.core.domain.repository.radioSatelliteProfileKey("SATELLITE LONG NAME","FM"),radio.remoteState.value.nativeProfileKey)
        assertTrue(radio.setSplitModes("FM","FM"))
        assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100006,145900004))
        assertEquals(435099500L,radio.remoteState.value.rxFrequencyHz)
        assertEquals(145900750L,radio.remoteState.value.txFrequencyHz)
        val writes=transport.commands.count {it==L4sProtocol.PAIR}
        transport.profileGeneration++
        advanceTimeBy(1600);runCurrent()
        assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100006,145900004))
        assertEquals(writes+1,transport.commands.count {it==L4sProtocol.PAIR})
        radio.disconnect()
    }

    @Test fun singleLegSettersReuseOnlyRawTargetsAndNeverAccumulateCorrections()=runTest {
        val transport=ScriptedL4sTransport(profiles=true)
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) {7}
        assertTrue(radio.connect());assertTrue(radio.selectSatelliteProfile("TEST","FM"))
        assertFalse(radio.setTxVfoFrequency(145900000))
        assertTrue(radio.setWorkingFrequency(435100000))
        repeat(4){
            assertTrue(radio.setTxVfoFrequency(145900000));assertEquals(435099500L,radio.remoteState.value.rxFrequencyHz)
            assertTrue(radio.setWorkingFrequency(435100000));assertEquals(145900750L,radio.remoteState.value.txFrequencyHz)
        }
        assertTrue(radio.selectSatelliteProfile("OTHER","FM"))
        assertFalse(radio.setTxVfoFrequency(145900000));radio.disconnect()
    }

    @Test fun stalePhoneBaseCannotOverwriteNewHandsetSave()=runTest {
        val transport=ScriptedL4sTransport(profiles=true)
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler)) {7}
        assertTrue(radio.connect());assertTrue(radio.selectSatelliteProfile("SATELLITE","FM"))
        transport.profileGeneration++;transport.baseRx=435200000
        assertFalse(radio.updateTrackingBases(com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435150000,145900000)))
        assertEquals(435200000L,transport.baseRx)
        assertEquals(435200000L,radio.remoteState.value.nativeBaseFrequencies!!.rxHz)
        assertTrue(radio.updateTrackingBases(com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435150000,145900000)))
        assertEquals(435150000L,radio.remoteState.value.nativeBaseFrequencies!!.rxHz)
        radio.disconnect()
    }

    @Test fun noHandshakeReplyReportsCommandAndReceivedByteCount()=runTest {
        val transport=ScriptedL4sTransport(silent=true)
        val diagnostics=ControlDiagnosticsBuffer()
        val radio=L4sController(transport,backgroundScope,StandardTestDispatcher(testScheduler),diagnostics) { 7 }
        assertFalse(radio.connect())
        assertTrue(radio.remoteState.value.error!!.contains("HELLO timed out (RX 0 bytes)"))
        assertTrue(diagnostics.events.value.any { it.stage=="l4s/timeout" && it.message.contains("HELLO") })
        assertEquals(listOf(L4sProtocol.HELLO,L4sProtocol.HELLO),transport.commands)
        assertFalse(transport.commands.contains(L4sProtocol.PTT))
        radio.disconnect()
    }
}

private class ScriptedL4sTransport(
    private val profiles:Boolean=false,
    private val nativeSettings:Boolean=false,
    private val displayContext:Boolean=false,
    private val silent:Boolean=false,
    private val ignorePair:Boolean=false,
    private val acceptPairOnly:Boolean=false,
    private var dropFirstCwReply:Boolean=false
):RadioTransport {
    var failCommand:Int?=null
    var failedWrites=0
    var menuOpen=false
    var profileGeneration=1L
    var baseRx=435100000L
    private var baseTx=145900000L
    private var step=250
    private var profileKey=com.rtbishop.look4sat.core.domain.repository.radioContextKey("test-transponder")
    override val lastIoFailure get()="mock bulk OUT result=-1 attached=false"
    var dtrOnConnect=false
        private set
    override fun setCdcDtrOnConnect(enabled:Boolean) { dtrOnConnect=enabled }
    override var isConnected=false
    var closeSession=false
    val commands=mutableListOf<Int>()
    val requests=mutableListOf<ByteArray>()
    var cwStarts=0
    private var session=0L
    private var rx=145000000L
    private var tx=0L
    private var revision=0L
    private var rxMode=0
    private var txMode=0
    private var txState=0
    fun localPtt(enabled:Boolean) { txState=if(enabled)3 else 0; if(!enabled)pairReady=false }
    private var cwState=0
    private var pairReady=false
    private var modeReady=false
    private var wire=byteArrayOf()
    private val retries=mutableMapOf<Int,ByteArray>()
    override suspend fun connect():Boolean { isConnected=true;return true }
    override suspend fun disconnect() { isConnected=false;wire=byteArrayOf() }
    override suspend fun write(bytes:ByteArray):Boolean {
        val p=L4sProtocol.Decoder().feed(bytes).single()
        val command=L4sProtocol.u16(p,0);val seq=L4sProtocol.u16(p,6)
        commands+=command
        requests+=p
        if(command==failCommand && failedWrites>0) {failedWrites--;return false}
        // Matches the production firmware's DTR gate, which TCP-only tests did not model.
        if(silent || !dtrOnConnect) return true
        retries[seq]?.let { wire+=it;return true }
        var result=0
        var data:ByteArray
        if(command==L4sProtocol.HELLO) {
            data=ByteArray(42)
            "L4S1".toByteArray().copyInto(data);data[4]=3
            L4sProtocol.put32(data,5,if(profiles)463 else if(nativeSettings)207 else if(displayContext)79 else 15);L4sProtocol.put16(data,9,10)
            data[11]=92;data[12]=10;data[13]=45;L4sProtocol.put16(data,14,1500)
            L4sProtocol.put32(data,16,18000000);L4sProtocol.put32(data,20,1300000000)
            "mock-v1".toByteArray().copyInto(data,24);data[40]=15;data[41]=9
        } else {
            when(command) {
                L4sProtocol.OPEN -> session=L4sProtocol.u32(p,8)
                L4sProtocol.KEEPALIVE -> if(closeSession) { session=0;txState=0;result=3 }
                L4sProtocol.CLOSE -> { session=0;txState=0 }
                L4sProtocol.PROFILE -> when(p[12].toInt()) {
                    0-> {profileKey=p.copyOfRange(13,29).joinToString("") {"%02x".format(it.toInt() and 255)};pairReady=false}
                    1-> if(L4sProtocol.u32(p,23)!=profileGeneration) result=9 else {
                        baseRx=L4sProtocol.u32(p,13);baseTx=L4sProtocol.u32(p,17);step=L4sProtocol.u16(p,21);pairReady=false
                    }
                }
                L4sProtocol.MODE -> { rxMode=p[12].toInt();txMode=p[13].toInt();modeReady=true }
                L4sProtocol.PAIR -> if(acceptPairOnly) result=L4sProtocol.ACCEPTED
                else if(!ignorePair) {
                    rx=L4sProtocol.u32(p,12);tx=L4sProtocol.u32(p,16);revision=L4sProtocol.u32(p,21);pairReady=true
                }
                L4sProtocol.PTT -> if(pairReady && modeReady) txState=1 else result=10
                L4sProtocol.CW -> if(pairReady && modeReady) { txState=2;cwState=1;cwStarts++;result=1 } else result=10
                L4sProtocol.STOP -> { txState=0;cwState=4;pairReady=false }
            }
            data=ByteArray(if(command==L4sProtocol.PROFILE && result==0)79 else if(command==L4sProtocol.SETTINGS)55 else 24)
            L4sProtocol.put32(data,0,rx);L4sProtocol.put32(data,4,tx);L4sProtocol.put32(data,8,revision)
            data[16]=rxMode.toByte();data[17]=txMode.toByte();data[18]=if(tx!=0L)1 else 0
            data[19]=txState.toByte();data[20]=cwState.toByte()
            if(data.size>=55) {
                data[24]=0;data[25]=3;data[26]=25;data[27]=60;data[28]=4;data[29]=1;data[30]=0;data[31]=4
                L4sProtocol.put16(data,32,if(txMode==0)670 else 0);L4sProtocol.put16(data,36,744)
                data[38]=(16 or if(menuOpen)1 else 0).toByte()
                profileKey.chunked(2)
                    .map {it.toInt(16).toByte()}.toByteArray().copyInto(data,39)
            }
            if(data.size==79) {
                data[55]=3;L4sProtocol.put32(data,56,baseRx);L4sProtocol.put32(data,60,baseTx)
                L4sProtocol.put32(data,64,-500);L4sProtocol.put32(data,68,750)
                L4sProtocol.put16(data,72,step);L4sProtocol.put32(data,74,profileGeneration);data[78]=56
            }
        }
        val reply=L4sProtocol.body(command or 0x8000,seq,session,data).also { it[5]=result.toByte() }
        val frame=L4sProtocol.frame(reply)
        retries[seq]=frame
        if(command==L4sProtocol.CW && dropFirstCwReply) dropFirstCwReply=false else wire+=frame
        return true
    }
    override suspend fun readAvailable(maxBytes:Int):ByteArray {
        val n=minOf(3,maxBytes,wire.size)
        return wire.copyOfRange(0,n).also { wire=wire.copyOfRange(n,wire.size) }
    }
}
