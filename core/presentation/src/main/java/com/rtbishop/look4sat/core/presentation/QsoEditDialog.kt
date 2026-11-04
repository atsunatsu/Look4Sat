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
package com.rtbishop.look4sat.core.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.logbook.LoTWSatelliteAliases
import com.rtbishop.look4sat.core.domain.model.NominalFrequencies
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.logbook.displayTheirGrid
import com.rtbishop.look4sat.core.domain.logbook.frequencyBand
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

private const val DATE_PATTERN = "yyyy-MM-dd"
private const val TIME_PATTERN = "HH:mm:ss"

/**
 * Edit a recorded QSO — the record list rows in the radar Log tab and the logbook dialog
 * both open this, and the logbook's Create button opens it on a blank draft. The opposite
 * grid is read-only: an unconfirmed QSO has no opposite grid of its own, LoTW's confirmation
 * is what brings the real one in. The sent/received reports and the comment are not part of
 * the log any more, so they are neither shown nor rewritten here. Frequencies are entered in
 * MHz and the band is re-derived from them, because that derivation is what LoTW validates:
 * a record with no uplink frequency has no BAND and cannot be signed.
 *
 * The satellite name is restricted to ARRL's catalogue ([satelliteCandidates], from
 * config.tq6): typing shows fuzzy suggestions, the picker lists the whole catalogue, and
 * a name that resolves to no ARRL satellite cannot be saved. A tracker name such as
 * "SAUDISAT 1C" is accepted and stored as the ARRL name it resolves to ("SO-50").
 */
