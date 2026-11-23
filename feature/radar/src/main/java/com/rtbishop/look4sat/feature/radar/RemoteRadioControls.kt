package com.rtbishop.look4sat.feature.radar

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.repository.PttState
import com.rtbishop.look4sat.core.domain.repository.RadioTransmitOwner
import com.rtbishop.look4sat.core.domain.model.RadioRecordingAction
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.formatFrequency

internal fun RadioControlSubState.canSendL4sCw(): Boolean =
    catControlsActive && remoteRadio?.connected == true && remoteRadio.capabilities?.cwText == true &&
        remoteRadio.txEnabled && remoteRadio.transmitStateKnown && remoteRadio.profileStore?.busy!=true && remoteRadio.handsetMacroStatus?.busy!=true &&
        !remoteRadio.nativeMenuOpen && remoteRadio.txSource == 0 && isReady && txPanel.mode == "CW" &&
        transmitOwner == RadioTransmitOwner.NONE && pttState == PttState.OFF

@Composable
internal fun RemoteRadioControls(control: RadioControlSubState, onAction: (RadarAction) -> Unit) {
    if (control.externalUsbPtt && control.txPanel.isConnected) UsbPttControls(control, onAction)
    if (!control.remoteControlsVisible) return
    val remote = control.remoteRadio ?: return
    val context = LocalContext.current
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val saveRecording = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) onAction(RadarAction.SaveRecording(uri.toString()))
    }
    val caps = remote.capabilities
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
    val requiresMicrophone by rememberUpdatedState(remote.phoneMicrophone)
    val canVoice by rememberUpdatedState(remote.connected && !remote.nativeMenuOpen && control.isReady && caps?.voicePtt == true &&
        remote.txEnabled && remote.transmitStateKnown && remote.profileStore?.busy!=true && remote.handsetMacroStatus?.busy!=true &&
        (!remote.phoneMicrophone || remote.bluetoothAudioConnected) &&
        control.txPanel.mode in setOf("FM", "NFM") && control.transmitOwner == RadioTransmitOwner.NONE &&
        control.pttState == PttState.OFF)
    val pttLabel = stringResource(R.string.rc_l4s_hold)
    DisposableEffect(Unit) { onDispose { action(RadarAction.StopRemoteTx) } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (caps?.cwText == true) {
            cwBlockReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (caps?.cwText == true || caps?.nativeSettings == true) {
        Text(stringResource(R.string.rc_l4s_applied,
            remote.txFrequencyHz?.let(::formatFrequency) ?: "—", remote.rxFrequencyHz?.let(::formatFrequency) ?: "—"),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.rc_l4s_source, when(remote.txSource) {
            1 -> "PTT"; 2 -> "CW"; 3 -> stringResource(R.string.rc_l4s_local); else -> stringResource(R.string.rc_l4s_off)
        }), style = MaterialTheme.typography.bodySmall)
        if(caps.nativeSettings) {
            fun toneLabel(hz:Double?)=hz?.let { "${it} Hz" } ?: "OFF"
            Text(stringResource(R.string.rc_l4s_tones,toneLabel(remote.txCtcssHz),toneLabel(remote.rxCtcssHz),toneLabel(remote.activationCtcssHz)),
                style=MaterialTheme.typography.bodySmall)
        }
        }
        if (control.voicePttVisible && !control.externalUsbPtt) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                if (requiresMicrophone && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                    waitForUpOrCancellation()
                                    return@awaitEachGesture
                                }
                                action(RadarAction.VoicePtt(true))
                                try { waitForUpOrCancellation() }
                                finally { action(RadarAction.VoicePtt(false)) }
                            }
                        }
                    }.padding(8.dp)
            ) { Text(pttLabel, color = if (canVoice || control.transmitOwner == RadioTransmitOwner.VOICE)
                MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)) }
        }
        if (control.stopRemoteTxVisible) OutlinedButton(onClick = { action(RadarAction.StopRemoteTx) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.rc_l4s_stop))
        }
        if (remote.isN76) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { action(RadarAction.RadioMonitor(!remote.radioMonitor)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = !control.frequencyEditingLocked) {
                    Text(stringResource(if (remote.radioMonitor) R.string.rc_n76_monitor_off else R.string.rc_n76_monitor))
                }
                if (remote.recordingSupported) OutlinedButton(onClick = {
                    if (!remote.recording && remote.recordingNeedsMicrophone &&
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    else action(RadarAction.Recording(if (remote.recording) RadioRecordingAction.STOP else RadioRecordingAction.START))
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(if (remote.recording) R.string.rc_n76_stop_recording else R.string.rc_n76_record))
                }
            }
            if (remote.recordingSupported) OutlinedButton(onClick = {
                action(RadarAction.Recording(if (remote.playingRecording) RadioRecordingAction.STOP_PLAYBACK else RadioRecordingAction.PLAY_LAST))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !remote.recording && remote.lastRecording != null && control.transmitOwner == RadioTransmitOwner.NONE) {
                Text(stringResource(if (remote.playingRecording) R.string.rc_n76_stop_playback else R.string.rc_n76_play_last))
            }
            if (remote.recordingSupported && remote.lastRecording != null) OutlinedButton(onClick = {
                saveRecording.launch(remote.lastRecording?.substringAfterLast('/')?.takeIf { it.endsWith(".wav", true) } ?: "N76-recording.wav")
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !remote.recording && !remote.recordingExporting) {
                Text(stringResource(if (remote.recordingExporting) R.string.rc_n76_saving else R.string.rc_n76_save))
            }
            val recordingError = remote.recordingError ?: remote.bluetoothAudioError
            if (recordingError != null) Text(recordingError, color = MaterialTheme.colorScheme.error)
            else if (remote.recordingExportError != null) Text(stringResource(R.string.rc_n76_export_retry), color = MaterialTheme.colorScheme.error)
            else if (remote.recordingExported && !remote.recordingExporting) Text(stringResource(R.string.rc_n76_saved), style = MaterialTheme.typography.bodySmall)
        }
    }
}
