/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.feature.radar

import android.app.Activity
import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.widget.ConstraintLayout
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.formatFrequency
import com.rtbishop.look4sat.core.presentation.infiniteMarquee
import com.rtbishop.look4sat.feature.cw.R as CwR
import com.ve3nea.morse_expert.MainActivity
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun TransceiversPage(
    transceivers: List<SatRadio>,
    selectedUuid: String?,
    radioControl: RadioControlSubState,
    onAction: (RadarAction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (transceivers.isEmpty()) {
        EmptyTransceiversContent(modifier)
    } else {
        val listState = rememberLazyListState()
        // Snap the expanded item to the top of the visible area
        LaunchedEffect(selectedUuid) {
            if (selectedUuid != null) {
                val index = transceivers.indexOfFirst { it.uuid == selectedUuid }
                if (index >= 0) {
                    kotlinx.coroutines.delay(300.milliseconds)
                    listState.animateScrollToItem(index)
                }
            }
        }
        LazyColumn(modifier = modifier.fillMaxSize(), state = listState) {
            itemsIndexed(items = transceivers, key = { _, radio -> radio.uuid }) { _, radio ->
                val isExpanded = radio.uuid == selectedUuid
                TransceiverItem(
                    radio = radio,
                    isExpanded = isExpanded,
                    radioControl = radioControl,
                    onAction = onAction,
                    onToggle = { onAction(RadarAction.SelectTransmitter(radio.uuid)) }
                )
            }
        }
    }
}

@Composable
fun CalculatorPage(
    transceivers: List<SatRadio>,
    selectedUuid: String?,
    orbitalPos: OrbitalPos?,
    cw: CwSubState,
    onAction: (RadarAction) -> Unit,
    requestMicPermission: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val calculatorTransceivers = remember(transceivers) {
        transceivers.filter(DopplerFrequencyCalculator::isNamedLinearTransponder)
    }
    val selectedTransceiver = remember(calculatorTransceivers, selectedUuid) {
        calculatorTransceivers.firstOrNull { it.uuid == selectedUuid }
            ?: calculatorTransceivers.firstOrNull()
    }

    if (selectedTransceiver == null) {
        EmptyTransceiversContent(modifier)
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (calculatorTransceivers.size > 1) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    calculatorTransceivers.forEach { radio ->
                        FilterChip(
                            selected = radio.uuid == selectedTransceiver.uuid,
                            onClick = {
                                if (radio.uuid != selectedUuid) onAction(RadarAction.SelectTransmitter(radio.uuid))
                            },
                            label = {
                                Text(
                                    text = transceiverTitle(radio),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
        }

        item {
            DopplerFrequencyCalculator(
                transponder = selectedTransceiver,
                orbitalPos = orbitalPos,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }

        item {
            CwDecoderPanel(
                cw = cw,
                onAction = onAction,
                requestMicPermission = requestMicPermission,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EmptyTransceiversContent(modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(text = """¯\_(ツ)_/¯""", fontSize = 32.sp)
            Text(
                text = stringResource(R.string.empty_list_message),
                fontSize = 21.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.radar_no_data),
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TransceiverItem(
    radio: SatRadio,
    isExpanded: Boolean,
    radioControl: RadioControlSubState,
    onAction: (RadarAction) -> Unit,
    onToggle: () -> Unit
) {
    val bgColor = if (isExpanded) MaterialTheme.colorScheme.surfaceContainerHighest
    else MaterialTheme.colorScheme.surface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggle() }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Header: [arrow slot] [name - (mode)] [icon slot]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Left icon slot — always reserved
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(if (isExpanded) 270f else 90f)
                    )
                }
                // Title with mode
                Text(
                    text = transceiverTitle(radio),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .infiniteMarquee()
                )
                // Right arrow slot — always reserved
                val iconTint = if (isExpanded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_radios),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Frequency rows
            UnifiedFrequencyRow(
                label = "TX",
                frequencyLow = radio.uplinkLow,
                frequencyHigh = radio.uplinkHigh,
                isConnected = if (isExpanded) radioControl.txPanel.isConnected else null
            )
            UnifiedFrequencyRow(
                label = "RX",
                frequencyLow = radio.downlinkLow,
                frequencyHigh = radio.downlinkHigh,
                isConnected = if (isExpanded) radioControl.rxPanel.isConnected else null
            )
        }

        // Expanded CAT control area
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            ExpandedRadioControl(
                radio = radio,
                radioControl = radioControl,
                onAction = onAction
            )
        }

        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.background)
    }
}

@Composable
private fun UnifiedFrequencyRow(
    label: String,
    frequencyLow: Long?,
    frequencyHigh: Long?,
    isConnected: Boolean?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // TX:/RX: label
        Text(
            text = "$label:",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp)
        )
        // Low frequency
        FrequencyText(
            frequency = frequencyLow,
            modifier = Modifier.weight(1f)
        )
        // Dash — always centered
        Text(
            text = "–",
            textAlign = TextAlign.Center,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(16.dp)
        )
        // High frequency
        FrequencyText(
            frequency = frequencyHigh,
            modifier = Modifier.weight(1f)
        )
        // Connection dot on the right
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.width(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when (isConnected) {
                            true -> Color(0xFF4CAF50)
                            false -> Color(0xFFE57373)
                            null -> MaterialTheme.colorScheme.outlineVariant
                        }
                    )
            )
        }
    }
}

