package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.data.repository.RadioProfileBackup
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.repository.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadioProfileManagerTest {
    @Test fun extendedCwBackupRequiresNewFirmwareBeforeAnyMutation()=runTest {
        val fixture=Fixture()
        val raw=RadioProfileRecordCodec.bytes(record().rawHex)
        raw[4]=2;raw[46]=30;raw[47]=1;raw[60]=0;raw[61]=0
        L4sProtocol.put16(raw,60,RadioProfileRecordCodec.crc(raw))
        val upgraded=checkNotNull(RadioProfileRecordCodec.decode(0,raw))
        val json=RadioProfileBackup.encode(listOf(upgraded),1)
        assertFalse(fixture.manager.prepareImport(json));assertEquals(0,fixture.radio.mutations)
        val remote=fixture.radio.remote.value
        fixture.radio.remote.value=remote.copy(capabilities=remote.capabilities!!.copy(cwOptions=true))
        assertTrue(fixture.manager.prepareImport(json));assertEquals(0,fixture.radio.mutations)
    }
    @Test fun exportReadsSavedSnapshotWithoutMutatingRadio()=runTest {
        val fixture=Fixture();val json=fixture.manager.export()
        assertNotNull(json);assertEquals(1,RadioProfileBackup.decode(json!!).size)
        assertEquals(0,fixture.radio.mutations);assertEquals(0,fixture.radio.cancelCount)
    }
    @Test fun importWaitsForPhysicalConfirmationAndKeepsConflictsUnlessChosen()=runTest {
        val fixture=Fixture();val original=fixture.radio.records[0].copyOf()
        val incoming=record(435200000)
        val newcomer=record(435300000,"NEW")
        assertTrue(fixture.manager.prepareImport(RadioProfileBackup.encode(listOf(incoming,newcomer),1)))
        var result=false;val job=launch {result=fixture.manager.import(emptySet())};runCurrent()
        assertEquals(6,fixture.radio.phase);assertTrue(fixture.manager.state.value.canCancel)
        assertArrayEquals(original,fixture.radio.records[0]);assertFalse(job.isCompleted)
        fixture.radio.confirm();advanceTimeBy(101);runCurrent();assertTrue(result)
        assertArrayEquals(original,fixture.radio.records[0])
        assertArrayEquals(RadioProfileRecordCodec.bytes(newcomer.rawHex),fixture.radio.records[1])
        assertTrue(fixture.manager.prepareImport(RadioProfileBackup.encode(listOf(incoming),1)))
        val second=launch {result=fixture.manager.import(setOf(incoming.key))};runCurrent()
        assertEquals(6,fixture.radio.phase);assertArrayEquals(original,fixture.radio.records[0])
        fixture.radio.confirm();advanceTimeBy(101);runCurrent();assertTrue(second.isCompleted);assertTrue(result)
        assertArrayEquals(RadioProfileRecordCodec.bytes(incoming.rawHex),fixture.radio.records[0])
    }
    @Test fun stalePreviewRejectsBeforeBeginningAnyWrite()=runTest {
        val fixture=Fixture()
        assertTrue(fixture.manager.prepareImport(RadioProfileBackup.encode(listOf(record(435200000)),1)))
        fixture.radio.generation++
        assertFalse(fixture.manager.import(setOf(record().key)))
        assertEquals(0,fixture.radio.mutations)
        assertTrue(fixture.manager.state.value.error!!.contains("preview"))
    }
    @Test fun cancelledImportRetainsSavedSnapshotAndCannotCancelHandsetLocalSave()=runTest {
        val fixture=Fixture();val original=fixture.radio.records[0].copyOf()
        fixture.radio.phase=1;fixture.radio.kind=1;fixture.radio.transaction=77
        assertFalse(fixture.manager.refresh());assertEquals(0,fixture.radio.cancelCount)
        fixture.radio.phase=0
        assertTrue(fixture.manager.prepareImport(RadioProfileBackup.encode(listOf(record(435200000)),1)))
        val job=launch {fixture.manager.import(setOf(record().key))};runCurrent()
        fixture.manager.cancel();advanceTimeBy(101);runCurrent()
        assertTrue(job.isCompleted);assertEquals(1,fixture.radio.cancelCount)
        assertArrayEquals(original,fixture.radio.records[0])
    }
    @Test fun deletingCurrentProfileRequiresStoppedTrackingAndLocalConfirmation()=runTest {
        val fixture=Fixture()
        assertTrue(fixture.manager.refresh());fixture.tracking=fixture.tracking.copy(isActive=true)
        assertFalse(fixture.manager.delete(0));assertEquals(0,fixture.radio.mutations)
        fixture.tracking=fixture.tracking.copy(isActive=false)
        var result=false;val job=launch {result=fixture.manager.delete(0)};runCurrent()
        assertEquals(6,fixture.radio.phase);assertFalse(job.isCompleted)
        fixture.radio.confirm();advanceTimeBy(101);runCurrent();assertTrue(result)
        assertTrue(fixture.radio.records[0].all {it==(-1).toByte()})
    }
    @Test fun reconnectInvalidatesPreparedImport()=runTest {
        val fixture=Fixture()
        assertTrue(fixture.manager.prepareImport(RadioProfileBackup.encode(listOf(record()),1)))
        fixture.radio.remote.value=fixture.radio.remote.value.copy(session=2)
        assertFalse(fixture.manager.import(emptySet()));assertEquals(0,fixture.radio.mutations)
    }
    @Test fun identicalRestoreDoesNotEraseOrRequestAConfirmation()=runTest {
        val fixture=Fixture()
        assertTrue(fixture.manager.prepareImport(RadioProfileBackup.encode(listOf(record()),1)))
        assertTrue(fixture.manager.import(emptySet()))
        assertEquals(0,fixture.radio.mutations);assertEquals(0,fixture.radio.phase)
        assertTrue(fixture.manager.state.value.importProfiles.isEmpty())
    }
    private class Fixture {
        val radio=StoreRadio()
        var tracking=RadioTrackingState(remoteRadio=radio.remote.value)
        private var metadata=mapOf<String,RadioProfileMetadata>()
        private var aliases=mapOf<String,String>()
        @Suppress("UNCHECKED_CAST")
        private val settings=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(ISettingsRepo::class.java)) {_,method,args->
            when(method.name) {
                "getRadioProfileMetadata"->metadata
                "setRadioProfileMetadata"->{metadata=args!![0] as Map<String,RadioProfileMetadata>;null}
                "getRadioProfileAliases"->aliases
                "setRadioProfileAliases"->{aliases=args!![0] as Map<String,String>;null}
                else->throw UnsupportedOperationException(method.name)
            }
        } as ISettingsRepo
        private val satellites=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(ISatelliteRepo::class.java)) {_,method,_->
            if(method.name=="getSatellites")MutableStateFlow(emptyList<OrbitalObject>()) else throw UnsupportedOperationException(method.name)
        } as ISatelliteRepo
        val manager=RadioProfileManager({radio},{tracking},settings,satellites){controller,data->
            if(tracking.frequencyEditingLocked)null else controller.profileStoreCommand(data)
        }
    }
    private class StoreRadio:IRadioController {
        val records=MutableList(56){ByteArray(64){-1}}.also {it[0]=RadioProfileRecordCodec.bytes(record().rawHex)}
        private var staged=records.map {it.copyOf()}.toMutableList()
        var phase=0;var kind=0;var transaction=0L;var generation=1L;var next=0
        var mutations=0;var cancelCount=0;private var deleting=-1
        val remote=MutableStateFlow(RemoteRadioState(connected=true,session=1,nativeProfileKey=record().key,capabilities=
            RemoteRadioCapabilities(10,92,10,45,1500,18000000,1300000000,15,9,true,true,true,true,false,false,
                nativeSettings=true,satelliteProfiles=true,transactionalProfileStore=true)))
        override val remoteState get()=remote
        override val isConnected=true
        override suspend fun profileStoreCommand(data:ByteArray):ByteArray? {
            when(data[0].toInt()) {
                0,1->Unit
                2->{if(L4sProtocol.u32(data,1)!=generation)return null;transaction=L4sProtocol.u32(data,5);kind=3;phase=4;next=0;staged=records.map {it.copyOf()}.toMutableList();mutations++}
                3->{if(L4sProtocol.u32(data,1)!=transaction || (data[5].toInt() and 255)!=next)return null;staged[next]=data.copyOfRange(6,70);next++}
                4->{if(next!=56)return null;phase=6}
                5->{if(L4sProtocol.u32(data,1)!=generation)return null;transaction=L4sProtocol.u32(data,5);kind=2;deleting=data[25].toInt();phase=6;mutations++}
                6->{phase=10;cancelCount++}
                else->return null
            }
            val output=ByteArray(if(data[0].toInt()==1)88 else 23)
            output[0]=1;output[1]=phase.toByte();output[2]=if(phase==9)100 else 90
            output[4]=if(phase in 1..8)1 else 0;output[5]=records.count {row->row.any {it!=(-1).toByte()}}.toByte();output[6]=56
            L4sProtocol.put32(output,8,generation);L4sProtocol.put32(output,12,transaction)
            output[16]=next.toByte();L4sProtocol.put16(output,17,10);output[19]=kind.toByte()
            if(data[0].toInt()==1){val slot=data[1].toInt();output[23]=slot.toByte();records[slot].copyInto(output,24)}
            return output
        }
        fun confirm(){if(kind==2)records[deleting]=ByteArray(64){-1} else staged.forEachIndexed {i,row->records[i]=row};generation++;phase=9}
        override suspend fun connect()=true
        override suspend fun disconnect()=Unit
        override suspend fun setFrequency(frequencyHz:Long)=false
        override suspend fun setMode(mode:String)=false
        override suspend fun setCtcssMode(enabled:Boolean)=false
        override suspend fun setCtcssTone(toneHz:Double)=false
        override suspend fun readFrequencyAndMode():Pair<Long,String>?=null
        override suspend fun pttOn()=false
        override suspend fun pttOff()=true
    }
    companion object {
        private fun record(rx:Long=435100000,name:String="TEST"):StoredRadioProfile {
            val raw=ByteArray(64);"L4SM".toByteArray().copyInto(raw);raw[4]=1;raw[6]=3;raw[7]=20;raw[8]=60;raw[9]=4
            val key=radioSatelliteProfileKey(name,"FM")
            key.chunked(2).map {it.toInt(16).toByte()}.toByteArray().copyInto(raw,14)
            L4sProtocol.put32(raw,30,rx);L4sProtocol.put32(raw,34,145900000);name.take(12).toByteArray().copyInto(raw,48)
            val crc=RadioProfileRecordCodec.crc(raw,60);L4sProtocol.put16(raw,60,crc)
            return checkNotNull(RadioProfileRecordCodec.decode(0,raw)).copy(metadata=RadioProfileMetadata(name,"FM"))
        }
    }
}
