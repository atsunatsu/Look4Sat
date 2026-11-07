// SPDX-License-Identifier: GPL-3.0-or-later
package com.rtbishop.look4sat.feature.radar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.sheetDialogContainerColor
import com.rtbishop.look4sat.core.presentation.sheetDialogShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingPanel(satellite: String,passAos: Long?,providedRecorder: IRecordingService? = null,maxElevation: Double? = null,onDismiss: ()->Unit) {
    val context=LocalContext.current
    val recorder=providedRecorder ?: (context.applicationContext as IContainerProvider).getMainContainer().recordingService
    val state by recorder.state.collectAsStateWithLifecycle()
    val files by recorder.files.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var selection by rememberSaveable {mutableStateOf("")}
    var pendingSource by rememberSaveable {mutableStateOf<String?>(null)}
    var exportFile by rememberSaveable {mutableStateOf<String?>(null)}
    var deleting by rememberSaveable {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var exported by remember {mutableStateOf(false)}
    fun operation(block: suspend ()->Unit) {scope.launch {
        try {error=null;block()} catch(cancelled:CancellationException) {throw cancelled}
        catch(failure:Exception) {error=failure.message}
    }}
    val document=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {uri ->
        val id=exportFile
        if(uri!=null && id!=null) operation {recorder.export(id,uri.toString());exported=true}
        exportFile=null
    }
    fun startPending() {
        val source=pendingSource ?: return
        pendingSource=null
        recorder.start(RecordingRequest(source,satellite,passAos,maxElevation))
    }
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {startPending()}
    fun startWithNotification() {
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startPending()
    }
    val microphone=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted ->
        if(granted) startWithNotification() else {pendingSource=null;error=context.getString(R.string.recording_microphone_required)}
    }
    LaunchedEffect(Unit) {recorder.refreshFiles()}
    val sources=recorder.sources()
    ModalBottomSheet(onDismissRequest=onDismiss,shape=sheetDialogShape(),containerColor=sheetDialogContainerColor()) {
        Column(Modifier.fillMaxWidth().heightIn(max=(LocalConfiguration.current.screenHeightDp*0.8f).dp)
            .verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.recording_title),style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.recording_source),style=MaterialTheme.typography.titleSmall)
            sources.forEach {source -> Row(Modifier.fillMaxWidth().clickable(enabled=!state.active) {selection=source.id},verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected=selection==source.id,onClick={selection=source.id},enabled=!state.active)
                Text(source.name+if(source.kind==RecordingSourceKind.MICROPHONE) " · "+stringResource(R.string.recording_microphone) else "")
            }}
            if(sources.isEmpty()) Text(stringResource(R.string.recording_no_source))
            if(state.active) {
                Text("${state.source?.name.orEmpty()} · ${state.durationMillis/60000}:${(state.durationMillis/1000%60).toString().padStart(2,'0')}",style=MaterialTheme.typography.titleMedium)
                if(state.sampleRate>0) Text("${state.sampleRate} Hz · PCM16 · "+stringResource(R.string.recording_mono))
                Text(state.fileName,style=MaterialTheme.typography.bodySmall)
                Button(onClick={operation {recorder.stop()}},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.recording_stop))}
            } else Button(onClick={
                val source=sources.firstOrNull {it.id==selection} ?: return@Button
                pendingSource=source.id
                if(source.kind in listOf(RecordingSourceKind.MICROPHONE,RecordingSourceKind.DEVICE) &&
                    ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                    microphone.launch(Manifest.permission.RECORD_AUDIO)
                else startWithNotification()
            },enabled=sources.any {it.id==selection} && !state.playing,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.recording_start))}
            (error ?: state.error)?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            if(exported) Text(stringResource(R.string.rc_n76_saved),color=MaterialTheme.colorScheme.primary)
            if(state.playing) OutlinedButton(onClick={operation {recorder.stopPlayback()}},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.rc_n76_stop_playback))}
            HorizontalDivider()
            Text(stringResource(R.string.recording_files),style=MaterialTheme.typography.titleMedium)
            if(files.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp)) {
            items(files,key={it.id}) {file ->
                Text(file.name,style=MaterialTheme.typography.bodyMedium)
                Text(String.format(java.util.Locale.ROOT,"%.1f MB",file.bytes/1_000_000.0),style=MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick={operation {recorder.play(file.id)}},enabled=!state.active) {Text(stringResource(R.string.recording_play))}
                    TextButton(onClick={exportFile=file.id;document.launch(file.name)},enabled=!state.exporting) {Text(stringResource(R.string.recording_export))}
                    TextButton(onClick={deleting=file.id},enabled=!state.active && !state.exporting) {Text(stringResource(R.string.recording_delete))}
                }
            }
            }
        }
    }
    deleting?.let {id -> AlertDialog(onDismissRequest={deleting=null},title={Text(stringResource(R.string.recording_delete))},
        text={Text(stringResource(R.string.recording_delete_confirm))},
        confirmButton={TextButton(onClick={deleting=null;operation {recorder.delete(id)}}) {Text(stringResource(R.string.recording_delete))}},
        dismissButton={TextButton(onClick={deleting=null}) {Text(stringResource(android.R.string.cancel))}})}
}