@Composable
private fun ExpandedRadioControl(
    radio: SatRadio,
    radioControl: RadioControlSubState,
    onAction: (RadarAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )

        // Frequency tuner
        if (radioControl.txBaseFrequencyHz != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "TX Base: ",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${formatFrequency(radioControl.txBaseFrequencyHz)} MHz",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val upLow = radio.uplinkLow
                val upHigh = radio.uplinkHigh
                if (upLow != null && upHigh != null && upLow != upHigh) {
                    Text(
                        text = "(${formatFrequency(upLow)} – ${formatFrequency(upHigh)})",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FREQ_ADJUSTMENTS.forEach { (delta, label) ->
                        CardButton(
                            onClick = { onAction(RadarAction.AdjustTxFrequency(delta)) },
                            text = label,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // CTCSS (only for FM uplink)
        if (radio.uplinkMode?.uppercase() == "FM") {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "CTCSS",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val chipModifier = Modifier.width(64.dp)
                    FilterChip(
                        selected = radioControl.ctcssTone == null,
                        onClick = { onAction(RadarAction.SetCtcssTone(null)) },
                        label = {
                            Text(
                                text = "Off",
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = chipModifier
                    )
                    RadarViewModel.CTCSS_TONES.forEach { tone ->
                        FilterChip(
                            selected = radioControl.ctcssTone == tone,
                            onClick = { onAction(RadarAction.SetCtcssTone(tone)) },
                            label = {
                                Text(
                                    text = String.format(Locale.ENGLISH, "%.1f", tone),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            modifier = chipModifier
                        )
                    }
                }
            }
        }

        // Control buttons
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!radioControl.txPanel.isConnected && !radioControl.rxPanel.isConnected) {
                CardButton(
                    onClick = { onAction(RadarAction.ConnectRadios) },
                    text = "Connect",
                    modifier = Modifier.weight(1f)
                )
            } else {
                CardButton(
                    onClick = { onAction(RadarAction.DisconnectRadios) },
                    text = "Disconnect",
                    modifier = Modifier.weight(1f)
                )
            }
            CardButton(
                onClick = { onAction(RadarAction.ToggleTracking) },
                text = if (radioControl.isTracking) "Stop" else "Track",
                modifier = Modifier.weight(1f)
            )
        }

        // Error
        radioControl.errorMessage?.let { msg ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(text = msg, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun DopplerFrequencyCalculator(
    transponder: SatRadio,
    orbitalPos: OrbitalPos?,
    modifier: Modifier = Modifier
) {
    if (orbitalPos == null || !DopplerFrequencyCalculator.isLinearTransponder(transponder)) return

    var txInputMHz by remember { mutableStateOf("") }
    var rxInputMHz by remember { mutableStateOf("") }
    var offsetKHz by remember { mutableStateOf("") }
    var lastEditedBy by remember { mutableStateOf(EditedField.TX) }

    val offsetHz = offsetKHz.toDoubleOrNull()?.let { it * 1000 }?.toLong() ?: 0L

    // Real-time refresh: when orbitalPos changes (1Hz), recompute the opposite field
    LaunchedEffect(orbitalPos) {
        if (lastEditedBy == EditedField.TX) {
            val txMHz = txInputMHz.toDoubleOrNull()
            if (txMHz != null && txMHz > 0) {
                val txHz = (txMHz * 1_000_000).toLong()
                val rxHz = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(
                    txHz, transponder, orbitalPos, offsetHz
                )
                if (rxHz != null) {
                    rxInputMHz = String.format(Locale.ENGLISH, "%.6f", rxHz / 1_000_000.0)
                }
            }
        } else {
            val rxMHz = rxInputMHz.toDoubleOrNull()
            if (rxMHz != null && rxMHz > 0) {
                val rxHz = (rxMHz * 1_000_000).toLong()
                val txHz = DopplerFrequencyCalculator.computeUplinkFromDownlinkWithOffset(
                    rxHz, transponder, orbitalPos, offsetHz
                )
                if (txHz != null) {
                    txInputMHz = String.format(Locale.ENGLISH, "%.6f", txHz / 1_000_000.0)
                }
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.radar_doppler_calc),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.radar_doppler_info),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Offset input
        OutlinedTextField(
            value = offsetKHz,
            onValueChange = { newVal ->
                offsetKHz = newVal
                // Trigger recompute based on last edited field
                if (lastEditedBy == EditedField.TX) {
                    val txMHz = txInputMHz.toDoubleOrNull()
                    if (txMHz != null && txMHz > 0) {
                        val txHz = (txMHz * 1_000_000).toLong()
                        val rxHz = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(
                            txHz, transponder, orbitalPos, offsetHz
                        )
                        rxInputMHz = if (rxHz != null) String.format(Locale.ENGLISH, "%.6f", rxHz / 1_000_000.0) else ""
                    }
                } else {
                    val rxMHz = rxInputMHz.toDoubleOrNull()
                    if (rxMHz != null && rxMHz > 0) {
                        val rxHz = (rxMHz * 1_000_000).toLong()
                        val txHz = DopplerFrequencyCalculator.computeUplinkFromDownlinkWithOffset(
                            rxHz, transponder, orbitalPos, offsetHz
                        )
                        txInputMHz = if (txHz != null) String.format(Locale.ENGLISH, "%.6f", txHz / 1_000_000.0) else ""
                    }
                }
            },
            label = { Text(stringResource(R.string.radar_doppler_offset_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth()
        )

        // TX and RX inputs on the same row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // TX input → compute RX
            OutlinedTextField(
                value = txInputMHz,
                onValueChange = { newVal ->
                    txInputMHz = newVal
                    lastEditedBy = EditedField.TX
                    val mhz = newVal.toDoubleOrNull()
                    if (mhz != null && mhz > 0) {
                        val txHz = (mhz * 1_000_000).toLong()
                        val rxHz = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(
                            txHz, transponder, orbitalPos, offsetHz
                        )
                        rxInputMHz = if (rxHz != null) String.format(Locale.ENGLISH, "%.6f", rxHz / 1_000_000.0) else ""
                    } else if (newVal.isEmpty()) {
                        rxInputMHz = ""
                    }
                },
                label = { Text(stringResource(R.string.radar_doppler_tx_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )

            // RX input → compute TX
            OutlinedTextField(
                value = rxInputMHz,
                onValueChange = { newVal ->
                    rxInputMHz = newVal
                    lastEditedBy = EditedField.RX
                    val mhz = newVal.toDoubleOrNull()
                    if (mhz != null && mhz > 0) {
                        val rxHz = (mhz * 1_000_000).toLong()
                        val txHz = DopplerFrequencyCalculator.computeUplinkFromDownlinkWithOffset(
                            rxHz, transponder, orbitalPos, offsetHz
                        )
                        txInputMHz = if (txHz != null) String.format(Locale.ENGLISH, "%.6f", txHz / 1_000_000.0) else ""
                    } else if (newVal.isEmpty()) {
                        txInputMHz = ""
                    }
                },
                label = { Text(stringResource(R.string.radar_doppler_rx_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private enum class EditedField { TX, RX }

@Composable
private fun CwDecoderPanel(
    cw: CwSubState,
    onAction: (RadarAction) -> Unit,
    requestMicPermission: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Header row: expand/collapse toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAction(RadarAction.CwToggleExpanded(!cw.isExpanded)) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.radar_cw_decoder),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                painter = painterResource(id = R.drawable.ic_arrow),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (cw.isExpanded) 270f else 90f)
            )
        }

        AnimatedVisibility(visible = cw.isExpanded) {
            // PR #1 Morse Expert engine: mini layout with waterfall + decoded text.
            // Keep the old Kotlin decoder state/actions as fallback code, but this panel no longer feeds it.
            val context = LocalContext.current
            val activity = remember { context as? Activity }
            val controller = remember { MainActivity() }
            val rootView = remember {
                LayoutInflater.from(context).inflate(CwR.layout.cw_panel_main, null) as ConstraintLayout
            }
            var initialized by remember { mutableStateOf(false) }
            var listening by remember { mutableStateOf(false) }

            DisposableEffect(Unit) {
                if (activity != null) {
                    controller.onCreate(activity, rootView, false)
                }
                onDispose {
                    controller.onPause()
                    controller.onDestroy()
                }
            }

            LaunchedEffect(cw.hasPermission) {
                if (cw.hasPermission && !initialized) {
                    controller.onPermissionGranted()
                    controller.onResume()
                    initialized = true
                    listening = true
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!listening) {
                        Button(
                            onClick = {
                                if (!cw.hasPermission) {
                                    requestMicPermission()
                                } else if (!initialized) {
                                    controller.onPermissionGranted()
                                    controller.onResume()
                                    initialized = true
                                    listening = true
                                } else {
                                    controller.onResume()
                                    listening = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.radar_cw_start))
                        }
                    } else {
                        Button(
                            onClick = {
                                controller.onPause()
                                listening = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.radar_cw_stop))
                        }
                    }
                    OutlinedButton(
                        onClick = { controller.clearDecoded() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.radar_cw_reset))
                    }
                }

                AndroidView(
                    factory = { rootView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                )
            }
        }
    }
}

@Composable
private fun FrequencyText(frequency: Long?, modifier: Modifier = Modifier) {
    val text = frequency?.let {
        stringResource(id = R.string.radar_link_low, it / 1000000f)
    } ?: stringResource(R.string.radar_no_link)
    Text(
        text = text,
        textAlign = TextAlign.Center,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

private fun transceiverTitle(radio: SatRadio): String {
    val title = if (radio.isInverted) "INV: ${radio.info}" else radio.info
    val mode = "${radio.downlinkMode ?: "--"}/${radio.uplinkMode ?: "--"}"
    return "$title ($mode)"
}

private val FREQ_ADJUSTMENTS =
    listOf(-10_000L to "-10k", -1_000L to "-1k", -100L to "-100", 100L to "+100", 1_000L to "+1k", 10_000L to "+10k")