@Composable
fun QsoEditDialog(
    record: QsoRecord,
    satelliteCandidates: List<String> = emptyList(),
    /** Nominal (no Doppler) uplink/downlink of a named satellite, prefilled into a blank draft. */
    nominalFrequencies: suspend (String) -> NominalFrequencies? = { null },
    onDismiss: () -> Unit,
    onSave: (QsoRecord) -> Unit
) {
    val utc = remember { TimeZone.getTimeZone("UTC") }
    fun formatDate(millis: Long) = SimpleDateFormat(DATE_PATTERN, Locale.US).apply { timeZone = utc }.format(Date(millis))
    fun formatTime(millis: Long) = SimpleDateFormat(TIME_PATTERN, Locale.US).apply { timeZone = utc }.format(Date(millis))

    var callsign by remember(record.id) { mutableStateOf(record.theirCallsign.trim()) }
    var dateText by remember(record.id) { mutableStateOf(formatDate(record.startUtcMillis)) }
    var timeText by remember(record.id) { mutableStateOf(formatTime(record.startUtcMillis)) }
    var txText by remember(record.id) { mutableStateOf(formatMhz(record.txFrequencyHz)) }
    var rxText by remember(record.id) { mutableStateOf(formatMhz(record.rxFrequencyHz)) }
    var modeText by remember(record.id) { mutableStateOf(record.displayMode) }
    var satelliteText by remember(record.id) { mutableStateOf(record.satelliteName) }
    var pickerOpen by remember(record.id) { mutableStateOf(false) }
    var error by remember(record.id) { mutableStateOf("") }

    // ARRL name the typed tracker name resolves to ("SAUDISAT 1C" -> "SO-50"), if any.
    val officialSatellite = remember(satelliteText, satelliteCandidates) {
        LoTWSatelliteAliases.resolve(satelliteText, satelliteCandidates)
    }
    val satelliteSuggestions = remember(satelliteText, satelliteCandidates) {
        LoTWSatelliteAliases.suggestions(satelliteText, satelliteCandidates)
    }

    // A blank draft gets the satellite's nominal pair as soon as its name resolves — the same
    // frequencies the radar Log tab logs through, with no Doppler shift applied. Listed records
    // keep whatever they were logged with, and a pair the operator edited by hand survives a
    // satellite change: AO-7 is worked on either band, so a manual band switch must stick.
    var autoTx by remember(record.id) { mutableStateOf("") }
    var autoRx by remember(record.id) { mutableStateOf("") }
    val fillName = officialSatellite ?: satelliteText.trim()
    LaunchedEffect(record.id, fillName) {
        if (record.id != 0L || fillName.isBlank()) return@LaunchedEffect
        val nominal = nominalFrequencies(fillName) ?: return@LaunchedEffect
        val tx = formatMhz(nominal.uplinkHz)
        val rx = formatMhz(nominal.downlinkHz)
        if (tx.isNotBlank() && (txText.isBlank() || txText == autoTx)) {
            txText = tx
            autoTx = tx
        }
        if (rx.isNotBlank() && (rxText.isBlank() || rxText == autoRx)) {
            rxText = rx
            autoRx = rx
        }
    }

    val txHz = parseMhz(txText)
    val rxHz = parseMhz(rxText)
    val bandText = listOf(frequencyBand(txHz), frequencyBand(rxHz)).filter { it.isNotBlank() }.distinct()
        .joinToString(" / ")

    val errCallsign = stringResource(R.string.qso_edit_err_callsign)
    val errDateTime = stringResource(R.string.qso_edit_err_datetime)
    val errFrequency = stringResource(R.string.qso_edit_err_frequency)
    val errSatellite = stringResource(R.string.qso_edit_err_satellite)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = sheetDialogShape(),
        containerColor = sheetDialogContainerColor(),
        title = { SheetDialogTitle(stringResource(R.string.qso_edit_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (record.lotwUploaded || record.lotwConfirmed) {
                    Text(
                        text = stringResource(R.string.qso_edit_uploaded_notice),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                EditField(stringResource(R.string.qso_edit_callsign), callsign, KeyboardCapitalization.Characters) {
                    callsign = it.uppercase(Locale.US)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    EditField(stringResource(R.string.qso_edit_date), dateText, KeyboardCapitalization.None, Modifier.weight(1f)) {
                        dateText = it
                    }
                    EditField(stringResource(R.string.qso_edit_time), timeText, KeyboardCapitalization.None, Modifier.weight(1f)) {
                        timeText = it
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    EditField(stringResource(R.string.qso_edit_tx), txText, KeyboardCapitalization.None, Modifier.weight(1f)) {
                        txText = it
                    }
                    EditField(stringResource(R.string.qso_edit_rx), rxText, KeyboardCapitalization.None, Modifier.weight(1f)) {
                        rxText = it
                    }
                }
                Text(
                    text = stringResource(R.string.qso_edit_band, bandText.ifBlank { "—" }),
                    fontSize = 12.sp,
                    color = if (txHz == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                EditField(stringResource(R.string.qso_edit_mode), modeText, KeyboardCapitalization.Characters) {
                    modeText = it.uppercase(Locale.US)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    EditField(
                        label = stringResource(R.string.qso_edit_satellite),
                        value = satelliteText,
                        capitalization = KeyboardCapitalization.Characters,
                        modifier = Modifier.weight(1f)
                    ) { satelliteText = it.uppercase(Locale.US) }
                    TextButton(
                        onClick = { pickerOpen = true },
                        enabled = satelliteCandidates.isNotEmpty()
                    ) { Text(stringResource(R.string.qso_edit_satellite_pick), fontSize = 12.sp) }
                }
                when {
                    // Resolves to an ARRL satellite under a different name: say so, the
                    // ARRL name is what gets stored and signed.
                    officialSatellite != null && !officialSatellite.equals(satelliteText.trim(), true) ->
                        Text(
                            text = stringResource(R.string.qso_edit_satellite_official, officialSatellite),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                    // Not an ARRL satellite at all: offer the closest catalogue names.
                    satelliteText.isNotBlank() && officialSatellite == null -> {
                        Text(
                            text = stringResource(R.string.qso_edit_err_satellite),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (satelliteSuggestions.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                satelliteSuggestions.take(4).forEach { candidate ->
                                    FilterChip(
                                        selected = false,
                                        onClick = { satelliteText = candidate },
                                        label = { Text(candidate, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }

                    else -> Text(
                        text = stringResource(R.string.qso_edit_hint_satellite),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ReadOnlyField(stringResource(R.string.qso_edit_grid), record.displayTheirGrid)
                if (record.iota.isNotBlank()) {
                    ReadOnlyField(stringResource(R.string.qso_edit_their_iota), record.iota)
                }
                if (record.myIota.isNotBlank()) {
                    ReadOnlyField(stringResource(R.string.qso_edit_my_iota), record.myIota)
                }
                if (txHz == null) {
                    Text(
                        text = stringResource(R.string.qso_edit_warn_no_freq),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (error.isNotBlank()) {
                    Text(text = error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val millis = parseUtc(dateText, timeText, utc)
                val badFrequency = (txText.isNotBlank() && txHz == null) || (rxText.isNotBlank() && rxHz == null)
                when {
                    callsign.isBlank() -> error = errCallsign
                    millis == null -> error = errDateTime
                    badFrequency -> error = errFrequency
                    // Only ARRL satellite names may be stored: a record that cannot be
                    // resolved here would be rejected at upload time anyway.
                    satelliteCandidates.isNotEmpty() && officialSatellite == null ->
                        error = errSatellite

                    else -> {
                        val mode = modeText.trim().uppercase(Locale.US).ifBlank { "FM" }
                        val satellite = officialSatellite ?: satelliteText.trim()
                        onSave(
                            record.copy(
                                theirCallsign = callsign.trim().uppercase(Locale.US),
                                startUtcMillis = millis,
                                endUtcMillis = record.endUtcMillis?.let { millis },
                                txFrequencyHz = txHz,
                                rxFrequencyHz = rxHz,
                                band = frequencyBand(txHz),
                                rxBand = frequencyBand(rxHz),
                                mode = if (mode == "FT4") "MFSK" else mode,
                                submode = if (mode == "FT4") "FT4" else "",
                                satelliteName = satellite,
                                propagationMode = if (satellite.isBlank()) record.propagationMode else "SAT"
                            )
                        )
                    }
                }
            }) { Text(stringResource(R.string.qso_edit_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.qso_edit_cancel)) }
        }
    )

    if (pickerOpen) {
        SatellitePickerDialog(
            candidates = satelliteCandidates,
            onDismiss = { pickerOpen = false },
            onPick = {
                satelliteText = it
                pickerOpen = false
            }
        )
    }
}

/**
 * Full ARRL satellite catalogue with a fuzzy filter: typing "saud" or "saudisat 1c" narrows the
 * list down to "SO-50", so a tracker name can always be turned into the name LoTW accepts.
 */
@Composable
private fun SatellitePickerDialog(
    candidates: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query, candidates) { filteredCandidates(query, candidates) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = sheetDialogShape(),
        containerColor = sheetDialogContainerColor(),
        title = { SheetDialogTitle(stringResource(R.string.qso_edit_satellite_pick_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.uppercase(Locale.US) },
                    label = { Text(stringResource(R.string.qso_edit_satellite_search), fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(matches, key = { it }) { name ->
                        Text(
                            text = name,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(name) }
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.qso_edit_cancel)) }
        }
    )
}

/** Catalogue filtered by the fuzzy query; the whole list when nothing matches or the query is blank. */
internal fun filteredCandidates(query: String, candidates: List<String>): List<String> {
    if (query.isBlank()) return candidates
    val matches = LoTWSatelliteAliases.suggestions(query, candidates, limit = 500)
    return matches.ifEmpty { candidates }
}

/** Non-editable look-alike of [EditField], for values the operator must not rewrite. */
@Composable
private fun ReadOnlyField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value.ifBlank { "—" },
        onValueChange = {},
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        readOnly = true,
        enabled = false,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier.padding(vertical = 1.dp)
    )
}

@Composable
private fun EditField(
    label: String,
    value: String,
    capitalization: KeyboardCapitalization,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(capitalization = capitalization),
        modifier = modifier.padding(vertical = 1.dp)
    )
}

/** "436.795" (MHz) -> 436_795_000 Hz. Null when blank or unparsable. */
internal fun parseMhz(text: String): Long? = text.trim()
    .takeIf { it.isNotEmpty() }
    ?.toDoubleOrNull()
    ?.let { (it * 1_000_000.0).roundToLong() }

/** Hz -> "436.795" (MHz), trailing zeros trimmed. */
internal fun formatMhz(hz: Long?): String = hz
    ?.let { BigDecimal.valueOf(it, 6).stripTrailingZeros().toPlainString() }
    .orEmpty()

internal fun parseUtc(date: String, time: String, zone: TimeZone): Long? = runCatching {
    SimpleDateFormat("$DATE_PATTERN $TIME_PATTERN", Locale.US).apply {
        timeZone = zone
        isLenient = false
    }.parse("${date.trim()} ${time.trim()}")?.time
}.getOrNull()
