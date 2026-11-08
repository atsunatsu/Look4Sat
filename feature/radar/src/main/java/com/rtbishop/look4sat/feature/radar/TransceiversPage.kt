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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator
import com.rtbishop.look4sat.core.domain.utility.TransponderMapper
import com.rtbishop.look4sat.core.domain.utility.CalculatorTuning
import com.rtbishop.look4sat.core.domain.utility.CalculatorFrequencyAnchor
import com.rtbishop.look4sat.core.domain.utility.CalculatorReference
import com.rtbishop.look4sat.core.domain.utility.withCalculatorReference
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.infiniteMarquee
import java.math.BigDecimal
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.roundToLong

@Composable
fun TransceiversPage(
    transceivers: List<SatRadio>,
    selectedUuid: String?,
    favoriteUuids: Set<String> = emptySet(),
    radioControl: RadioControlSubState,
    onAction: (RadarAction) -> Unit,
    connectRadios: () -> Unit,
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
        // nav bar floats over the bottom of the list. Padding the last item
        // above the bar avoids obscuring the final transponder row (three-button
        // nav already resizes the window, where this padding is a harmless no-op).
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
                    isFavorite = radio.uuid in favoriteUuids,
                    onToggleFavorite = { radio.catnum?.let { onAction(RadarAction.ToggleFavoriteTransponder(radio.uuid, it)) } },
                    onAction = { action ->
                        if (action == RadarAction.ConnectRadios) connectRadios() else onAction(action)
                    },
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
            initialTxBaseHz = radioControl.txBaseFrequencyHz.takeIf { selectedTransceiver.uuid == radioControl.selectedTransponderUuid },
            reference = radioControl.calculatorReferenceFor(selectedTransceiver.uuid,
                DopplerFrequencyCalculator.parseOffsetHz(calculatorOffsetKHz)),
            editingEnabled=!radioControl.frequencyEditingLocked || selectedTransceiver.uuid!=radioControl.selectedTransponderUuid ||
                (!radioControl.txPanel.isConnected && !radioControl.rxPanel.isConnected),
            // Show the live Doppler-corrected TX/RX frequencies and limits on this page.
            displayNominalFrequencies = false,
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
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
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
                // Favorite toggle is satellite-specific and independent of row expansion.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onToggleFavorite() }
                ) {
                    Text(
                        text = if (isFavorite) "★" else "☆",
                        color = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 22.sp,
                        lineHeight = 24.sp
                    )
                }
                val iconTint = if (isExpanded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
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
        if (DopplerFrequencyCalculator.isNamedLinearTransponder(radio)) {
            OutlinedButton(
                onClick = { onAction(RadarAction.OpenCwConsole) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.rc_l4s_cw))
            }
        }
        if (!radioControl.catControlsActive) {
            Text(
                stringResource(if (!radioControl.catEnabled) R.string.rc_cat_disabled_status else R.string.rc_vox_status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (radioControl.remoteControlsVisible) {
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
private const val CALCULATOR_FREQUENCY_STEP_HZ = 1_000L // Default: 1 kHz
internal val CW_CALCULATOR_FREQUENCY_STEPS = listOf(
    100L to "0.1k",
    250L to "0.25k",
    500L to "0.5k",
    1_000L to "1k",
    5_000L to "5k"
)

internal fun snapCalculatorFrequency(hz: Long, stepHz: Long): Long {
    require(stepHz > 0L)
    return Math.floorDiv(hz + stepHz / 2L, stepHz) * stepHz
}
private val CALCULATOR_OFFSET_PATTERN = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")

private fun isValidCalculatorOffset(value: String): Boolean {
    if (value in setOf("", "+", "-", ".", "+.", "-.")) return true
    if (value.length > 12) return false
    if (!CALCULATOR_OFFSET_PATTERN.matches(value)) return false
    val parsed = value.toDoubleOrNull() ?: return false
    return parsed.isFinite() && kotlin.math.abs(parsed) <= MAX_CALCULATOR_OFFSET_KHZ
}

internal fun adjustCalculatorOffset(offsetKHz: String, increase: Boolean): String {
    val current = offsetKHz.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val delta = if (increase) BigDecimal("0.1") else BigDecimal("-0.1")
    val limit = BigDecimal("10000")
    return current.add(delta).coerceIn(limit.negate(), limit)
        .stripTrailingZeros().toPlainString()
}

@Composable
internal fun DopplerFrequencyCalculator(
    transponder: SatRadio,
    orbitalPos: OrbitalPos?,
    offsetKHz: String = "",
    onOffsetChange: (String) -> Unit = {},
    onFrequencyChange: (Long, Long) -> Unit = { _, _ -> },
    onTuningChange: (CalculatorTuning) -> Unit = {},
    initialTxBaseHz: Long? = null,
    editingEnabled:Boolean=true,
    displayNominalFrequencies: Boolean = false,
    reference: CalculatorReference? = null,
    frequencyStepHz: Long = CALCULATOR_FREQUENCY_STEP_HZ,
    onFrequencyStepChange: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val calibrated = remember(transponder, reference) { transponder.withCalculatorReference(reference) } ?: return
    CalculatorContent(calibrated, orbitalPos, offsetKHz, onOffsetChange, onFrequencyChange,
        onTuningChange, initialTxBaseHz, editingEnabled, displayNominalFrequencies, reference,
        frequencyStepHz, onFrequencyStepChange, modifier)
}

@Composable
private fun CalculatorContent(
    transponder: SatRadio,
    orbitalPos: OrbitalPos?,
    offsetKHz: String,
    onOffsetChange: (String) -> Unit,
    onFrequencyChange: (Long, Long) -> Unit,
    onTuningChange: (CalculatorTuning) -> Unit,
    initialTxBaseHz: Long?,
    editingEnabled: Boolean,
    displayNominalFrequencies: Boolean,
    reference: CalculatorReference?,
    frequencyStepHz: Long,
    onFrequencyStepChange: ((Long) -> Unit)?,
    modifier: Modifier
) {
    if (orbitalPos == null || !DopplerFrequencyCalculator.isLinearTransponder(transponder)) return

    var lastEditedField by rememberSaveable(transponder.uuid, reference) { mutableStateOf(EditedField.TX) }
    var txFrequencyHz by rememberSaveable(transponder.uuid, reference) { mutableStateOf(0L) }
    var rxFrequencyHz by rememberSaveable(transponder.uuid, reference) { mutableStateOf(0L) }
    var passbandPosition by rememberSaveable(transponder.uuid, reference) { mutableStateOf(0.5f) }
    // Saved values can be restored without validating rememberSaveable inputs.
    // Retain the reference used by that saved pair so a later base edit wins.
    var appliedReference by rememberSaveable(transponder.uuid) { mutableStateOf(longArrayOf()) }
    val referenceKey=reference?.let { longArrayOf(it.txHz,it.rxHz,it.offsetHz) } ?: longArrayOf()
    var nominalPassbandInitialized by remember(transponder.uuid, reference, displayNominalFrequencies) {
        mutableStateOf(false)
    }

    // Observe the live pair during composition even when CW displays only nominal
    // values; reads inside SideEffect alone do not schedule another composition.
    val publishedPair = txFrequencyHz to rxFrequencyHz
    val publishReady = appliedReference.contentEquals(referenceKey) &&
        (!displayNominalFrequencies || nominalPassbandInitialized)
    SideEffect {
        if (publishedPair.first != 0L && publishedPair.second != 0L && publishReady)
            onFrequencyChange(publishedPair.first, publishedPair.second)
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
        reference,
        displayNominalFrequencies,
        transponder.uplinkLow,
        transponder.uplinkHigh,
        transponder.downlinkLow,
        transponder.downlinkHigh,
        transponder.isInverted,
        orbitalPos.distanceRate,
        offsetHz
    ) {
        if (!appliedReference.contentEquals(referenceKey) ||
            displayNominalFrequencies && !nominalPassbandInitialized ||
            txFrequencyHz == 0L || rxFrequencyHz == 0L) {
            passbandPosition = initialTxBaseHz?.let {
                ((it-nominalTxLow).toFloat()/nominalTxRange).coerceIn(0f,1f)
            } ?: 0.5f
            txFrequencyHz = txAtPassbandPosition(passbandPosition)
            rxFrequencyHz = downlinkFor(txFrequencyHz) ?: (rxLow + rxRange / 2)
            nominalPassbandInitialized = true
            appliedReference = referenceKey
        } else if (displayNominalFrequencies) {
            // Keep the displayed nominal TX/RX position fixed as orbital Doppler changes.
            txFrequencyHz = txAtPassbandPosition(passbandPosition)
            rxFrequencyHz = downlinkFor(txFrequencyHz) ?: rxFrequencyHz.coerceIn(rxLow, rxHigh)
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

    // Endpoints and number readouts in the CW calculator are in the transponder-list
    // frequency domain. Only txFrequencyHz/rxFrequencyHz above include live Doppler.
    val nominalRxLow = minOf(
        transponder.downlinkLow ?: return,
        transponder.downlinkHigh ?: return
    ) + offsetHz
    val nominalRxHigh = maxOf(
        transponder.downlinkLow ?: return,
        transponder.downlinkHigh ?: return
    ) + offsetHz
    val displayTxLow = if (displayNominalFrequencies) nominalTxLow else txLow
    val displayTxHigh = if (displayNominalFrequencies) nominalTxLow + nominalTxRange else txHigh
    val displayRxLow = if (displayNominalFrequencies) nominalRxLow else rxLow
    val displayRxHigh = if (displayNominalFrequencies) nominalRxHigh else rxHigh
    val txSliderValue = if (displayNominalFrequencies || lastEditedField == EditedField.PASSBAND) {
        passbandPosition
    } else {
        txPosition(txFrequencyHz)
    }
    val nominalTxDisplayHz = nominalTxLow + (passbandPosition.coerceIn(0f, 1f).toDouble() * nominalTxRange).roundToLong()
    val nominalRxDisplayHz = TransponderMapper.mapUplinkToDownlink(nominalTxDisplayHz, transponder)
        ?.let { (it + offsetHz).coerceIn(nominalRxLow, nominalRxHigh) }
        ?: (nominalRxLow + ((passbandPosition.coerceIn(0f, 1f)) * (nominalRxHigh - nominalRxLow)).toLong())
    val displayTxHz = if (displayNominalFrequencies) nominalTxDisplayHz else txFrequencyHz
    val displayRxHz = if (displayNominalFrequencies) nominalRxDisplayHz else rxFrequencyHz
    val rxSliderValue = if (displayNominalFrequencies) {
        ((displayRxHz - nominalRxLow).toFloat() / (nominalRxHigh - nominalRxLow).coerceAtLeast(1L)).coerceIn(0f, 1f)
    } else {
        ((rxFrequencyHz - rxLow).toFloat() / rxRange).coerceIn(0f, 1f)
    }
    val edgeFrequencyFormat = if (displayNominalFrequencies) "%.3f" else "%.4f"
    val showStepSelector = displayNominalFrequencies && onFrequencyStepChange != null
    val stepHz = if (showStepSelector) frequencyStepHz else CALCULATOR_FREQUENCY_STEP_HZ
    val stepPrecision = when (stepHz) {
        250L -> 5
        100L, 500L -> 4
        else -> 3
    }
    val offsetPrecision = when {
        offsetHz % 1_000L == 0L -> 3
        offsetHz % 100L == 0L -> 4
        offsetHz % 10L == 0L -> 5
        else -> 6
    }
    val readoutFrequencyFormat = if (!displayNominalFrequencies) "%.6f" else
        "%.${maxOf(stepPrecision, offsetPrecision)}f"

    fun publishTuning() {
        if(!editingEnabled)return
        val anchor = if(displayNominalFrequencies) CalculatorFrequencyAnchor.SATELLITE else when(lastEditedField) {
            EditedField.TX -> CalculatorFrequencyAnchor.GROUND_TX
            EditedField.RX -> CalculatorFrequencyAnchor.GROUND_RX
            EditedField.PASSBAND -> CalculatorFrequencyAnchor.SATELLITE
        }
        val hz = if(anchor == CalculatorFrequencyAnchor.SATELLITE)
            nominalTxLow + (passbandPosition.coerceIn(0f,1f)*nominalTxRange).toLong()
        else when(lastEditedField) {
            EditedField.TX -> txFrequencyHz
            EditedField.RX -> rxFrequencyHz
            EditedField.PASSBAND -> nominalTxLow + (passbandPosition.coerceIn(0f,1f)*nominalTxRange).toLong()
        }
        onTuningChange(CalculatorTuning(transponder.uuid,anchor,hz,reference))
    }

    fun updateTx(newTxHz: Long) {
        if(!editingEnabled)return
        if (displayNominalFrequencies) {
            // The slider selects a nominal passband position; recompute the live radio pair
            // from that position so the readout itself never follows Doppler.
            passbandPosition = txPosition(newTxHz.coerceIn(txLow, txHigh))
            txFrequencyHz = txAtPassbandPosition(passbandPosition)
            rxFrequencyHz = downlinkFor(txFrequencyHz) ?: rxFrequencyHz
        } else {
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
        }
        publishTuning()
    }

    fun updateRx(newRxHz: Long) {
        if(!editingEnabled)return
        if (displayNominalFrequencies) {
            val rxTarget = newRxHz.coerceIn(rxLow, rxHigh)
            val txFromRx = uplinkFor(rxTarget)
            if (txFromRx != null) passbandPosition = txPosition(txFromRx)
            txFrequencyHz = txAtPassbandPosition(passbandPosition)
            rxFrequencyHz = downlinkFor(txFrequencyHz) ?: rxTarget
        } else {
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
        }
        publishTuning()
    }

    // In the CW console, plus/minus keys operate in the nominal transponder frequency
    // domain shown to the user (1 kHz = 0.001 MHz), not on the live Doppler-corrected
    // radio frequency. The resulting pair is still converted to live radio frequencies
    // for tuning, while the readout remains stable as Doppler changes.
    fun updateNominalTx(newTxHz: Long) {
        if (!editingEnabled) return
        val targetTxHz = newTxHz.coerceIn(nominalTxLow, nominalTxLow + nominalTxRange)
        passbandPosition = ((targetTxHz - nominalTxLow).toDouble() / nominalTxRange.toDouble())
            .toFloat().coerceIn(0f, 1f)
        txFrequencyHz = txAtPassbandPosition(passbandPosition)
        rxFrequencyHz = downlinkFor(txFrequencyHz) ?: rxFrequencyHz
        publishTuning()
    }

    fun updateNominalRx(newRxHz: Long) {
        if (!editingEnabled) return
        val targetRxHz = newRxHz.coerceIn(nominalRxLow, nominalRxHigh)
        val targetTxHz = TransponderMapper.mapDownlinkToUplink(targetRxHz - offsetHz, transponder)
            ?.coerceIn(nominalTxLow, nominalTxLow + nominalTxRange) ?: return
        passbandPosition = ((targetTxHz - nominalTxLow).toDouble() / nominalTxRange.toDouble())
            .toFloat().coerceIn(0f, 1f)
        txFrequencyHz = txAtPassbandPosition(passbandPosition)
        rxFrequencyHz = downlinkFor(txFrequencyHz) ?: rxFrequencyHz
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
                    modifier = Modifier.weight(if (showStepSelector) 1.25f else 1f),
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
                // Offset input, followed by the CW-only step selector.
                Box(
                    modifier = Modifier
                        .weight(if (showStepSelector) 1.05f else 1f)
                        .height(48.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = offsetKHz,
                            enabled = editingEnabled,
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
                            modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                                .testTag("cwCalculatorOffsetInput"),
                            decorationBox = { innerTextField ->
                                if (offsetKHz.isEmpty()) {
                                    Text(
                                        text = "Offset (kHz)",
                                        modifier = Modifier.fillMaxWidth(),
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (showStepSelector) {
                            val increaseDescription = stringResource(R.string.cw_console_increase_offset)
                            val decreaseDescription = stringResource(R.string.cw_console_decrease_offset)
                            Column(
                                modifier = Modifier.width(20.dp).fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                        .clickable(enabled = editingEnabled, role = Role.Button) {
                                            val next = adjustCalculatorOffset(offsetKHz, increase = true)
                                            if (next != offsetKHz) onOffsetChange(next)
                                        }
                                        .semantics { contentDescription = increaseDescription }
                                        .testTag("cwCalculatorOffsetIncrease"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "▲",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = if (editingEnabled) 1f else 0.38f
                                        )
                                    )
                                }
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                        .clickable(enabled = editingEnabled, role = Role.Button) {
                                            val next = adjustCalculatorOffset(offsetKHz, increase = false)
                                            if (next != offsetKHz) onOffsetChange(next)
                                        }
                                        .semantics { contentDescription = decreaseDescription }
                                        .testTag("cwCalculatorOffsetDecrease"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "▼",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = if (editingEnabled) 1f else 0.38f
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                if (showStepSelector) {
                    var stepMenuExpanded by remember { mutableStateOf(false) }
                    val stepLabel = CW_CALCULATOR_FREQUENCY_STEPS
                        .firstOrNull { it.first == stepHz }?.second ?: "1k"
                    val stepDescription = stringResource(R.string.cw_console_frequency_step)
                    Box(
                        modifier = Modifier
                            .weight(0.85f)
                            .height(48.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                            .clickable(enabled = editingEnabled, role = Role.Button) { stepMenuExpanded = true }
                            .semantics { contentDescription = "$stepDescription: $stepLabel" }
                            .testTag("cwCalculatorStepSelector"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center) {
                            Text(stepLabel, fontSize = 14.sp, maxLines = 1)
                            Text(" ▾", fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = stepMenuExpanded, onDismissRequest = { stepMenuExpanded = false }) {
                            CW_CALCULATOR_FREQUENCY_STEPS.forEach { (newStepHz, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        stepMenuExpanded = false
                                        if (newStepHz != stepHz) {
                                            onFrequencyStepChange?.invoke(newStepHz)
                                            // Snap the active nominal anchor; the opposite
                                            // frequency is recalculated through the transponder.
                                            if (lastEditedField == EditedField.RX) {
                                                updateNominalRx(snapCalculatorFrequency(displayRxHz, newStepHz))
                                            } else {
                                                updateNominalTx(snapCalculatorFrequency(displayTxHz, newStepHz))
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("cwCalculatorStepOption$newStepHz")
                                )
                            }
                        }
                    }
                }
            }

            // TX 行
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            if (displayNominalFrequencies) updateNominalTx(displayTxHz - stepHz)
                            else updateTx(txFrequencyHz - stepHz)
                        },
                        enabled=editingEnabled,
                        modifier = Modifier.width(32.dp).height(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    ) { Text("−", fontSize = 14.sp) }

                    Slider(
                        value = txSliderValue,
                        enabled=editingEnabled,
                        onValueChange = { norm ->
                            if (displayNominalFrequencies) {
                                val rawHz = nominalTxLow + (nominalTxRange * norm.toDouble()).roundToLong()
                                updateNominalTx(snapCalculatorFrequency(rawHz, stepHz))
                            } else {
                                val rawHz = txLow + (txRange * norm).toLong()
                                updateTx(snapCalculatorFrequency(rawHz, stepHz).coerceIn(txLow, txHigh))
                            }
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).height(20.dp).padding(horizontal = 4.dp)
                    )

                    OutlinedButton(
                        onClick = {
                            if (displayNominalFrequencies) updateNominalTx(displayTxHz + stepHz)
                            else updateTx(txFrequencyHz + stepHz)
                        },
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
                        text = String.format(Locale.ENGLISH, edgeFrequencyFormat, displayTxLow / 1_000_000.0),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = String.format(Locale.ENGLISH, readoutFrequencyFormat, displayTxHz / 1_000_000.0),
                            modifier = Modifier.testTag("calculatorTxFrequency"),
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
                        text = String.format(Locale.ENGLISH, edgeFrequencyFormat, displayTxHigh / 1_000_000.0),
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
                        onClick = {
                            if (displayNominalFrequencies) updateNominalRx(displayRxHz - stepHz)
                            else updateRx(rxFrequencyHz - stepHz)
                        },
                        enabled=editingEnabled,
                        modifier = Modifier.width(32.dp).height(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    ) { Text("−", fontSize = 14.sp) }

                    Slider(
                        value = rxSliderValue,
                        enabled=editingEnabled,
                        onValueChange = { norm ->
                            if (displayNominalFrequencies) {
                                val rawHz = nominalRxLow + ((nominalRxHigh - nominalRxLow) * norm.toDouble()).roundToLong()
                                updateNominalRx(snapCalculatorFrequency(rawHz, stepHz))
                            } else {
                                val rawHz = rxLow + (rxRange * norm).toLong()
                                updateRx(snapCalculatorFrequency(rawHz, stepHz).coerceIn(rxLow, rxHigh))
                            }
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).height(20.dp).padding(horizontal = 4.dp)
                    )

                    OutlinedButton(
                        onClick = {
                            if (displayNominalFrequencies) updateNominalRx(displayRxHz + stepHz)
                            else updateRx(rxFrequencyHz + stepHz)
                        },
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
                        text = String.format(Locale.ENGLISH, edgeFrequencyFormat, displayRxLow / 1_000_000.0),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = String.format(Locale.ENGLISH, readoutFrequencyFormat, displayRxHz / 1_000_000.0),
                            modifier = Modifier.testTag("calculatorRxFrequency"),
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
                        text = String.format(Locale.ENGLISH, edgeFrequencyFormat, displayRxHigh / 1_000_000.0),
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
private fun CwDecoderPanel(cw: CwSubState, onAction: (RadarAction) -> Unit,
    requestMicPermission: () -> Unit, modifier: Modifier = Modifier) {
    if (LocalCwConsoleVisible.current) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.radar_cw_decoder), color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable { onAction(RadarAction.CwToggleExpanded(!cw.isExpanded)) })
        if (cw.isExpanded) SharedCwDecoderPanel(cw, onAction, requestMicPermission)
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
