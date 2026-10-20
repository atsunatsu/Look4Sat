package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.data.repository.RadioProfileBackup
import com.rtbishop.look4sat.core.domain.repository.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

internal class RadioProfileManager(
    private val controller:()->IRadioController?,private val radioState:()->RadioTrackingState,
    private val settings:ISettingsRepo,private val satellites:ISatelliteRepo,
    private val startMutation:suspend(IRadioController,ByteArray)->ByteArray?
) {
    private val mutable=MutableStateFlow(RadioProfileManagerState())
    val state:StateFlow<RadioProfileManagerState> = mutable
    private val mutex=Mutex()
    private val nonce=AtomicLong(System.currentTimeMillis() and 0xffffffffL)
    private var previewGeneration:Long?=null
    private var listedGeneration:Long?=null
    private var listedController:IRadioController?=null
    private var listedSession:Long?=null
    @Volatile private var ownedTransaction:Long?=null
    @Volatile private var ownedKind=0
    private var ownedController:IRadioController?=null
    private var ownedSession:Long?=null
    fun observe(status:RadioProfileStoreStatus?) {
        if(status!=null) {
            if(!status.busy && status.transaction==ownedTransaction && status.kind==ownedKind)ownedTransaction=null
            mutable.update {it.copy(status=status,canCancel=status.busy && status.phase<7 && status.transaction==ownedTransaction && status.kind==ownedKind)}
        }
    }
    private fun radio():IRadioController=checkNotNull(controller()?.takeIf {
        it.isConnected && it.remoteState?.value?.capabilities?.transactionalProfileStore==true
    }) {"Connect an alpha7 L4S radio to manage profiles"}
    private suspend fun status(radio:IRadioController):RadioProfileStoreStatus {
        check(controller()===radio && radio.isConnected){"Radio disconnected; operation cancelled"}
        val data=checkNotNull(radio.profileStoreCommand(byteArrayOf(0))){"Cannot read radio profile status"}
        val result=checkNotNull(L4sProtocol.storeStatus(data)){"Malformed profile status"}
        observe(result);return result
    }
    private suspend fun readAll(radio:IRadioController):List<StoredRadioProfile> {
        repeat(2) {attempt->
            val session=radio.remoteState?.value?.session
            val initial=status(radio);check(!initial.busy){"Wait for the current profile operation"}
            val records=mutableListOf<StoredRadioProfile>()
            for(slot in 0..55) {
                val data=checkNotNull(radio.profileStoreCommand(byteArrayOf(1,slot.toByte()))){"Profile read failed; retry"}
                check(data.size==88 && data[23].toInt()==slot){"Malformed profile record"}
                RadioProfileRecordCodec.decode(slot,data.copyOfRange(24,88))?.let {records+=resolve(it)}
            }
            if(status(radio).generation==initial.generation && session==radio.remoteState?.value?.session) {
                listedGeneration=initial.generation;listedController=radio;listedSession=session;return records
            }
            check(attempt==0){"Profiles changed while reading; retry"}
        }
        error("Profiles changed while reading")
    }
    private suspend fun resolve(profile:StoredRadioProfile):StoredRadioProfile {
        var metadata=settings.getRadioProfileMetadata()[profile.key]
        if(metadata==null && profile.compatible) {
            val candidates=satellites.satellites.value.filter {it.data.name.take(12)==profile.label}
            for(satellite in candidates.take(16)) {
                val found=satellites.getRadiosWithId(satellite.data.catnum).find {
                    radioSatelliteProfileKey(satellite.data.name,it.uuid)==profile.key
                }
                if(found!=null) {metadata=RadioProfileMetadata(satellite.data.name,found.uuid,found.info);break}
            }
            metadata?.let {settings.setRadioProfileMetadata(settings.getRadioProfileMetadata()+(profile.key to it))}
        }
        return profile.copy(metadata=metadata,alias=settings.getRadioProfileAliases()[profile.key])
    }
    private suspend fun execute(block:suspend()->Unit):Boolean=mutex.withLock {
        mutable.update {it.copy(loading=true,error=null,message=null)}
        try {block();true}
        catch(cancelled:CancellationException){withContext(NonCancellable){runCatching {cancel()}};throw cancelled}
        catch(error:Exception){runCatching {cancel()};mutable.update {it.copy(error=error.message ?: "Profile operation failed")};false}
        finally {mutable.update {it.copy(loading=false)}}
    }
    suspend fun refresh():Boolean=execute {val records=readAll(radio());mutable.update {it.copy(profiles=records)}}
    suspend fun prepareImport(json:String):Boolean=execute {
        val imported=RadioProfileBackup.decode(json);val radio=radio()
        check(radio.remoteState?.value?.capabilities?.cwOptions==true || imported.none {RadioProfileRecordCodec.bytes(it.rawHex)[4]==2.toByte()}) {
            "Upgrade the handset firmware before restoring extended CW settings"
        }
        val existing=readAll(radio)
        RadioProfileRecordCodec.merge(existing,imported,emptySet())
        previewGeneration=listedGeneration
        mutable.update {it.copy(profiles=existing,importProfiles=imported)}
    }
    private fun packet(operation:Int,generation:Long,transaction:Long,key:String?=null):ByteArray {
        val data=ByteArray(if(key==null)9 else 25);data[0]=operation.toByte()
        L4sProtocol.put32(data,1,generation);L4sProtocol.put32(data,5,transaction)
        key?.chunked(2)?.map {it.toInt(16).toByte()}?.toByteArray()?.copyInto(data,9)
        return data
    }
    private fun transaction():Long=(nonce.incrementAndGet() and 0xffffffffL).coerceAtLeast(1)
    private suspend fun await(radio:IRadioController,predicate:(RadioProfileStoreStatus)->Boolean):RadioProfileStoreStatus = withTimeout(120000) {
        while(true) {
            val current=status(radio)
            check(current.phase !in setOf(10,11)) {if(current.phase==10)"Profile operation cancelled" else "Profile storage failed; old snapshot retained"}
            if(predicate(current))return@withTimeout current
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }
    suspend fun delete(slot:Int):Boolean=execute {
        val radio=radio();val profile=checkNotNull(state.value.profiles.find {it.slot==slot}){"Profile not found"}
        check(!radioState().isActive || radio.remoteState?.value?.nativeProfileKey!=profile.key){"Stop tracking before deleting the current profile"}
        val expected=checkNotNull(listedGeneration)
        check(listedController===radio && listedSession==radio.remoteState?.value?.session){"Refresh profiles after reconnecting"}
        check(status(radio).generation==expected){"Profiles changed; refresh before deleting"}
        val transaction=transaction();own(radio,transaction,2)
        val started=startMutation(radio,packet(5,expected,transaction,profile.key)+slot.toByte())
        check(started!=null || status(radio).let {it.transaction==transaction && it.busy && it.kind==2}){"Delete rejected; stop transmitting and close radio MENU"}
        await(radio){it.complete}
        settings.setRadioProfileAliases(settings.getRadioProfileAliases()-profile.key)
        val records=readAll(radio);mutable.update {it.copy(profiles=records,message="Profile deleted")}
    }
    suspend fun import(overwrite:Set<String>):Boolean=execute {
        val radio=radio();check(!radioState().isActive){"Stop tracking before restoring profiles"}
        val current=state.value;val expected=checkNotNull(previewGeneration){"Preview the backup first"}
        check(listedController===radio && listedSession==radio.remoteState?.value?.session){"Preview the backup again after reconnecting"}
        check(status(radio).generation==expected){"Profiles changed; preview the merge again"}
        val merged=RadioProfileRecordCodec.merge(current.profiles,current.importProfiles,overwrite)
        val original=MutableList(56){ByteArray(64){-1}}
        current.profiles.forEach {original[it.slot]=RadioProfileRecordCodec.bytes(it.rawHex)}
        if(merged.indices.all {merged[it].contentEquals(original[it])}) {
            importMetadata(current,overwrite);previewGeneration=null
            val records=current.profiles.map {it.copy(metadata=settings.getRadioProfileMetadata()[it.key],alias=settings.getRadioProfileAliases()[it.key])}
            mutable.update {it.copy(profiles=records,importProfiles=emptyList(),message="Settings already match; no radio write was needed")}
            return@execute
        }
        val transaction=transaction()
        own(radio,transaction,3)
        val started=startMutation(radio,packet(2,expected,transaction))
        check(started!=null || status(radio).let {it.transaction==transaction && it.busy && it.kind==3}){"Import rejected; stop transmitting and close radio MENU"}
        for(slot in 0..55) {
            await(radio){it.phase==4 && !it.ioPending && it.nextSlot==slot}
            val data=ByteArray(70);data[0]=3;L4sProtocol.put32(data,1,transaction);data[5]=slot.toByte();merged[slot].copyInto(data,6)
            if(radio.profileStoreCommand(data)==null) {
                val actual=status(radio)
                if(actual.nextSlot==slot)checkNotNull(radio.profileStoreCommand(data)){"Import transfer failed"}
                else check(actual.nextSlot==slot+1){"Import transfer sequence changed"}
            }
        }
        await(radio){it.phase==4 && !it.ioPending && it.nextSlot==56}
        val finish=ByteArray(5);finish[0]=4;L4sProtocol.put32(finish,1,transaction)
        checkNotNull(radio.profileStoreCommand(finish)){"Import verification failed"}
        await(radio){it.complete}
        importMetadata(current,overwrite)
        previewGeneration=null;val records=readAll(radio)
        mutable.update {it.copy(profiles=records,importProfiles=emptyList(),message="Profiles restored")}
    }
    private fun importMetadata(current:RadioProfileManagerState,overwrite:Set<String>) {
        val metadata=settings.getRadioProfileMetadata().toMutableMap();val aliases=settings.getRadioProfileAliases().toMutableMap()
        val existingKeys=current.profiles.map {it.key}.toSet()
        current.importProfiles.forEach {profile->
            profile.metadata?.let {if(profile.key !in metadata || profile.key in overwrite || profile.key !in existingKeys)metadata[profile.key]=it}
            profile.alias?.let {if(profile.key !in aliases || profile.key in overwrite || profile.key !in existingKeys)aliases[profile.key]=it}
        }
        settings.setRadioProfileMetadata(metadata);settings.setRadioProfileAliases(aliases)
    }
    suspend fun export():String? {
        var json:String?=null
        execute {val records=readAll(radio());json=RadioProfileBackup.encode(records,checkNotNull(listedGeneration));mutable.update {it.copy(profiles=records)}}
        return json
    }
    suspend fun cancel() {
        val token=ownedTransaction ?: return
        val radio=controller() ?: return
        if(radio!==ownedController || radio.remoteState?.value?.session!=ownedSession){ownedTransaction=null;return}
        val active=runCatching {status(radio)}.getOrNull() ?: return
        if(active.transaction!=token || active.kind!=ownedKind)return
        if(!active.busy || active.phase>=7)return
        val data=ByteArray(5);data[0]=6;L4sProtocol.put32(data,1,active.transaction)
        radio.profileStoreCommand(data)
    }
    private fun own(radio:IRadioController,transaction:Long,kind:Int) {
        ownedController=radio;ownedSession=radio.remoteState?.value?.session;ownedKind=kind;ownedTransaction=transaction
    }
    fun alias(key:String,value:String) {
        settings.setRadioProfileAliases(settings.getRadioProfileAliases()+(key to value.trim().take(128)))
        mutable.update {it.copy(profiles=it.profiles.map {profile->if(profile.key==key)profile.copy(alias=value.trim()) else profile})}
    }
}
