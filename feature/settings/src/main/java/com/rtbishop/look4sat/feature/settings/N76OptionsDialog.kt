/* Copyright 2026 Look4Sat contributors. SPDX-License-Identifier: GPL-3.0-or-later */
package com.rtbishop.look4sat.feature.settings

import android.content.Intent
import android.app.Activity
import android.Manifest
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.audio.AudioInputDevice
import com.rtbishop.look4sat.core.domain.model.N76ControlOptions
import com.rtbishop.look4sat.core.domain.model.N76FirmwareMode
import com.rtbishop.look4sat.core.domain.model.N76Power
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun N76OptionsDialog(initial: N76ControlOptions, audioEnabled: Boolean,
    inputs: List<AudioInputDevice>, onSave: (N76ControlOptions) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var interval by remember { mutableStateOf(initial.pollMillis.toString()) }
    var folderError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) scope.launch {
            val granted = withContext(Dispatchers.IO) { runCatching {
                val flags = (result.data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                check(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                val parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                val writable = context.contentResolver.query(parent, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
                    it.moveToFirst() && it.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
                } == true
                check(writable)
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }.isSuccess }
            folderError = !granted
            if (granted) draft = draft.copy(outputFolderUri = uri.toString())
        }
    }
    val poll = n76PollMillis(interval)
    val valid = poll != null
    SharedDialog(title = stringResource(R.string.rc_n76_options), onCancel = onDismiss, onDismissRequest = onDismiss,
        onAccept = { if (poll != null) { onSave(draft.copy(pollMillis = poll).normalized()); onDismiss() } }) { padding ->
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.65f).dp)
            .verticalScroll(rememberScrollState()).padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            N76Choice(stringResource(R.string.rc_n76_firmware), draft.firmwareMode,
                N76FirmwareMode.entries.map { mode -> mode to stringResource(when (mode) {
                    N76FirmwareMode.AUTO -> R.string.rc_n76_auto
                    N76FirmwareMode.SATELLITE -> R.string.rc_n76_satellite_firmware
                    N76FirmwareMode.EXACT -> R.string.rc_n76_exact_firmware
                }) }) { draft = draft.copy(firmwareMode = it) }
            N76Switch(stringResource(R.string.rc_n76_satellite_info), draft.sendSatelliteInfo) { draft = draft.copy(sendSatelliteInfo = it) }
            OutlinedTextField(interval, { interval = it }, label = { Text(stringResource(R.string.rc_n76_interval)) },
                isError = !valid, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            N76Choice(stringResource(R.string.rc_n76_power), draft.power, N76Power.entries.map { power ->
                power to stringResource(when (power) {
                    N76Power.UNCHANGED -> R.string.rc_n76_power_unchanged
                    N76Power.LOW -> R.string.rc_n76_power_low
                    N76Power.MEDIUM -> R.string.rc_n76_power_medium
                    N76Power.HIGH -> R.string.rc_n76_power_high
                })
            }) { draft = draft.copy(power = it) }
            N76Switch(stringResource(R.string.rc_n76_open_squelch), draft.openSquelchOnTrack) { draft = draft.copy(openSquelchOnTrack = it) }
            val follow = stringResource(R.string.rc_n76_follow)
            val off = stringResource(R.string.rc_n76_off)
            val tones = listOf<Pair<Double?, String>>(null to off) + N76_TONES.map { it to "$it Hz" }
            val rxChoice = if (draft.forceRxCtcss) draft.rxCtcssHz?.toString() ?: "off" else "follow"
            N76Choice(stringResource(R.string.rc_n76_rx_tone), rxChoice,
                listOf("follow" to follow) + tones.map { (tone, title) -> (tone?.toString() ?: "off") to title }) {
                draft = draft.copy(forceRxCtcss = it != "follow", rxCtcssHz = it.toDoubleOrNull())
            }
            val txChoice = if (draft.forceTxCtcss) draft.txCtcssHz?.toString() ?: "off" else "follow"
            N76Choice(stringResource(R.string.rc_n76_tx_tone), txChoice,
                listOf("follow" to follow) + tones.map { (tone, title) -> (tone?.toString() ?: "off") to title }) {
                draft = draft.copy(forceTxCtcss = it != "follow", txCtcssHz = it.toDoubleOrNull())
            }
            N76Switch(stringResource(R.string.rc_n76_speaker_monitor), draft.speakerMonitor, audioEnabled) { draft = draft.copy(speakerMonitor = it) }
            N76Switch(stringResource(R.string.rc_n76_record_radio), draft.recordRadio, audioEnabled || draft.recordRadio) { draft = draft.copy(recordRadio = it) }
            N76Switch(stringResource(R.string.rc_n76_record_mic), draft.recordMicrophone) {
                draft = draft.copy(recordMicrophone = it)
                if (it) microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            N76Switch(stringResource(R.string.rc_n76_record_on_track), draft.recordOnTrack) { draft = draft.copy(recordOnTrack = it) }
            N76Choice(stringResource(R.string.rc_n76_mic_input), draft.inputDeviceKey,
                listOf<Pair<String?, String>>(null to stringResource(R.string.rc_n76_auto)) + inputs.map { it.key to it.name }) {
                draft = draft.copy(inputDeviceKey = it)
            }
            N76Choice(stringResource(R.string.rc_n76_mic_gain), draft.micGainDb, listOf(0, 6, 12, 18, 24).map { it to "+$it dB" }) {
                draft = draft.copy(micGainDb = it)
            }
            OutlinedButton(onClick = { folder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (draft.outputFolderUri.isBlank()) R.string.rc_n76_choose_folder else R.string.rc_n76_change_folder))
            }
            if (folderError) Text(stringResource(R.string.rc_n76_folder_error))
            if (draft.outputFolderUri.isNotBlank()) OutlinedButton(onClick = { draft = draft.copy(outputFolderUri = "") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.rc_n76_default_folder))
            }
        }
    }
}

@Composable
private fun N76Switch(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun <T> N76Choice(title: String, selected: T, entries: List<Pair<T, String>>, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, Modifier.weight(1f))
                Text(entries.firstOrNull { it.first == selected }?.second.orEmpty(), Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
            entries.forEach { (value, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onChange(value); expanded = false }) }
        }
    }
}

private val N76_TONES = listOf(67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5, 94.8, 97.4,
    100.0, 103.5, 107.2, 110.9, 114.8, 118.8, 123.0, 127.3, 131.8, 136.5, 141.3, 146.2, 151.4, 156.7,
    159.8, 162.2, 165.5, 167.9, 171.3, 173.8, 177.3, 179.9, 183.5, 186.2, 189.9, 192.8, 196.6, 199.5,
    203.5, 206.5, 210.7, 218.1, 225.7, 229.1, 233.6, 241.8, 250.3, 254.1)

internal fun n76PollMillis(text: String): Long? = text.toLongOrNull()?.takeIf { it in 250..15_000 && it % 250 == 0L }
