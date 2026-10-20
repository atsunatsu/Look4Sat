package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.CwTransmitState
import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional cross-language integration: tools/l4s/simulator.py runs the production C protocol core. */
class L4sSimulatorTest {
    @Test fun kotlinControllerInteroperatesWithProductionCStateMachine() = runBlocking {
        val port=System.getenv("L4S_SIMULATOR_PORT")?.toIntOrNull()
        assumeTrue("Set L4S_SIMULATOR_PORT to a running hardware-free C-core simulator",port!=null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val radio=L4sController(SocketTransport(checkNotNull(port)),scope)
        try {
            assertTrue(radio.connect())
            assertTrue(radio.remoteState.value.capabilities!!.satelliteProfiles)
            assertTrue(radio.remoteState.value.capabilities!!.transactionalProfileStore)
            val store=checkNotNull(radio.profileStoreCommand(byteArrayOf(0)))
            assertEquals(23,store.size);assertFalse(checkNotNull(L4sProtocol.storeStatus(store)).busy)
            assertEquals(88,checkNotNull(radio.profileStoreCommand(byteArrayOf(1,0))).size)
            assertTrue(radio.selectSatelliteProfile("TEST SATELLITE","test-transponder"))
            assertTrue(radio.updateTrackingBases(com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435100000,145900000)))
            assertEquals(250L,radio.remoteState.value.nativeStepHz)
            assertEquals(435100000L,radio.remoteState.value.nativeBaseFrequencies!!.rxHz)
            assertTrue(radio.remoteState.value.capabilities!!.cwOptions)
            val options=com.rtbishop.look4sat.core.domain.repository.NativeCwOptions(25,70,4,45,3,12)
            assertTrue(radio.setNativeCwOptions(options))
            assertEquals(options.copy(modified=true),radio.remoteState.value.nativeCwOptions)
            assertTrue(radio.refreshNativeDiagnostics())
            assertNotNull(radio.remoteState.value.nativeDiagnostics)
            val staged=HandsetMacroSync.stage(List(8){"CQ F${it+1}"},123) {radio.handsetMacroCommand(it)}
            assertTrue(staged.waitingForConfirmation)
            assertFalse(radio.pttOn())
            assertEquals(FrequencyPairResult.HELD,radio.setTrackingFrequencies(435101000,145901000))
            assertNotNull(radio.handsetMacroCommand(byteArrayOf(4)))
            withTimeout(5000) {radio.remoteState.first {it.handsetMacroStatus?.busy==false}}
            assertTrue(radio.setSplitModes("FM","FM"))
            assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435100006,145900004))
            assertEquals(435100000L,radio.remoteState.value.rxFrequencyHz)
            assertEquals(145900000L,radio.remoteState.value.txFrequencyHz)
            assertTrue(radio.configureTxCtcss(67.0))
            assertTrue(radio.pttOn())
            assertFalse(radio.setNativeCwOptions(options.copy(wpm=30)))
            assertTrue(radio.remoteState.value.capabilities!!.trackingDisplay)
            assertTrue(radio.updateTrackingDisplay(com.rtbishop.look4sat.core.domain.repository.RadioTrackingDisplay("TEST",137.0,34.0,
                secondsUntilAos=120,secondsUntilLos=600,passPhase=1)))
            assertEquals(FrequencyPairResult.HELD,radio.setTrackingFrequencies(435101000,145901000))
            assertEquals(435100000L,radio.readWorkingFrequency())
            assertEquals(145900000L,radio.readTxVfoFrequency())
            assertTrue(radio.pttOff())
            assertEquals(FrequencyPairResult.APPLIED,radio.setTrackingFrequencies(435101000,145901000))
            assertTrue(radio.setSplitModes("CW","CW"))
            assertTrue(radio.configureTxCtcss(null))
            assertTrue(radio.startCw("CQ TEST",20))
            val heldRevision=radio.remoteState.value.appliedRevision
            withTimeout(5000) { radio.remoteState.first { it.cwState==CwTransmitState.DONE } }
            assertEquals(2,radio.remoteState.value.txSource) // operation held until STOP, including chunk boundaries
            assertTrue(radio.remoteState.value.toString(),radio.startCw("DE TEST",20))
            withTimeout(5000) { radio.remoteState.first { it.cwState==CwTransmitState.DONE } }
            assertEquals(heldRevision,radio.remoteState.value.appliedRevision)
            assertTrue(radio.pttOff())
        } finally {
            radio.disconnect();scope.cancel()
        }
    }

    private class SocketTransport(private val port:Int):RadioTransport {
        private var socket:Socket?=null
        override val isConnected get()=socket?.let { it.isConnected && !it.isClosed }==true
        override suspend fun connect():Boolean {
            socket=Socket().apply { connect(InetSocketAddress("127.0.0.1",this@SocketTransport.port),2000);tcpNoDelay=true }
            return true
        }
        override suspend fun disconnect() { socket?.close();socket=null }
        override suspend fun write(bytes:ByteArray):Boolean {
            socket!!.getOutputStream().apply { write(bytes);flush() };return true
        }
        override suspend fun readAvailable(maxBytes:Int):ByteArray {
            val input=socket!!.getInputStream()
            val n=minOf(input.available(),maxBytes)
            if(n==0)return byteArrayOf()
            val p=ByteArray(n);val read=input.read(p)
            return if(read>0)p.copyOf(read)else byteArrayOf()
        }
    }
}
