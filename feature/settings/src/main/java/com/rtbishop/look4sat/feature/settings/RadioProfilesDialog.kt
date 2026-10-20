package com.rtbishop.look4sat.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.repository.*
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import com.rtbishop.look4sat.core.presentation.formatFrequency
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RadioProfilesDialog(manager:RadioProfileManagerState,radio:RadioTrackingState,export:String?,
    action:(SettingsAction)->Unit,onDismiss:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var pendingExport by rememberSaveable {mutableStateOf<String?>(null)}
    var ioError by remember {mutableStateOf<String?>(null)}
    var selectedKey by rememberSaveable {mutableStateOf<String?>(null)}
    var alias by rememberSaveable {mutableStateOf("")}
    var overwrite by remember(manager.importProfiles) {mutableStateOf(setOf<String>())}
    val exportLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
        val text=pendingExport;pendingExport=null
        if(uri!=null && text!=null)scope.launch {
            runCatching {withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri)).writer(Charsets.UTF_8).use {it.write(text)}}}
                .onFailure {ioError=it.message}
        }
    }
    val importLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null)scope.launch {
            runCatching {withContext(Dispatchers.IO){
                checkNotNull(context.contentResolver.openInputStream(uri)).use {input->
                    val result=ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(true){val count=input.read(buffer);if(count<0)break;require(result.size()+count<=262144){"Backup exceeds 256 KiB"};result.write(buffer,0,count)}
                    result.toString("UTF-8")
                }
            }}.onSuccess {action(SettingsAction.ImportRadioProfiles(it))}.onFailure {ioError=it.message}
        }
    }
    LaunchedEffect(export) {export?.let {pendingExport=it;action(SettingsAction.ConsumeRadioProfilesExport);exportLauncher.launch("Look4Sat-L4S-profiles.json")}}
    val unsupported=radio.remoteRadio?.capabilities?.transactionalProfileStore!=true || radio.remoteRadio?.connected!=true
    val busy=manager.loading || manager.status?.busy==true
    SharedDialog(title=stringResource(R.string.rc_l4s_profiles),onDismissRequest=onDismiss,onCancel=onDismiss) {padding->
        Column(modifier=Modifier.fillMaxWidth().heightIn(max=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp*0.65f).dp)
            .verticalScroll(rememberScrollState()).padding(padding),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(unsupported)Text(stringResource(R.string.rc_l4s_profiles_upgrade),color=MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.rc_l4s_profile_capacity,manager.status?.used ?: manager.profiles.size,56))
            if(manager.status?.readOnly==true)Text(stringResource(R.string.rc_l4s_profiles_readonly),color=MaterialTheme.colorScheme.error)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick={action(SettingsAction.RefreshRadioProfiles)},enabled=!busy && !unsupported,modifier=Modifier.weight(1f)){Text(stringResource(R.string.rc_l4s_refresh))}
                OutlinedButton(onClick={action(SettingsAction.ExportRadioProfiles)},enabled=!busy && !unsupported,modifier=Modifier.weight(1f)){Text(stringResource(R.string.rc_l4s_export))}
                OutlinedButton(onClick={importLauncher.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!busy && !unsupported,modifier=Modifier.weight(1f)){Text(stringResource(R.string.rc_l4s_import))}
            }
            if(manager.loading)LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
            manager.status?.takeIf {it.busy}?.let {status->
                if(status.error!=0)Text(stringResource(R.string.rc_l4s_store_failed),color=MaterialTheme.colorScheme.error)
                else Text(stringResource(if(status.waitingForConfirmation)R.string.rc_l4s_confirm_handset else R.string.rc_l4s_store_progress,status.progress))
                Text(stringResource(R.string.rc_l4s_merge_counts,status.added,status.replaced,status.deleted))
                OutlinedButton(onClick={action(SettingsAction.CancelRadioProfileOperation)},enabled=manager.canCancel){Text(stringResource(R.string.btn_cancel))}
            }
            if(manager.profiles.isEmpty() && !manager.loading)Text(stringResource(R.string.rc_l4s_profiles_empty))
            manager.profiles.forEach {profile->
                Column(modifier=Modifier.fillMaxWidth().clickable {selectedKey=profile.key;alias=profile.alias ?: ""}.padding(vertical=6.dp)) {
                    Text(profile.displayName,color=MaterialTheme.colorScheme.primary)
                    Text(profile.key.take(12),style=MaterialTheme.typography.bodySmall)
                    if(!profile.compatible)Text(stringResource(R.string.rc_l4s_profile_damaged),color=MaterialTheme.colorScheme.error)
                }
            }
            if(manager.importProfiles.isNotEmpty()) {
                HorizontalDivider();Text(stringResource(R.string.rc_l4s_merge_preview),color=MaterialTheme.colorScheme.primary)
                manager.importProfiles.forEach {incoming->
                    val existing=manager.profiles.find {it.key==incoming.key}
                    Row(modifier=Modifier.fillMaxWidth()) {
                        if(existing!=null && existing.rawHex!=incoming.rawHex)Checkbox(checked=incoming.key in overwrite,
                            onCheckedChange={overwrite=if(it)overwrite+incoming.key else overwrite-incoming.key},enabled=!busy)
                        Text(incoming.displayName+" · "+stringResource(if(existing==null)R.string.rc_l4s_merge_add else R.string.rc_l4s_merge_keep_or_replace))
                    }
                }
                if(radio.isActive)Text(stringResource(R.string.rc_l4s_stop_tracking_first))
                Button(onClick={action(SettingsAction.RestoreRadioProfiles(overwrite))},enabled=!busy && !radio.frequencyEditingLocked && !radio.isActive && !unsupported && manager.status?.readOnly!=true) {
                    Text(stringResource(R.string.rc_l4s_send_for_confirmation))
                }
            }
            (ioError ?: manager.error ?: manager.message)?.let {Text(it,color=if(ioError!=null||manager.error!=null)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}
            Text(stringResource(R.string.rc_l4s_export_saved_only),style=MaterialTheme.typography.bodySmall)
        }
    }
    val selected=manager.profiles.find {it.key==selectedKey}
    if(selected!=null)SharedDialog(title=selected.displayName,onDismissRequest={selectedKey=null},onCancel={selectedKey=null},
        onAccept={action(SettingsAction.SetRadioProfileAlias(selected.key,alias));selectedKey=null}) {padding->
        Column(modifier=Modifier.fillMaxWidth().heightIn(max=420.dp).verticalScroll(rememberScrollState()).padding(padding),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=alias,onValueChange={alias=it.take(128)},label={Text(stringResource(R.string.rc_l4s_profile_alias))},modifier=Modifier.fillMaxWidth())
            Text(stringResource(R.string.rc_l4s_alias_local),style=MaterialTheme.typography.bodySmall)
            if(selected.compatible) {
                val bytes=RadioProfileRecordCodec.bytes(selected.rawHex)
                Text(stringResource(R.string.rc_l4s_rx_reference)+": "+formatFrequency(RadioProfileRecordCodec.read32(bytes,30)))
                Text(stringResource(R.string.rc_l4s_tx_reference)+": "+RadioProfileRecordCodec.read32(bytes,34).takeIf {it>0}?.let(::formatFrequency).orEmpty())
                Text(stringResource(R.string.rc_l4s_corrections,RadioProfileRecordCodec.read32(bytes,42).toInt(),RadioProfileRecordCodec.read32(bytes,38).toInt(),radio.remoteRadio?.nativeStepHz ?: 250))
                Text(stringResource(R.string.rc_l4s_profile_details,listOf("FM","CW","USB/CW","NFM")[bytes[5].toInt()],
                    listOf("USER","LOW1","LOW2","LOW3","LOW4","LOW5","MID","HIGH")[bytes[6].toInt()],bytes[13].toInt(),bytes[7].toInt(),bytes[8].toInt()*10,bytes[9].toInt()))
            }
            val current=radio.remoteRadio?.nativeProfileKey==selected.key
            if(current && radio.isActive)Text(stringResource(R.string.rc_l4s_stop_tracking_first))
            OutlinedButton(onClick={action(SettingsAction.DeleteRadioProfile(selected.slot));selectedKey=null},
                enabled=!busy && !radio.frequencyEditingLocked && !(current && radio.isActive) && manager.status?.readOnly!=true) {
                Text(stringResource(R.string.rc_l4s_delete_confirm_handset))
            }
        }
    }
}
