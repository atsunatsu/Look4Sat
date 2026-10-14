package com.rtbishop.look4sat.feature.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.rtbishop.look4sat.core.domain.repository.CwTransmitText
import com.rtbishop.look4sat.core.domain.repository.PttState
import com.rtbishop.look4sat.core.domain.repository.RadioTransmitOwner
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioState
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import com.rtbishop.look4sat.core.presentation.formatFrequency

internal fun RadioControlSubState.canSendL4sCw(): Boolean =
    remoteRadio?.connected == true && remoteRadio.capabilities?.cwText == true &&
        remoteRadio.txEnabled && remoteRadio.transmitStateKnown && remoteRadio.profileStore?.busy!=true &&
        !remoteRadio.nativeMenuOpen && remoteRadio.txSource == 0 && isReady && txPanel.mode == "CW" &&
        transmitOwner == RadioTransmitOwner.NONE && pttState == PttState.OFF

@Composable
internal fun RemoteRadioControls(control: RadioControlSubState, onAction: (RadarAction) -> Unit) {
    val remote = control.remoteRadio ?: RemoteRadioState()
    val caps = remote.capabilities
    val minWpm = caps?.minimumWpm ?: 10
    val maxWpm = caps?.maximumWpm ?: 45
    val cwBlockReason = when {
        !remote.connected -> stringResource(R.string.rc_l4s_need_connection)
        remote.nativeMenuOpen -> stringResource(R.string.rc_l4s_menu_open)
        caps?.cwText != true -> stringResource(R.string.rc_l4s_no_cw)
        !control.isReady -> stringResource(R.string.rc_l4s_need_tracking)
        control.txPanel.mode != "CW" -> stringResource(R.string.rc_l4s_need_cw_mode)
        remote.txSource != 0 || control.transmitOwner != RadioTransmitOwner.NONE || control.pttState != PttState.OFF ->
            stringResource(R.string.rc_l4s_busy)
        else -> null
    }
    val action by rememberUpdatedState(onAction)
    val canVoice by rememberUpdatedState(remote.connected && !remote.nativeMenuOpen && control.isReady && caps?.voicePtt == true &&
        remote.txEnabled && remote.transmitStateKnown && remote.profileStore?.busy!=true &&
        control.txPanel.mode in setOf("FM", "NFM") && control.transmitOwner == RadioTransmitOwner.NONE &&
        control.pttState == PttState.OFF)
    var showCw by rememberSaveable { mutableStateOf(false) }
    val pttLabel = stringResource(R.string.rc_l4s_hold)
    DisposableEffect(Unit) { onDispose { action(RadarAction.StopRemoteTx) } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { showCw = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.rc_l4s_cw))
        }
        cwBlockReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(stringResource(R.string.rc_l4s_applied,
            remote.txFrequencyHz?.let(::formatFrequency) ?: "—", remote.rxFrequencyHz?.let(::formatFrequency) ?: "—"),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.rc_l4s_source, when(remote.txSource) {
            1 -> "PTT"; 2 -> "CW"; 3 -> stringResource(R.string.rc_l4s_local); else -> stringResource(R.string.rc_l4s_off)
        }), style = MaterialTheme.typography.bodySmall)
        if(caps?.nativeSettings==true) {
            fun toneLabel(hz:Double?)=hz?.let { "${it} Hz" } ?: "OFF"
            Text(stringResource(R.string.rc_l4s_tones,toneLabel(remote.txCtcssHz),toneLabel(remote.rxCtcssHz),toneLabel(remote.activationCtcssHz)),
                style=MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .background(if (control.transmitOwner == RadioTransmitOwner.VOICE) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .semantics { role = Role.Button; contentDescription = pttLabel }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (canVoice) {
                                down.consume()
                                action(RadarAction.VoicePtt(true))
                                try { waitForUpOrCancellation() }
                                finally { action(RadarAction.VoicePtt(false)) }
                            }
                        }
                    }.padding(8.dp)
            ) { Text(pttLabel, color = if (canVoice || control.transmitOwner == RadioTransmitOwner.VOICE)
                MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)) }
        }
        OutlinedButton(onClick = { action(RadarAction.StopRemoteTx) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.rc_l4s_stop))
        }
        remote.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (showCw) {
        var text by rememberSaveable { mutableStateOf("") }
        var wpm by rememberSaveable { mutableIntStateOf((remote.nativeCwWpm ?: 20).coerceIn(minWpm, maxWpm)) }
        var error by remember { mutableStateOf<String?>(null) }
        var editPreset by rememberSaveable { mutableIntStateOf(-1) }
        var presetDraft by rememberSaveable { mutableStateOf("") }
        var presetError by remember { mutableStateOf<String?>(null) }
        val close = { action(RadarAction.StopRemoteTx); showCw = false }
        SharedDialog(title = stringResource(R.string.rc_l4s_cw), onDismissRequest = close, onCancel = close) { padding ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp)
                    .verticalScroll(rememberScrollState()).padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                cwBlockReason?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Text(stringResource(R.string.rc_l4s_presets_hint),style=MaterialTheme.typography.bodySmall)
                for(row in 0..1) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()) {
                        for(column in 0..3) {
                            val index=row*4+column
                            val preset=control.cwPresets.getOrElse(index) {""}
                            val available=control.transmitOwner==RadioTransmitOwner.NONE && remote.txSource==0
                            Surface(shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),
                                modifier=Modifier.weight(1f).heightIn(min=64.dp).combinedClickable(
                                    enabled=available,role=Role.Button,
                                    onLongClickLabel=stringResource(R.string.rc_l4s_edit_preset,index+1),
                                    onClick={text=preset;error=null},
                                    onLongClick={presetDraft=preset;presetError=null;editPreset=index})) {
                                Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(6.dp)) {
                                    Text("F${index+1}",color=MaterialTheme.colorScheme.primary)
                                    Text(preset.ifBlank {"—"},maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(text, { text = it.take(4096); error = null },
                    label = { Text(stringResource(R.string.rc_l4s_message)) },
                    enabled = control.transmitOwner == RadioTransmitOwner.NONE, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.rc_l4s_wpm, wpm))
                Slider(value = wpm.coerceIn(minWpm, maxWpm).toFloat(), onValueChange = { wpm = it.toInt() },
                    valueRange = minWpm.toFloat()..maxWpm.toFloat(),
                    enabled = control.transmitOwner == RadioTransmitOwner.NONE)
                Text(stringResource(R.string.rc_l4s_progress, remote.cwPosition, remote.cwLength, remote.cwState.name))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching { CwTransmitText.chunks(text, caps?.maxCwText ?: 92) }
                            .onSuccess { action(RadarAction.SendCw(text, wpm.coerceIn(minWpm, maxWpm))) }
                            .onFailure { error = it.message }
                    }, enabled = control.canSendL4sCw() && text.isNotBlank()) {
                        Text(stringResource(R.string.rc_l4s_send))
                    }
                    OutlinedButton(onClick = { action(RadarAction.StopRemoteTx) }) {
                        Text(stringResource(R.string.rc_l4s_stop))
                    }
                }
                (error ?: remote.error ?: control.errorMessage)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        if(editPreset in 0..7) {
            val dismiss={editPreset=-1;presetError=null}
            SharedDialog(title=stringResource(R.string.rc_l4s_edit_preset,editPreset+1),onCancel=dismiss,onDismissRequest=dismiss,
                onAccept={
                    runCatching {com.rtbishop.look4sat.core.domain.repository.CwTransmitPresets.normalize(presetDraft)}
                        .onSuccess {action(RadarAction.SaveCwPreset(editPreset,it));dismiss()}
                        .onFailure {presetError=it.message}
                }) { padding ->
                Column(modifier=Modifier.fillMaxWidth().padding(padding),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value=presetDraft,onValueChange={presetDraft=it.take(4096);presetError=null},
                        label={Text(stringResource(R.string.rc_l4s_message))},modifier=Modifier.fillMaxWidth())
                    Text(stringResource(R.string.rc_l4s_preset_save_hint),style=MaterialTheme.typography.bodySmall)
                    presetError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                }
            }
        }
    }
}
