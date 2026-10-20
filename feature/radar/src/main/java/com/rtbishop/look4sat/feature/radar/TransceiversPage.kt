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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
import com.rtbishop.look4sat.core.domain.utility.CalculatorTuning
import com.rtbishop.look4sat.core.domain.utility.CalculatorFrequencyAnchor
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
        // contentPadding for the system navigation bar: the radar page is a
        // full-screen destination, so on gesture-nav devices the translucent
        // nav bar floats over the list bottom and covers the CW decoder's
        // decoded-text output when the panel is scrolled to the end. Padding
        // the last item above the bar fixes it (three-button nav already
        // resizes the window, where this padding is a harmless no-op).
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                bottom = with(LocalDensity.current) {
                    (WindowInsets.navigationBars.getBottom(this) / density).dp
                }
            )
        ) {
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
    calculatorOffsetKHz: String = "",
    radioControl: RadioControlSubState = RadioControlSubState(),
    onAction: (RadarAction) -> Unit,
    requestMicPermission: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val calculatorTransceivers = remember(transceivers) {
        transceivers.filter(DopplerFrequencyCalculator::isNamedLinearTransponder)
            .let(DopplerFrequencyCalculator::deduplicateTransponders)
    }
    val selectedTransceiver = remember(calculatorTransceivers, selectedUuid) {
        calculatorTransceivers.firstOrNull { it.uuid == selectedUuid }
            ?: calculatorTransceivers.firstOrNull()
    }

    // Keep the per-satellite offset in sync with the transponder driving the
    // calculator: load the saved value on open/satellite change, and remember
    // its catnum so editing saves even when no transceiver is selected
    LaunchedEffect(selectedTransceiver?.uuid) {
        onAction(RadarAction.CalculatorTransponderChanged(selectedTransceiver?.catnum))
    }

    if (selectedTransceiver == null) {
        EmptyTransceiversContent(modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (calculatorTransceivers.size > 1) {
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

        if (radioControl.isL4sModel || radioControl.txPanel.isConnected || radioControl.rxPanel.isConnected) {
            Text(stringResource(if(radioControl.frequencyEditingLocked && selectedTransceiver.uuid==radioControl.selectedTransponderUuid &&
                    (radioControl.txPanel.isConnected || radioControl.rxPanel.isConnected))R.string.rc_l4s_edit_locked
                else if (radioControl.isTracking) R.string.rc_calculator_cat_link else R.string.rc_calculator_need_tracking),
                style = MaterialTheme.typography.bodySmall)
        }
        DopplerFrequencyCalculator(
            transponder = selectedTransceiver,
            orbitalPos = orbitalPos,
            offsetKHz = calculatorOffsetKHz,
            onOffsetChange = { onAction(RadarAction.ChangeCalculatorOffset(it)) },
            onFrequencyChange = { tx, rx -> onAction(RadarAction.UpdateCalculatorFrequency(tx, rx)) },
            onTuningChange = { if(!radioControl.frequencyEditingLocked && (radioControl.txPanel.isConnected || radioControl.rxPanel.isConnected))onAction(RadarAction.TuneCalculator(it)) },
            initialTxBaseHz = radioControl.txBaseFrequencyHz.takeIf { selectedTransceiver.uuid == selectedUuid },
            editingEnabled=!radioControl.frequencyEditingLocked || selectedTransceiver.uuid!=radioControl.selectedTransponderUuid ||
                (!radioControl.txPanel.isConnected && !radioControl.rxPanel.isConnected),
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )

        CwDecoderPanel(
            cw = cw,
            onAction = onAction,
            requestMicPermission = requestMicPermission,
            modifier = Modifier.fillMaxWidth()
        )
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        ReferenceFrequencyControls(radioControl, onAction)
        TxToneControl(radio, radioControl, onAction)
        if (radioControl.isL4sModel || radioControl.remoteRadio?.capabilities?.let { it.cwText || it.voicePtt } == true) {
            RemoteRadioControls(radioControl, onAction)
        }
        RadioTrackingButtons(radioControl, onAction)
        radioControl.errorMessage?.let { message ->
            Text(message, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private enum class EditedField { TX, PASSBAND, RX }

private const val MAX_CALCULATOR_OFFSET_KHZ = 10_000.0
private val CALCULATOR_OFFSET_PATTERN = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")

private fun isValidCalculatorOffset(value: String): Boolean {
    if (value in setOf("", "+", "-", ".", "+.", "-.")) return true
    if (value.length > 12) return false
    if (!CALCULATOR_OFFSET_PATTERN.matches(value)) return false
    val parsed = value.toDoubleOrNull() ?: return false
    return parsed.isFinite() && kotlin.math.abs(parsed) <= MAX_CALCULATOR_OFFSET_KHZ
}

@Composable
private fun DopplerFrequencyCalculator(
    transponder: SatRadio,
    orbitalPos: OrbitalPos?,
    offsetKHz: String = "",
    onOffsetChange: (String) -> Unit = {},
    onFrequencyChange: (Long, Long) -> Unit = { _, _ -> },
    onTuningChange: (CalculatorTuning) -> Unit = {},
    initialTxBaseHz: Long? = null,
    editingEnabled:Boolean=true,
    modifier: Modifier = Modifier
) {
    if (orbitalPos == null || !DopplerFrequencyCalculator.isLinearTransponder(transponder)) return

    var lastEditedField by rememberSaveable(transponder.uuid) { mutableStateOf(EditedField.TX) }
    var txFrequencyHz by rememberSaveable(transponder.uuid) { mutableStateOf(0L) }
    var rxFrequencyHz by rememberSaveable(transponder.uuid) { mutableStateOf(0L) }
    var passbandPosition by rememberSaveable(transponder.uuid) { mutableStateOf(0.5f) }
    var stepSizeKHz by remember { mutableIntStateOf(1) }

    // Keep the live log page aligned with the calculator frequencies.
    SideEffect {
        if (txFrequencyHz != 0L && rxFrequencyHz != 0L) onFrequencyChange(txFrequencyHz, rxFrequencyHz)
    }

    val offsetHz = DopplerFrequencyCalculator.parseOffsetHz(offsetKHz)

    val nominalTxLow = transponder.uplinkLow ?: return
    val nominalTxHigh = transponder.uplinkHigh ?: return
    val nominalTxRange = nominalTxHigh - nominalTxLow
    if (nominalTxRange <= 0L) return

    val txGroundRange = DopplerFrequencyCalculator.groundUplinkRange(transponder, orbitalPos) ?: return
    val rxGroundRange = DopplerFrequencyCalculator.groundDownlinkRangeWithOffset(
        transponder,
        orbitalPos,
        offsetHz
    ) ?: return
    val txLow = txGroundRange.first
    val txHigh = txGroundRange.last
    val rxLow = rxGroundRange.first
    val rxHigh = rxGroundRange.last
    val txRange = txHigh - txLow
    val rxRange = rxHigh - rxLow
    if (txRange <= 0L || rxRange <= 0L) return

    fun txAtPassbandPosition(position: Float): Long {
        val nominal = nominalTxLow + (position.coerceIn(0f, 1f) * nominalTxRange).toLong()
        return orbitalPos.getUplinkFreq(nominal).coerceIn(txLow, txHigh)
    }

    fun downlinkFor(txHz: Long): Long? =
        DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(
            txHz,
            transponder,
            orbitalPos,
            offsetHz
        )?.coerceIn(rxLow, rxHigh)

    fun uplinkFor(rxHz: Long): Long? =
        DopplerFrequencyCalculator.computeUplinkFromDownlinkWithOffset(
            rxHz,
            transponder,
            orbitalPos,
            offsetHz
        )?.coerceIn(txLow, txHigh)

    fun txPosition(txHz: Long): Float =
        ((txHz - txLow).toFloat() / txRange).coerceIn(0f, 1f)

    // Recalculate whenever range rate or offset changes. TX/RX mode preserves the user's
    // ground-frequency anchor; passband mode preserves its satellite-passband position.
    LaunchedEffect(
        transponder.uuid,
        transponder.uplinkLow,
        transponder.uplinkHigh,
        transponder.downlinkLow,
        transponder.downlinkHigh,
        transponder.isInverted,
        orbitalPos.distanceRate,
        offsetHz
    ) {
        if (txFrequencyHz == 0L || rxFrequencyHz == 0L) {
            passbandPosition = initialTxBaseHz?.let { ((it-nominalTxLow).toFloat()/nominalTxRange).coerceIn(0f,1f) } ?: 0.5f
            txFrequencyHz = txAtPassbandPosition(passbandPosition)
            rxFrequencyHz = downlinkFor(txFrequencyHz) ?: (rxLow + rxRange / 2)
        } else {
            when (lastEditedField) {
                EditedField.TX -> {
                    txFrequencyHz = txFrequencyHz.coerceIn(txLow, txHigh)
                    rxFrequencyHz = downlinkFor(txFrequencyHz) ?: rxFrequencyHz.coerceIn(rxLow, rxHigh)
                }
                EditedField.RX -> {
                    rxFrequencyHz = rxFrequencyHz.coerceIn(rxLow, rxHigh)
                    txFrequencyHz = uplinkFor(rxFrequencyHz) ?: txFrequencyHz.coerceIn(txLow, txHigh)
                }
                EditedField.PASSBAND -> {
                    txFrequencyHz = txAtPassbandPosition(passbandPosition)
                    rxFrequencyHz = downlinkFor(txFrequencyHz) ?: rxFrequencyHz.coerceIn(rxLow, rxHigh)
                }
            }
        }
    }

    val txSliderValue = if (lastEditedField == EditedField.PASSBAND) {
        passbandPosition
    } else {
        txPosition(txFrequencyHz)
    }
    val rxSliderValue = ((rxFrequencyHz - rxLow).toFloat() / rxRange).coerceIn(0f, 1f)

    fun publishTuning() {
        if(!editingEnabled)return
        val anchor = when(lastEditedField) {
            EditedField.TX -> CalculatorFrequencyAnchor.GROUND_TX
            EditedField.RX -> CalculatorFrequencyAnchor.GROUND_RX
            EditedField.PASSBAND -> CalculatorFrequencyAnchor.SATELLITE
        }
        val hz = when(lastEditedField) {
            EditedField.TX -> txFrequencyHz
            EditedField.RX -> rxFrequencyHz
            EditedField.PASSBAND -> nominalTxLow + (passbandPosition.coerceIn(0f,1f)*nominalTxRange).toLong()
        }
        onTuningChange(CalculatorTuning(transponder.uuid,anchor,hz))
    }

    fun updateTx(newTxHz: Long) {
        if(!editingEnabled)return
        when (lastEditedField) {
            EditedField.PASSBAND -> {
                passbandPosition = txPosition(newTxHz)
                txFrequencyHz = txAtPassbandPosition(passbandPosition)
                rxFrequencyHz = downlinkFor(txFrequencyHz) ?: rxFrequencyHz
            }
            else -> {
                txFrequencyHz = newTxHz.coerceIn(txLow, txHigh)
                rxFrequencyHz = downlinkFor(txFrequencyHz) ?: rxFrequencyHz
            }
        }
        publishTuning()
    }

    fun updateRx(newRxHz: Long) {
        if(!editingEnabled)return
        when (lastEditedField) {
            EditedField.PASSBAND -> {
                val txFromRx = uplinkFor(newRxHz)
                passbandPosition = if (txFromRx != null) {
                    txPosition(txFromRx)
                } else {
                    passbandPosition
                }
                txFrequencyHz = txAtPassbandPosition(passbandPosition)
                rxFrequencyHz = downlinkFor(txFrequencyHz) ?: rxFrequencyHz
            }
            else -> {
                rxFrequencyHz = newRxHz.coerceIn(rxLow, rxHigh)
                txFrequencyHz = uplinkFor(rxFrequencyHz) ?: txFrequencyHz
            }
        }
        publishTuning()
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 左半边: TX + RX 按钮
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = lastEditedField == EditedField.TX,
                        enabled=editingEnabled,
                        onClick = {
                            when (lastEditedField) {
                                EditedField.TX -> {
                                    passbandPosition = txPosition(txFrequencyHz)
                                    lastEditedField = EditedField.PASSBAND
                                    txFrequencyHz = txAtPassbandPosition(passbandPosition)
                                    rxFrequencyHz = downlinkFor(txFrequencyHz) ?: rxFrequencyHz
                                }
                                EditedField.PASSBAND -> { lastEditedField = EditedField.TX }
                                else -> { lastEditedField = EditedField.TX }
                            }
                            publishTuning()
                        },
                        label = {
                            Text("TX", fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth())
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    FilterChip(
                        selected = lastEditedField == EditedField.RX,
                        enabled=editingEnabled,
                        onClick = {
                            when (lastEditedField) {
                                EditedField.RX -> {
                                    txFrequencyHz = uplinkFor(rxFrequencyHz) ?: txFrequencyHz
                                    passbandPosition = txPosition(txFrequencyHz)
                                    txFrequencyHz = txAtPassbandPosition(passbandPosition)
                                    rxFrequencyHz = downlinkFor(txFrequencyHz) ?: rxFrequencyHz
                                    lastEditedField = EditedField.PASSBAND
                                }
                                EditedField.PASSBAND -> {
                                    lastEditedField = EditedField.RX
                                    txFrequencyHz = uplinkFor(rxFrequencyHz) ?: txFrequencyHz
                                }
                                else -> { lastEditedField = EditedField.RX }
                            }
                            publishTuning()
                        },
                        label = {
                            Text("RX", fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth())
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                // 右半边: Offset 输入框（带细线边框）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center
                ) {
                    BasicTextField(
                        value = offsetKHz,
                        enabled=editingEnabled,
                        onValueChange = { value ->
                            if (isValidCalculatorOffset(value)) onOffsetChange(value)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        decorationBox = { innerTextField ->
                            if (offsetKHz.isEmpty()) {
                                Text(
                                    text = "Offset (kHz)",
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            // TX 行
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { updateTx(txFrequencyHz - stepSizeKHz * 1000L) },
                        enabled=editingEnabled,
                        modifier = Modifier.width(32.dp).height(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    ) { Text("−", fontSize = 14.sp) }

                    Slider(
                        value = txSliderValue,
                        enabled=editingEnabled,
                        onValueChange = { norm ->
                            val rawFreq = txLow + (txRange * norm).toLong()
                            val snapped = ((rawFreq + 500) / 1000L) * 1000L
                            updateTx(snapped.coerceIn(txLow, txHigh))
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).height(20.dp).padding(horizontal = 4.dp)
                    )

                    OutlinedButton(
                        onClick = { updateTx(txFrequencyHz + stepSizeKHz * 1000L) },
                        enabled=editingEnabled,
                        modifier = Modifier.width(32.dp).height(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    ) { Text("+", fontSize = 14.sp) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(Locale.ENGLISH, "%.4f", txLow / 1_000_000.0),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = String.format(Locale.ENGLISH, "%.6f", txFrequencyHz / 1_000_000.0),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = " MHz",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = String.format(Locale.ENGLISH, "%.4f", txHigh / 1_000_000.0),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )

            // RX 行
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { updateRx(rxFrequencyHz - stepSizeKHz * 1000L) },
                        enabled=editingEnabled,
                        modifier = Modifier.width(32.dp).height(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    ) { Text("−", fontSize = 14.sp) }

                    Slider(
                        value = rxSliderValue,
                        enabled=editingEnabled,
                        onValueChange = { norm ->
                            val rawFreq = rxLow + (rxRange * norm).toLong()
                            val snapped = ((rawFreq + 500) / 1000L) * 1000L
                            updateRx(snapped.coerceIn(rxLow, rxHigh))
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).height(20.dp).padding(horizontal = 4.dp)
                    )

                    OutlinedButton(
                        onClick = { updateRx(rxFrequencyHz + stepSizeKHz * 1000L) },
                        enabled=editingEnabled,
                        modifier = Modifier.width(32.dp).height(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    ) { Text("+", fontSize = 14.sp) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(Locale.ENGLISH, "%.4f", rxLow / 1_000_000.0),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = String.format(Locale.ENGLISH, "%.6f", rxFrequencyHz / 1_000_000.0),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = " MHz",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = String.format(Locale.ENGLISH, "%.4f", rxHigh / 1_000_000.0),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
}

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
            val activity = remember(context) {
                context as? Activity ?: error("CwDecoderPanel must be hosted in an Activity")
            }
            val controller = remember { MainActivity() }
            val rootView = remember(context) {
                LayoutInflater.from(context).inflate(CwR.layout.activity_main, null) as ConstraintLayout
            }
            var initialized by remember { mutableStateOf(false) }
            var listening by remember { mutableStateOf(false) }

            // 重要: 不在展开时初始化控制器(避免"一展开就崩溃")。
            // controller.onCreate/onResume/onPermissionGranted 全部推迟到用户点 Start 才执行。
            fun startDecoding() {
                if (!initialized) {
                    controller.onCreate(activity, rootView)
                    controller.onPermissionGranted()
                    controller.onResume()
                    initialized = true
                } else {
                    controller.onResume()
                }
                listening = true
            }

            DisposableEffect(Unit) {
                onDispose {
                    if (initialized) {
                        controller.onPause()
                        controller.onDestroy()
                    }
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
                                } else {
                                    startDecoding()
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
                        onClick = { if (initialized) controller.clearDecoded() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.radar_cw_reset))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                ) {
                    AndroidView(
                        factory = { rootView },
                        modifier = Modifier.fillMaxSize()
                    )
                }
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
