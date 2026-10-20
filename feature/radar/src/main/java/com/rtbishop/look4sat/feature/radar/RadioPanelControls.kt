/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.feature.radar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import java.util.Locale

@Composable
internal fun ReferenceFrequencyControls(control: RadioControlSubState, onAction: (RadarAction) -> Unit) {
    var showEditor by rememberSaveable(control.selectedTransponderUuid) { mutableStateOf(false) }
    val action by rememberUpdatedState(onAction)
    val enabled = !control.frequencyEditingLocked && control.referenceRxHz != null
    val title = stringResource(R.string.rc_panel_reference)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button,
            onClickLabel = title, onClick = { showEditor = true })
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, maxLines = 1, softWrap = false,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.rc_panel_reference_pair,
                        control.referenceRxHz?.let(::referenceFrequencyText) ?: "—",
                        control.txBaseFrequencyHz?.let(::referenceFrequencyText) ?: "—"),
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    maxLines = 1, softWrap = false,
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace
                )
                Icon(painterResource(R.drawable.ic_edit), contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (control.calculatorLinked) Text(stringResource(R.string.rc_panel_linked),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                control.remoteRadio?.takeIf { it.capabilities?.satelliteProfiles == true }?.let { remote ->
                    Text(stringResource(if (control.referenceDirty || remote.profileStore?.dirty == true || !remote.nativeProfileValid)
                        R.string.rc_panel_unsaved else R.string.rc_panel_saved), style = MaterialTheme.typography.labelSmall)
                }
                if (control.referenceRxHz == null) Text(stringResource(R.string.rc_panel_waiting),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (showEditor) {
        ReferenceFrequencyEditor(control, onDismiss = { showEditor = false }, onSubmit = {
            action(RadarAction.SetBaseFrequencies(it))
            showEditor = false
        })
    }
}

@Composable
private fun ReferenceFrequencyEditor(
    control: RadioControlSubState, onDismiss: () -> Unit, onSubmit: (RadioBaseFrequencies) -> Unit
) {
    val txEnabled = control.txBaseFrequencyHz != null
    var rx by rememberSaveable { mutableStateOf(control.referenceRxHz?.let(::referenceFrequencyText).orEmpty()) }
    var tx by rememberSaveable { mutableStateOf(control.txBaseFrequencyHz?.let(::referenceFrequencyText).orEmpty()) }
    var target by rememberSaveable { mutableStateOf(if (txEnabled) ReferenceTarget.TX else ReferenceTarget.RX) }
    var error by remember { mutableStateOf(false) }
    val effectiveTarget = if (txEnabled) target else ReferenceTarget.RX
    val draft = ReferenceFrequencyDraft(rx, tx)
    SharedDialog(title = stringResource(R.string.rc_panel_reference), onDismissRequest = onDismiss, onCancel = onDismiss,
        acceptEnabled = !control.frequencyEditingLocked, onAccept = {
            if (!control.frequencyEditingLocked) {
                val bases = draft.bases(control.isL4sModel, txEnabled)
                if (bases == null) error = true else onSubmit(bases)
            }
        }) { padding ->
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp)
                .verticalScroll(rememberScrollState()).padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(value = rx, onValueChange = { rx = it; error = false },
                label = { Text(stringResource(R.string.rc_l4s_rx_reference)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = !control.frequencyEditingLocked, singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) target = ReferenceTarget.RX })
            OutlinedTextField(value = tx, onValueChange = { tx = it; error = false },
                label = { Text(stringResource(R.string.rc_l4s_tx_reference)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = !control.frequencyEditingLocked && txEnabled, singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) target = ReferenceTarget.TX })
            Text(stringResource(R.string.rc_panel_fine_tune), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReferenceTarget.entries.forEach { field ->
                    FilterChip(selected = effectiveTarget == field,
                        enabled = !control.frequencyEditingLocked && (field == ReferenceTarget.RX || txEnabled),
                        onClick = { target = field },
                        label = { Text(stringResource(if (field == ReferenceTarget.RX) R.string.rc_panel_rx else R.string.rc_panel_tx)) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
            }
            ReferenceAdjustmentButtons(draft, effectiveTarget, control) {
                rx = it.rx
                tx = it.tx
                error = false
            }
            if (error) Text(stringResource(R.string.rc_cat_invalid_reference), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ReferenceAdjustmentButtons(
    draft: ReferenceFrequencyDraft, target: ReferenceTarget, control: RadioControlSubState,
    onAdjust: (ReferenceFrequencyDraft) -> Unit
) {
    val adjustments = listOf(-10_000L to "−10k", -1_000L to "−1k", -100L to "−100",
        100L to "+100", 1_000L to "+1k", 10_000L to "+10k")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = referenceAdjustmentColumns(maxWidth.value, LocalDensity.current.fontScale)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            adjustments.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (delta, label) ->
                        val adjusted = draft.adjusted(target, delta, control.isL4sModel)
                        OutlinedButton(onClick = { adjusted?.let(onAdjust) },
                            enabled = !control.frequencyEditingLocked && adjusted != null,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TxToneControl(radio: SatRadio, control: RadioControlSubState, onAction: (RadarAction) -> Unit) {
    if (!showTxToneControl(radio.uplinkLow != null, control.txPanel.mode, radio.uplinkMode ?: radio.downlinkMode)) return
    var showChoices by rememberSaveable(radio.uuid) { mutableStateOf(false) }
    val action by rememberUpdatedState(onAction)
    val label = stringResource(R.string.rc_panel_tx_tone)
    OutlinedButton(onClick = { showChoices = true }, enabled = !control.frequencyEditingLocked,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        Text(toneText(control.ctcssTone), modifier = Modifier.padding(horizontal = 8.dp))
        Icon(painterResource(R.drawable.ic_arrow), contentDescription = null,
            modifier = Modifier.size(16.dp).rotate(270f))
    }
    if (showChoices) {
        SharedDialog(title = label, onDismissRequest = { showChoices = false }, onCancel = { showChoices = false }) { padding ->
            val tones = remember { listOf<Double?>(null) + RadarViewModel.CTCSS_TONES }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = tones.indexOf(control.ctcssTone).coerceAtLeast(0))
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.5f).dp)
                .selectableGroup(), contentPadding = PaddingValues(start = padding, end = padding, bottom = padding)) {
                items(tones, key = { it?.toString() ?: "off" }) { tone ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = control.ctcssTone == tone, enabled = !control.frequencyEditingLocked,
                                role = Role.RadioButton, onClick = {
                                    if (!control.frequencyEditingLocked) {
                                        action(RadarAction.SetCtcssTone(tone))
                                        showChoices = false
                                    }
                                }).padding(vertical = 8.dp)) {
                        RadioButton(selected = control.ctcssTone == tone, onClick = null, enabled = !control.frequencyEditingLocked)
                        Text(toneText(tone), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun toneText(tone: Double?): String = if (tone == null) stringResource(R.string.rc_panel_tone_off)
    else stringResource(R.string.rc_panel_tone_hz, String.format(Locale.US, "%.1f", tone))

@Composable
internal fun RadioTrackingButtons(control: RadioControlSubState, onAction: (RadarAction) -> Unit) {
    val connected = control.txPanel.isConnected || control.rxPanel.isConnected
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = stackRadioActionButtons(maxWidth.value, LocalDensity.current.fontScale)
        val connect: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedButton(onClick = { onAction(if (connected) RadarAction.DisconnectRadios else RadarAction.ConnectRadios) },
                modifier = modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                Text(stringResource(if (connected) R.string.rc_panel_disconnect else R.string.rc_panel_connect))
            }
        }
        val track: @Composable (Modifier) -> Unit = { modifier ->
            Button(onClick = { onAction(RadarAction.ToggleTracking) },
                enabled = control.isTracking || !control.frequencyEditingLocked,
                modifier = modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                Text(stringResource(if (control.isTracking) R.string.rc_panel_stop else R.string.rc_panel_track), fontWeight = FontWeight.Medium)
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                connect(Modifier.fillMaxWidth())
                track(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                connect(Modifier.weight(1f))
                track(Modifier.weight(1f))
            }
        }
    }
}
