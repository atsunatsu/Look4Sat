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
package com.rtbishop.look4sat.feature.settings

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rtbishop.look4sat.core.domain.model.Constants
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.RadioProtocolFamily
import com.rtbishop.look4sat.core.domain.model.radioModelDescriptor
import com.rtbishop.look4sat.core.domain.model.parseRadioTcpEndpoint
import com.rtbishop.look4sat.core.domain.model.supportedRadioBaudRates
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.source.NetworkResult
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.LocalSpacing
import com.rtbishop.look4sat.core.presentation.MainTheme
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Preview(showBackground = true)
@Composable
private fun PositionDialogPreview() {
    MainTheme { PositionDialog(0.0, 0.0, {}) { _, _ -> } }
}

@Composable
fun PositionDialog(lat: Double, lon: Double, dismiss: () -> Unit, save: (Double, Double) -> Unit) {
    val latValue = rememberSaveable { mutableStateOf(lat.toString()) }
    val lonValue = rememberSaveable { mutableStateOf(lon.toString()) }
    val titleText = stringResource(id = R.string.prefs_station_title)
    val onAccept = { saveValues(latValue.value, lonValue.value, save).also { dismiss() } }
    SharedDialog(title = titleText, onCancel = dismiss, onAccept = onAccept) {
        OutlinedTextField(
            value = latValue.value,
            onValueChange = { latValue.value = it },
            label = { Text(text = stringResource(id = R.string.prefs_station_lat_text)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large),
        )
        OutlinedTextField(
            value = lonValue.value,
            onValueChange = { lonValue.value = it },
            label = { Text(text = stringResource(id = R.string.prefs_station_lon_text)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large),
        )
        Spacer(modifier = Modifier.height(0.dp))
    }
}

private fun saveValues(latValue: String, lonValue: String, save: (Double, Double) -> Unit) {
    val latitude = latValue.toDoubleOrNull() ?: 0.0
    val longitude = lonValue.toDoubleOrNull() ?: 0.0
    val newLatitude = if (latitude > 90) 90.0 else if (latitude < -90) -90.0 else latitude
    val newLongitude = if (longitude > 180) 180.0 else if (longitude < -180) -180.0 else longitude
    save(newLatitude, newLongitude)
}

@Preview(showBackground = true)
@Composable
private fun LocatorDialogPreview() {
    MainTheme { LocatorDialog("IO91vl", {}) { } }
}

@Composable
fun LocatorDialog(qthLocator: String, dismiss: () -> Unit, save: (String) -> Unit) {
    val locator = rememberSaveable { mutableStateOf(qthLocator) }
    val onAccept = { save(locator.value).also { dismiss() } }
    SharedDialog(title = stringResource(R.string.prefs_locator_title), onCancel = dismiss, onAccept = onAccept) {
        OutlinedTextField(
            value = locator.value,
            onValueChange = { locator.value = it },
            label = { Text(text = stringResource(id = R.string.prefs_locator_text)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large),
        )
        Spacer(modifier = Modifier.height(0.dp))
    }
}


@Composable
fun WavelogUploadDialog(
    initialSettings: com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings,
    stations: List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo>,
    rights: String,
    probeBusy: Boolean,
    isSyncing: Boolean,
    lastSyncEpochMs: Long,
    message: String?,
    dismiss: () -> Unit,
    onSave: (com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings) -> Unit,
    onFetchStations: (url: String, apiKey: String) -> Unit,
    onSelectStation: (com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo) -> Unit,
    onSyncIncremental: () -> Unit,
    onSyncFull: () -> Unit,
    onClear: () -> Unit
) {
    val url = rememberSaveable { mutableStateOf(initialSettings.url) }
    val apiKey = rememberSaveable { mutableStateOf(initialSettings.apiKey) }
    var showClearConfirm by remember { mutableStateOf(false) }
    SharedDialog(
        title = stringResource(R.string.prefs_wavelog_upload_title),
        onCancel = dismiss,
        onAccept = {
            onSave(initialSettings.copy(url = url.value, apiKey = apiKey.value))
            dismiss()
        }
    ) {
        OutlinedTextField(
            value = url.value,
            onValueChange = { url.value = it },
            label = { Text(text = stringResource(id = R.string.prefs_wavelog_url)) },
            placeholder = { Text(text = "http://100.x.x.x/wavelog") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large),
        )
        OutlinedTextField(
            value = apiKey.value,
            onValueChange = { apiKey.value = it },
            label = { Text(text = stringResource(id = R.string.prefs_wavelog_upload_apikey)) },
            placeholder = { Text(text = "wl…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large)
        ) {
            TextButton(onClick = { onFetchStations(url.value, apiKey.value) }, enabled = !probeBusy) {
                Text(
                    text = if (probeBusy) stringResource(R.string.prefs_wavelog_upload_probing)
                    else stringResource(R.string.prefs_wavelog_upload_fetch)
                )
            }
        }
        if (rights.isNotBlank()) {
            Text(
                text = when (rights) {
                    "rw" -> stringResource(R.string.prefs_wavelog_upload_rights_rw)
                    else -> stringResource(R.string.prefs_wavelog_upload_rights_r)
                },
                color = if (rights == "rw") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = LocalSpacing.current.large)
            )
        }
        if (stations.isEmpty() && initialSettings.stationId.isBlank()) {
            Text(
                text = stringResource(R.string.prefs_wavelog_upload_station_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = LocalSpacing.current.large)
            )
        } else {
            // Many station profiles must stay reachable: the list scrolls inside a
            // bounded height instead of stretching (or clipping) the dialog.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                stations.forEach { station ->
                    val selected = station.id == initialSettings.stationId
                    Text(
                        text = (if (selected) "● " else "○ ") + listOf(station.name, station.callsign, station.grid)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectStation(station) }
                            .padding(horizontal = LocalSpacing.current.large, vertical = 2.dp)
                    )
                }
            }
        }
        if (initialSettings.stationId.isNotBlank()) {
            Text(
                text = stringResource(
                    R.string.prefs_wavelog_upload_configured,
                    listOf(initialSettings.stationName, initialSettings.stationCallsign, initialSettings.stationGrid)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = LocalSpacing.current.large)
            )
        }
        if (lastSyncEpochMs != 0L) {
            val timePattern = stringResource(R.string.prefs_updated_time)
            val lastSyncText = remember(lastSyncEpochMs) {
                java.text.SimpleDateFormat(timePattern, java.util.Locale.getDefault())
                    .format(java.util.Date(lastSyncEpochMs))
            }
            Text(
                text = stringResource(R.string.prefs_updated_title, lastSyncText),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = LocalSpacing.current.large)
            )
        }
        // Sync pulls the server's QSOs (and the grid data the map highlights) — the
        // incremental button resumes from the stored cursor, the full one re-pulls.
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large)
        ) {
            CardButton(
                onClick = onSyncFull,
                text = stringResource(R.string.prefs_wavelog_sync_full),
                enabled = !isSyncing && !probeBusy
            )
            CardButton(
                onClick = onSyncIncremental,
                text = stringResource(R.string.prefs_wavelog_sync_incremental),
                enabled = !isSyncing && !probeBusy
            )
        }
        if (isSyncing) {
            Text(
                text = stringResource(R.string.prefs_wavelog_syncing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = LocalSpacing.current.large)
            )
        }
        if (message != null) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = LocalSpacing.current.large)
            )
        }
        Text(
            text = stringResource(R.string.prefs_wavelog_upload_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = LocalSpacing.current.large)
        )
        TextButton(
            onClick = { showClearConfirm = true },
            modifier = Modifier.padding(horizontal = LocalSpacing.current.large)
        ) {
            Text(text = stringResource(R.string.prefs_wavelog_clear), color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(0.dp))
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.prefs_wavelog_clear_title)) },
            text = { Text(stringResource(R.string.prefs_wavelog_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onClear()
                }) { Text(stringResource(R.string.btn_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }
}

/** LoTW→Wavelog hand-over guard: shown when completing the Wavelog config would switch the mode. */
@Composable
fun WavelogSwitchWarningDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.prefs_wavelog_switch_title)) },
        text = { Text(stringResource(R.string.prefs_wavelog_switch_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.prefs_wavelog_switch_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

@Composable
fun LoTWDialog(
    initialSettings: com.rtbishop.look4sat.core.domain.model.LoTWSettings,
    workedGridsCount: Int,
    isSyncing: Boolean,
    syncMode: LoTWSyncMode?,
    progress: com.rtbishop.look4sat.core.domain.repository.LoTWProgress?,
    message: String?,
    dismiss: () -> Unit,
    /** Called on cancel/back while a sync is running: aborts the job. */
    onCancelSync: () -> Unit,
    onSave: (com.rtbishop.look4sat.core.domain.model.LoTWSettings) -> Unit,
    onSyncFull: (com.rtbishop.look4sat.core.domain.model.LoTWSettings) -> Unit,
    onSyncIncremental: (com.rtbishop.look4sat.core.domain.model.LoTWSettings) -> Unit
) {
    val call = rememberSaveable { mutableStateOf(initialSettings.callsign) }
    val pass = rememberSaveable { mutableStateOf(initialSettings.password) }
    SharedDialog(
        title = stringResource(R.string.prefs_lotw_title),
        titleHorizontalPadding = LocalSpacing.current.medium,
        // While syncing, cancel/back aborts the download instead of merely
        // hiding the dialog — a full sync started by mistake must be stoppable.
        onDismissRequest = { if (isSyncing) onCancelSync() else dismiss() },
        onCancel = { if (isSyncing) onCancelSync() else dismiss() },
        // The two sync buttons below are the primary actions: saving
        // credentials and fetching confirmed grids happen in one step.
        onAccept = null
    ) {
        OutlinedTextField(
            value = call.value,
            onValueChange = { call.value = it.uppercase() },
            label = { Text(text = stringResource(id = R.string.prefs_lotw_callsign)) },
            placeholder = { Text(text = "BA7OPF") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large),
        )
        OutlinedTextField(
            value = pass.value,
            onValueChange = { pass.value = it },
            label = { Text(text = stringResource(id = R.string.prefs_lotw_password)) },
            placeholder = { Text(text = "********") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large),
        )
        Text(
            text = stringResource(R.string.prefs_lotw_hint, workedGridsCount),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = LocalSpacing.current.large)
        )
        if (isSyncing) {
            // Live progress: once the report header reveals the record count,
            // switch to "N QSOs, ~X s remaining" with a determinate bar.
            val progressText = when {
                progress == null ||
                    progress.phase == com.rtbishop.look4sat.core.domain.repository.LoTWPhase.Connecting ->
                    stringResource(R.string.lotw_sync_progress_connecting)
                // Download finished (or the size estimate reached its cap):
                // parsing and saving may still run — say so instead of the bar
                // sitting at 100% with no feedback, which reads as "stuck".
                progress.fraction >= 1f ->
                    stringResource(R.string.lotw_sync_progress_saving)
                progress.qsoCount > 0 && progress.remainingSeconds > 0 ->
                    stringResource(R.string.lotw_sync_progress_qso, progress.qsoCount, progress.remainingSeconds)
                progress.qsoCount > 0 ->
                    stringResource(R.string.lotw_sync_progress_count, progress.qsoCount)
                else -> stringResource(R.string.lotw_sync_progress_downloading)
            }
            Text(
                text = progressText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = LocalSpacing.current.large)
            )
            if (progress != null && progress.expectedBytes > 0) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large)
                )
            }
        }
        if (message != null) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = LocalSpacing.current.large)
            )
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large)
        ) {
            // Full sync bottom-left, incremental merge bottom-right.
            CardButton(
                onClick = { onSyncFull(com.rtbishop.look4sat.core.domain.model.LoTWSettings(call.value, pass.value)) },
                text = stringResource(R.string.lotw_sync_full),
                enabled = !isSyncing
            )
            CardButton(
                onClick = { onSyncIncremental(com.rtbishop.look4sat.core.domain.model.LoTWSettings(call.value, pass.value)) },
                text = stringResource(R.string.lotw_sync_incremental),
                enabled = !isSyncing
            )
        }
        Spacer(modifier = Modifier.height(0.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun TransceiversDialogPreview() {
    MainTheme {
        DataSourcesDialog(
            satelliteUrls = listOf(
                "celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=csv",
                "amsat.org/tle/current/nasabare.txt"
            ),
            transceiversUrls = listOf(
                "db.satnogs.org/api/transmitters/?format=json&status=active"
            ),
            satelliteEnabled = listOf(true, false),
            transceiversEnabled = listOf(true),
            statusCodes = mapOf(
                "celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=csv" to 200,
                "amsat.org/tle/current/nasabare.txt" to 404
            ),
            onImportTle = {},
            onImportTransceivers = {},
            onDismiss = {},
            onSave = { _, _, _, _ -> }
        )
    }
}

@Composable
fun DataSourcesDialog(
    satelliteUrls: List<String>,
    transceiversUrls: List<String>,
    satelliteEnabled: List<Boolean>,
    transceiversEnabled: List<Boolean>,
    statusCodes: Map<String, Int>,
    onImportTle: () -> Unit,
    onImportTransceivers: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<String>, List<String>, List<Boolean>, List<Boolean>) -> Unit
) {
    val padding = LocalSpacing.current.large
    // Stable Long IDs avoid key collisions when several entries are empty or duplicated.
    val nextId = remember { mutableLongStateOf((satelliteUrls.size + transceiversUrls.size).toLong()) }
    val satUrls = remember {
        satelliteUrls.mapIndexed { i, url -> i.toLong() to url }.toMutableStateList()
    }
    val txUrls = remember {
        transceiversUrls.mapIndexed { i, url -> (satelliteUrls.size + i).toLong() to url }.toMutableStateList()
    }
    val satEnabled = remember {
        mutableStateMapOf<Long, Boolean>().apply {
            satUrls.forEachIndexed { i, (id, _) -> this[id] = satelliteEnabled.getOrElse(i) { true } }
        }
    }
    val txEnabled = remember {
        mutableStateMapOf<Long, Boolean>().apply {
            txUrls.forEachIndexed { i, (id, _) -> this[id] = transceiversEnabled.getOrElse(i) { true } }
        }
    }
    val onRestoreDefaults = {
        val satDefaults = Sources.satelliteDataUrls.values.filter { it.isNotBlank() }
        val txDefaults = Sources.transceiversDataUrls.values.filter { it.isNotBlank() }
        nextId.longValue = (satDefaults.size + txDefaults.size).toLong()
        satUrls.clear()
        satUrls.addAll(satDefaults.mapIndexed { i, url -> i.toLong() to url })
        txUrls.clear()
        txUrls.addAll(txDefaults.mapIndexed { i, url -> (satDefaults.size + i).toLong() to url })
        satEnabled.clear()
        txEnabled.clear()
        Unit
    }
    val listState = rememberLazyListState()
    val satDraggedId = remember { mutableStateOf(-1L) }
    val txDraggedId = remember { mutableStateOf(-1L) }
    val onAccept = {
        val satFiltered = satUrls.filter { it.second.trim().isNotBlank() }
        val txFiltered = txUrls.filter { it.second.trim().isNotBlank() }
        onSave(
            satFiltered.map { it.second.trim() },
            txFiltered.map { it.second.trim() },
            satFiltered.map { satEnabled[it.first] ?: true },
            txFiltered.map { txEnabled[it.first] ?: true }
        )
        onDismiss()
    }
    SharedDialog(
        title = stringResource(id = R.string.prefs_data_sources_title),
        onCancel = onDismiss,
        onAccept = onAccept,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight(0.84f)
                .padding(horizontal = padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CardButton(
                        onClick = { onImportTle(); onDismiss() },
                        text = "TLE/3LE (.txt)\nOMM (.csv)",
                        modifier = Modifier.weight(1f)
                    )
                    CardButton(
                        onClick = { onImportTransceivers(); onDismiss() },
                        text = "Transceivers\nSatNOGS (.json)",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                CardButton(
                    onClick = onRestoreDefaults,
                    text = stringResource(R.string.prefs_data_sources_restore),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            sourceSection(
                sectionKey = "sat",
                labelResId = R.string.prefs_data_sources_satellites_label,
                hintResId = R.string.prefs_data_sources_sat_hint,
                urls = satUrls,
                listState = listState,
                draggedId = satDraggedId,
                statusCodes = statusCodes,
                enabledMap = satEnabled,
                onToggle = { id -> satEnabled[id] = !(satEnabled[id] ?: true) },
                onAdd = { satUrls.add(nextId.longValue++ to "") },
                onMove = { from, to -> satUrls.add(to, satUrls.removeAt(from)) },
                onRemove = { i -> satUrls.removeAt(i) },
                onUrlChange = { i, v -> satUrls[i] = satUrls[i].first to v }
            )
            sourceSection(
                sectionKey = "tx",
                labelResId = R.string.prefs_data_sources_transceivers_label,
                hintResId = R.string.prefs_data_sources_transceivers_hint,
                urls = txUrls,
                listState = listState,
                draggedId = txDraggedId,
                statusCodes = statusCodes,
                enabledMap = txEnabled,
                onToggle = { id -> txEnabled[id] = !(txEnabled[id] ?: true) },
                onAdd = { txUrls.add(nextId.longValue++ to "") },
                onMove = { from, to -> txUrls.add(to, txUrls.removeAt(from)) },
                onRemove = { i -> txUrls.removeAt(i) },
                onUrlChange = { i, v -> txUrls[i] = txUrls[i].first to v }
            )
        }
    }
}

private fun LazyListScope.sourceSection(
    sectionKey: String,
    labelResId: Int,
    hintResId: Int,
    urls: List<Pair<Long, String>>,
    listState: LazyListState,
    draggedId: MutableState<Long>,
    statusCodes: Map<String, Int>,
    enabledMap: Map<Long, Boolean>,
    onToggle: (Long) -> Unit,
    onAdd: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onUrlChange: (Int, String) -> Unit
) {
    item {
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(labelResId),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            IconCard(action = onAdd, resId = R.drawable.ic_add)
        }
        // States what the order below actually decides: for satellites the elements come from
        // whichever enabled source has the freshest epoch, so the order only picks the name.
        Text(
            text = stringResource(hintResId),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 8.dp)
        )
    }
    itemsIndexed(urls, key = { _, entry -> "$sectionKey-${entry.first}" }) { index, (id, url) ->
        val enabledTint = MaterialTheme.colorScheme.onSurfaceVariant
        val sourceStatusColor = statusCodes[url]?.let { statusColor(it) }
        val sourceTextFieldColors = if (sourceStatusColor == null) {
            OutlinedTextFieldDefaults.colors()
        } else {
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = sourceStatusColor,
                unfocusedTextColor = sourceStatusColor,
                disabledTextColor = sourceStatusColor,
                focusedBorderColor = sourceStatusColor,
                unfocusedBorderColor = sourceStatusColor,
                disabledBorderColor = sourceStatusColor
            )
        }
        val enabled = enabledMap[id] ?: true
        val rowState = remember { DragRowState() }
        val scope = rememberCoroutineScope()
        val isDragging = draggedId.value == id
        val isLifted = isDragging || rowState.isSettling.value
        LaunchedEffect(isDragging) {
            if (!isDragging) return@LaunchedEffect
            autoScroll(listState, rowState.startCenterY, rowState.fingerOffset, rowState.scrollComp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .draggedVisual(
                    isLifted = isLifted,
                    translationY = if (rowState.isSettling.value) {
                        rowState.settleAnim.value
                    } else {
                        rowState.offsetY.floatValue + rowState.scrollComp.floatValue
                    }
                )
                .animateItem(
                    fadeInSpec = spring(),
                    // The dragged row repositions instantly, while its neighbours spring
                    // out of the way (the "squeeze" effect).
                    placementSpec = if (isDragging) {
                        tween<IntOffset>(durationMillis = 0)
                    } else {
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    },
                    fadeOutSpec = spring()
                )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .dragHandle(listState, sectionKey, id, urls, draggedId, rowState, scope, onMove)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_drag_handle),
                    contentDescription = null,
                    tint = enabledTint
                )
            }
            OutlinedTextField(
                value = url,
                onValueChange = { onUrlChange(index, it) },
                label = { Text(stringResource(R.string.prefs_data_sources_url_title)) },
                colors = sourceTextFieldColors,
                trailingIcon = {
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = null
                        )
                    }
                },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = enabled,
                onCheckedChange = { onToggle(id) }
            )
        }
    }
}

/**
 * Per-row drag state, kept in one object to keep the drag-handle modifier signature small.
 *
 * [offsetY] is the compensated visual displacement during a drag (finger travel minus the
 * heights of already-swapped neighbours), so the row stays glued to the finger. [fingerOffset]
 * tracks the raw finger travel for edge auto-scroll and swap detection. [settleAnim] smoothly
 * flies the lifted row back into its slot once the finger is released.
 */
private class DragRowState {
    val offsetY = mutableFloatStateOf(0f)
    val fingerOffset = mutableFloatStateOf(0f)
    val scrollComp = mutableFloatStateOf(0f)
    val startCenterY = mutableFloatStateOf(0f)
    val settleAnim = Animatable(0f)
    val isSettling = mutableStateOf(false)
}

/** Spring shared by neighbour "squeeze" and the settle-back animation: soft and slightly bouncy. */
private val reorderSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow
)

/**
 * Drag handle gesture that performs live reordering while dragging.
 */
@Composable
private fun Modifier.dragHandle(
    listState: LazyListState,
    sectionKey: String,
    entryId: Long,
    urls: List<Pair<Long, String>>,
    draggedId: MutableState<Long>,
    rowState: DragRowState,
    scope: CoroutineScope,
    onMove: (from: Int, to: Int) -> Unit
): Modifier = pointerInput(entryId, sectionKey) {
    fun reorderLive() {
        val myIndex = urls.indexOfFirst { it.first == entryId }
        if (myIndex !in urls.indices) return
        val myCenter = rowState.startCenterY.floatValue + rowState.fingerOffset.floatValue
        val visible = listState.layoutInfo.visibleItemsInfo
        // Dragging down: swap when the dragged centre passes the next row's midpoint.
        if (myIndex < urls.lastIndex) {
            val next = visible.firstOrNull { it.key == "$sectionKey-${urls[myIndex + 1].first}" }
            if (next != null && myCenter > next.offset + next.size / 2f) {
                onMove(myIndex, myIndex + 1)
                rowState.offsetY.floatValue -= next.size.toFloat()
                return
            }
        }
        // Dragging up: swap when the dragged centre passes the previous row's midpoint.
        if (myIndex > 0) {
            val prev = visible.firstOrNull { it.key == "$sectionKey-${urls[myIndex - 1].first}" }
            if (prev != null && myCenter < prev.offset + prev.size / 2f) {
                onMove(myIndex, myIndex - 1)
                rowState.offsetY.floatValue += prev.size.toFloat()
            }
        }
    }

    // Reset the drag bookkeeping and fly the lifted row back into its slot.
    fun finishDrag() {
        val lastOffset = rowState.offsetY.floatValue + rowState.scrollComp.floatValue
        if (kotlin.math.abs(lastOffset) < 1f) {
            rowState.fingerOffset.floatValue = 0f
            rowState.offsetY.floatValue = 0f
            rowState.scrollComp.floatValue = 0f
            draggedId.value = -1L
            return
        }
        scope.launch {
            rowState.settleAnim.snapTo(lastOffset)
            rowState.isSettling.value = true
            rowState.fingerOffset.floatValue = 0f
            rowState.offsetY.floatValue = 0f
            rowState.scrollComp.floatValue = 0f
            draggedId.value = -1L
            rowState.settleAnim.animateTo(0f, reorderSpring)
            rowState.isSettling.value = false
        }
    }

    detectDragGesturesAfterLongPress(
        onDragStart = {
            rowState.isSettling.value = false
            val layout = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "$sectionKey-$entryId" }
            rowState.startCenterY.floatValue = (layout?.offset ?: 0) + (layout?.size ?: 0) / 2f
            rowState.fingerOffset.floatValue = 0f
            rowState.offsetY.floatValue = 0f
            rowState.scrollComp.floatValue = 0f
            draggedId.value = entryId
        },
        onDragEnd = ::finishDrag,
        onDragCancel = ::finishDrag
    ) { change, dragAmount ->
        change.consume()
        if (draggedId.value != entryId) return@detectDragGesturesAfterLongPress
        rowState.fingerOffset.floatValue += dragAmount.y
        rowState.offsetY.floatValue += dragAmount.y
        reorderLive()
    }
}

@Composable
private fun Modifier.draggedVisual(isLifted: Boolean, translationY: Float): Modifier {
    val shape = MaterialTheme.shapes.small
    // Smoothly scale the row up/down as the lifted card appears and disappears.
    val scale by animateFloatAsState(
        targetValue = if (isLifted) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dragScale"
    )
    return this
        .graphicsLayer {
            if (isLifted) {
                this.translationY = translationY
                scaleX = scale
                scaleY = scale
            }
        }
        .then(
            if (isLifted) {
                // Solid card on top so the lifted row fully covers the row beneath it
                // instead of showing a translucent overlap of both rows.
                Modifier
                    .zIndex(1f)
                    .shadow(8.dp, shape, clip = false)
                    .background(MaterialTheme.colorScheme.surface, shape)
            } else {
                Modifier
            }
        )
}

/**
 * Scrolls the list while dragging so the entry follows the finger past the viewport edges.
 * The visual centre is tracked independently of the entry's layout slot (which can scroll out
 * of [LazyListState.layoutInfo.visibleItemsInfo] during a long drag); [startCenterY] is the
 * entry's viewport centre captured at drag start and [fingerOffset] is the raw finger delta.
 */
private suspend fun autoScroll(
    listState: LazyListState,
    startCenterY: MutableFloatState,
    fingerOffset: MutableFloatState,
    scrollComp: MutableFloatState
) {
    val threshold = 48f
    val maxSpeed = 24f
    while (true) {
        val info = listState.layoutInfo
        val center = startCenterY.floatValue + fingerOffset.floatValue
        val top = info.viewportStartOffset + threshold
        val bottom = info.viewportEndOffset - threshold
        val delta = when {
            center < top -> -(top - center).coerceAtMost(maxSpeed)
            center > bottom -> (center - bottom).coerceAtMost(maxSpeed)
            else -> 0f
        }
        if (delta != 0f) scrollComp.floatValue += listState.scrollBy(delta)
        delay(16L)
    }
}

@Composable
private fun statusColor(code: Int): Color = when {
    code == NetworkResult.CONNECTION_ERROR -> Color(0xFFC62828)
    code in 200..299 -> Color(0xFF2E7D32)
    else -> Color(0xFFC62828)
}

@Preview(showBackground = true)
@Composable
fun PreviewNetworkOutputDialog() {
    MainTheme {
        NetworkOutputDialog(
            initialSettings = RCSettings(
                rotatorState = false,
                rotatorAddress = "127.0.0.1",
                rotatorPort = "4533",
                rotatorFormat = $$"P $AZ $EL",
                frequencyState = false,
                frequencyAddress = "127.0.0.1",
                frequencyPort = "4532",
                frequencyFormat = $$"F $FREQ",
                frequencyOffsetHz = 0L,
                bluetoothRotatorState = false,
                bluetoothRotatorFormat = $$"P $AZ $EL",
                bluetoothRotatorName = "Default",
                bluetoothRotatorAddress = "00:0C:BF:13:80:5D",
                bluetoothFrequencyState = false,
                bluetoothFrequencyAddress = "00:0C:BF:13:80:5D",
                bluetoothFrequencyFormat = $$"F $FREQ"
            ),
            onDismiss = {},
            onSave = { _, _, _, _, _ -> }
        )
    }
}

@Composable
fun NetworkOutputDialog(
    initialSettings: RCSettings,
    onDismiss: () -> Unit,
    onSave: (Boolean, String, String, String, Long) -> Unit
) {
    val padding = LocalSpacing.current.large
    val frequencyState = rememberSaveable { mutableStateOf(initialSettings.frequencyState) }
    val frequencyAddress = rememberSaveable {
        mutableStateOf("${initialSettings.frequencyAddress}:${initialSettings.frequencyPort}")
    }
    val frequencyFormat = rememberSaveable { mutableStateOf(initialSettings.frequencyFormat) }
    val frequencyOffsetHz = rememberSaveable { mutableStateOf(initialSettings.frequencyOffsetHz.toString()) }
    val onAccept = {
        val (freqIp, freqPort) = splitAddress(frequencyAddress.value)
        val offsetHz = (frequencyOffsetHz.value.trim().toLongOrNull() ?: 0L)
            .coerceIn(Constants.FREQ_OFFSET_MIN_HZ, Constants.FREQ_OFFSET_MAX_HZ)
        onSave(frequencyState.value, freqIp, freqPort, frequencyFormat.value, offsetHz)
        onDismiss()
    }
    SharedDialog(
        title = stringResource(R.string.prefs_net_title),
        onCancel = onDismiss,
        onAccept = onAccept
    ) {
        Column(modifier = Modifier.padding(horizontal = padding)) {
            OutputChannelSection(
                switchLabel = stringResource(R.string.prefs_net_frequency_switch),
                enabled = frequencyState.value,
                onEnabledChange = { frequencyState.value = it },
                address = frequencyAddress.value,
                onAddressChange = { frequencyAddress.value = it },
                addressLabel = stringResource(R.string.prefs_net_frequency_address_hint),
                format = frequencyFormat.value,
                onFormatChange = { frequencyFormat.value = it },
                formatLabel = stringResource(R.string.prefs_net_frequency_format_hint)
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = frequencyOffsetHz.value,
                onValueChange = { frequencyOffsetHz.value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.prefs_net_frequency_offset_hint)) },
                supportingText = { Text(stringResource(R.string.prefs_net_frequency_offset_help)) },
                trailingIcon = {
                    IconButton(
                        onClick = { frequencyOffsetHz.value = "0" },
                        enabled = frequencyState.value && frequencyOffsetHz.value != "0"
                    ) {
                        Icon(painter = painterResource(R.drawable.ic_close), contentDescription = null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = frequencyState.value
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun splitAddress(address: String): Pair<String, String> {
    val lastColon = address.lastIndexOf(':')
    return if (lastColon >= 0) {
        address.substring(0, lastColon) to address.substring(lastColon + 1)
    } else {
        address to ""
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewBluetoothOutputDialog() {
    MainTheme {
        BluetoothOutputDialog(
            initialSettings = RCSettings(
                rotatorState = false,
                rotatorAddress = "127.0.0.1",
                rotatorPort = "4533",
                rotatorFormat = $$"P $AZ $EL",
                frequencyState = false,
                frequencyAddress = "127.0.0.1",
                frequencyPort = "4532",
                frequencyFormat = $$"F $FREQ",
                frequencyOffsetHz = 0L,
                bluetoothRotatorState = false,
                bluetoothRotatorFormat = $$"P $AZ $EL",
                bluetoothRotatorName = "Default",
                bluetoothRotatorAddress = "00:0C:BF:13:80:5D",
                bluetoothFrequencyState = false,
                bluetoothFrequencyAddress = "00:0C:BF:13:80:5D",
                bluetoothFrequencyFormat = $$"F $FREQ"
            ),
            onDismiss = {},
            onSave = { _, _, _ -> }
        )
    }
}

@Composable
fun BluetoothOutputDialog(
    initialSettings: RCSettings,
    onDismiss: () -> Unit,
    onSave: (Boolean, String, String) -> Unit
) {
    val padding = LocalSpacing.current.large
    val frequencyState = rememberSaveable { mutableStateOf(initialSettings.bluetoothFrequencyState) }
    val frequencyAddress = rememberSaveable { mutableStateOf(initialSettings.bluetoothFrequencyAddress) }
    val frequencyFormat = rememberSaveable { mutableStateOf(initialSettings.bluetoothFrequencyFormat) }
    val onAccept = {
        onSave(frequencyState.value, frequencyAddress.value, frequencyFormat.value)
        onDismiss()
    }
    SharedDialog(
        title = stringResource(R.string.prefs_bt_title),
        onCancel = onDismiss,
        onAccept = onAccept
    ) {
        Column(modifier = Modifier.padding(horizontal = padding)) {
            OutputChannelSection(
                switchLabel = stringResource(R.string.prefs_bt_frequency_switch),
                enabled = frequencyState.value,
                onEnabledChange = { frequencyState.value = it },
                address = frequencyAddress.value,
                onAddressChange = { frequencyAddress.value = it },
                addressLabel = stringResource(R.string.prefs_bt_frequency_device_hint),
                format = frequencyFormat.value,
                onFormatChange = { frequencyFormat.value = it },
                formatLabel = stringResource(R.string.prefs_bt_frequency_output_hint)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * Reusable section for a switch-toggled output channel with address and format fields.
 * Used by both Network and Bluetooth output dialogs.
 */
@Composable
private fun OutputChannelSection(
    switchLabel: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    address: String,
    onAddressChange: (String) -> Unit,
    addressLabel: String,
    format: String,
    onFormatChange: (String) -> Unit,
    formatLabel: String
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(switchLabel)
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            singleLine = true,
            label = { Text(addressLabel) },
            modifier = Modifier.weight(0.6f),
            enabled = enabled
        )
        OutlinedTextField(
            value = format,
            onValueChange = onFormatChange,
            singleLine = true,
            label = { Text(formatLabel) },
            modifier = Modifier.weight(0.4f),
            enabled = enabled
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadioControlDialog(
    initialSettings: RadioControlSettings,
    onDismiss: () -> Unit,
    onSave: (RadioControlSettings) -> Unit,
    frequencyLocked:Boolean=false,
    onOpenProfiles:()->Unit={}
) {
    val context    = androidx.compose.ui.platform.LocalContext.current
    val padding    = LocalSpacing.current.large
    val enabled    = rememberSaveable { mutableStateOf(initialSettings.enabled) }
    val radioModel = rememberSaveable { mutableStateOf(initialSettings.radioModel) }
    val initialIsIcom = radioModelDescriptor(initialSettings.radioModel).protocolFamily ==
        RadioProtocolFamily.ICOM_CIV
    val initialBaudRates = radioBaudRates(initialSettings.radioModel)
    val splitMode  = rememberSaveable { mutableStateOf(initialSettings.splitMode && initialIsIcom) }
    val duplexMode = rememberSaveable { mutableStateOf(initialSettings.duplexMode) }
    val catTransport = rememberSaveable { mutableStateOf(initialSettings.catTransport) }
    val tcpProtocol = rememberSaveable { mutableStateOf(initialSettings.tcpProtocol) }
    val txAddress  = rememberSaveable { mutableStateOf(initialSettings.txRadioAddress) }
    val rxAddress  = rememberSaveable { mutableStateOf(initialSettings.rxRadioAddress) }
    val initialTxName = initialSettings.txRadioName.takeUnless {
        initialSettings.catTransport == RadioControlSettings.TRANSPORT_TCP
    }.orEmpty()
    val initialRxName = initialSettings.rxRadioName.takeUnless {
        initialSettings.catTransport == RadioControlSettings.TRANSPORT_TCP
    }.orEmpty()
    val txName     = rememberSaveable { mutableStateOf(initialTxName) }
    val rxName     = rememberSaveable { mutableStateOf(initialRxName) }
    val baudRate   = rememberSaveable {
        mutableIntStateOf(initialSettings.baudRate.takeIf { it in initialBaudRates } ?: initialBaudRates.first())
    }
    val dialSettleMillis = rememberSaveable {
        mutableStateOf(initialSettings.dialSettleMillis.toString())
    }
    val linearDialDeadbandHz = rememberSaveable {
        mutableStateOf(initialSettings.linearDialDeadbandHz.toString())
    }
    val fmDialDeadbandHz = rememberSaveable {
        mutableStateOf(initialSettings.fmDialDeadbandHz.toString())
    }
    val l4sDopplerStepHz = rememberSaveable { mutableStateOf(initialSettings.l4sDopplerStepHz.toString()) }
    val l4sCorrectionStepHz = rememberSaveable { mutableStateOf(initialSettings.l4sCorrectionEditStepHz.toString()) }
    val sharedBusDelayMillis = rememberSaveable {
        mutableStateOf(initialSettings.sharedBusCommandDelayMillis.toString())
    }
    val initialCivAddress = initialSettings.civAddress
        ?: defaultCivAddress(initialSettings.radioModel)
    val civAddress = rememberSaveable {
        mutableStateOf(initialCivAddress?.let { "0x%02X".format(it) }.orEmpty())
    }
    val txAddressError = rememberSaveable { mutableStateOf(false) }
    val rxAddressError = rememberSaveable { mutableStateOf(false) }
    val civAddressError = rememberSaveable { mutableStateOf(false) }
    val txAddressByTransport = remember {
        mutableStateMapOf(initialSettings.catTransport to initialSettings.txRadioAddress)
    }
    val rxAddressByTransport = remember {
        mutableStateMapOf(initialSettings.catTransport to initialSettings.rxRadioAddress)
    }
    val txNameByTransport = remember {
        mutableStateMapOf(initialSettings.catTransport to initialTxName)
    }
    val rxNameByTransport = remember {
        mutableStateMapOf(initialSettings.catTransport to initialRxName)
    }
    val selectingFor = rememberSaveable { mutableStateOf("") } // "tx", "rx", or ""
    val bluetoothDevicesRevision = remember { mutableIntStateOf(0) }
    val usbDevicesRevision = remember { mutableIntStateOf(0) }
    val txUsbError = remember { mutableStateOf<UsbSelectionError?>(null) }
    val rxUsbError = remember { mutableStateOf<UsbSelectionError?>(null) }
    val usbPermissionDenied = remember { mutableStateOf(false) }
    val pendingUsbSelection = remember { mutableStateOf<PendingUsbSelection?>(null) }
    val usbManager = remember(context) { context.getSystemService(UsbManager::class.java) }
    val usbPermissionAction = remember(context.packageName) {
        "${context.packageName}.USB_CAT_PERMISSION"
    }
    val usbSerialDeviceFormat = stringResource(R.string.rc_usb_cdc_device)
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        bluetoothDevicesRevision.intValue++
    }

    val applyUsbSelection: (PendingUsbSelection) -> Unit = { selection ->
        if (selection.target == "tx") {
            txAddress.value = selection.address
            txName.value = selection.name
            txUsbError.value = null
        } else {
            rxAddress.value = selection.address
            rxName.value = selection.name
            rxUsbError.value = null
        }
        usbPermissionDenied.value = false
        selectingFor.value = ""
    }

    DisposableEffect(context, usbPermissionAction) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    usbPermissionAction -> {
                        usbDevicesRevision.intValue++
                        val selection = pendingUsbSelection.value
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && selection != null) {
                            applyUsbSelection(selection)
                        } else if (!granted) {
                            usbPermissionDenied.value = true
                        }
                        pendingUsbSelection.value = null
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> usbDevicesRevision.intValue++
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(usbPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val selectDevice: (String) -> Unit = { target ->
        selectingFor.value = target
        usbPermissionDenied.value = false
        if (
            catTransport.value == RadioControlSettings.TRANSPORT_BLUETOOTH &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    val modelDescriptor = radioModelDescriptor(radioModel.value)
    val isIcom = modelDescriptor.protocolFamily == RadioProtocolFamily.ICOM_CIV
    val isL4s = modelDescriptor.protocolFamily == RadioProtocolFamily.L4S
    val isFtx1 = radioModel.value == RadioControlSettings.MODEL_YAESU_FTX1
    val isVox = catTransport.value == RadioControlSettings.TRANSPORT_VOX
    LaunchedEffect(catTransport.value) {
        val models=com.rtbishop.look4sat.core.domain.model.RadioModelCatalog.modelsForTransport(catTransport.value)
        if (!isVox && radioModel.value !in models) {
            radioModel.value=models.first()
            baudRate.intValue=radioBaudRates(radioModel.value).first()
            defaultCivAddress(radioModel.value)?.let { civAddress.value="0x%02X".format(it) }
            splitMode.value=false
            txAddress.value=""; rxAddress.value=""; txName.value=""; rxName.value=""
        }
    }
    val supportsSatelliteMode = modelDescriptor.capabilities.satelliteMode &&
        !(catTransport.value == RadioControlSettings.TRANSPORT_TCP &&
            tcpProtocol.value == RadioControlSettings.TCP_PROTOCOL_HAMLIB)
    val isSingleRadio = modelDescriptor.capabilities.singleRadioSplit && (splitMode.value || isL4s)
    val requiredStopBits = modelDescriptor.serialStopBits

    val baudRates = radioBaudRates(radioModel.value)

    val pairedDevices: List<RadioDeviceUiEntry> = remember(
        catTransport.value,
        radioModel.value,
        usbSerialDeviceFormat,
        bluetoothDevicesRevision.intValue,
        usbDevicesRevision.intValue
    ) {
        buildList {
            try {
                if (catTransport.value == RadioControlSettings.TRANSPORT_USB) {
                    usbManager.deviceList.values.forEach { device ->
                        device.usbSerialPorts().forEach { port ->
                            val product = runCatching { device.productName }.getOrNull() ?: "USB"
                            val identity = "%04X:%04X".format(device.vendorId, device.productId)
                            val name = usbSerialDeviceFormat.format(
                                product,
                                port.driverName,
                                port.dataInterfaceId,
                                identity
                            )
                            val selector = listOf(
                                device.deviceId,
                                port.controlInterfaceId,
                                port.dataInterfaceId,
                                device.vendorId,
                                device.productId
                            ).joinToString(":")
                            add(
                                RadioDeviceUiEntry(
                                    name = name,
                                    address = selector,
                                    usbDeviceId = device.deviceId,
                                    hasUsbPermission = usbManager.hasPermission(device),
                                    isSupported = port.supportsLineConfiguration(
                                        interfaceCount = device.interfaceCount,
                                        stopBits = requiredStopBits
                                    )
                                )
                            )
                        }
                    }
                    return@buildList
                }
                if (catTransport.value != RadioControlSettings.TRANSPORT_BLUETOOTH) return@buildList
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@buildList
                }
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                manager.adapter?.bondedDevices?.forEach {
                    add(RadioDeviceUiEntry(it.name ?: "Unknown", it.address ?: ""))
                }
            } catch (_: SecurityException) { }
        }
    }

    val onAccept = accept@{
        txAddressError.value = false
        rxAddressError.value = false
        civAddressError.value = false
        txUsbError.value = null
        rxUsbError.value = null
        if (enabled.value && catTransport.value == RadioControlSettings.TRANSPORT_TCP) {
            val bothBlank = !isSingleRadio && txAddress.value.isBlank() && rxAddress.value.isBlank()
            txAddressError.value = (isSingleRadio && txAddress.value.isBlank()) || bothBlank ||
                (txAddress.value.isNotBlank() && parseRadioTcpEndpoint(txAddress.value) == null)
            rxAddressError.value = !isSingleRadio && (bothBlank ||
                (rxAddress.value.isNotBlank() && parseRadioTcpEndpoint(rxAddress.value) == null))
        }
        if (enabled.value && catTransport.value == RadioControlSettings.TRANSPORT_USB) {
            val bothBlank = !isSingleRadio && txAddress.value.isBlank() && rxAddress.value.isBlank()
            txUsbError.value = when {
                (isSingleRadio && txAddress.value.isBlank()) || bothBlank -> UsbSelectionError.NOT_SELECTED
                txAddress.value.isBlank() -> null
                else -> validateUsbSelection(usbManager, txAddress.value, requiredStopBits)
            }
            rxUsbError.value = when {
                isSingleRadio -> null
                bothBlank -> UsbSelectionError.NOT_SELECTED
                rxAddress.value.isBlank() -> null
                else -> validateUsbSelection(usbManager, rxAddress.value, requiredStopBits)
            }
        }
        val parsedCivAddress = if (!isVox && isIcom) parseCivAddress(civAddress.value) else null
        civAddressError.value = !isVox && isIcom &&
            !(catTransport.value == RadioControlSettings.TRANSPORT_TCP &&
                tcpProtocol.value == RadioControlSettings.TCP_PROTOCOL_HAMLIB) &&
            parsedCivAddress == null
        if (
            txAddressError.value || rxAddressError.value || civAddressError.value ||
            txUsbError.value != null || rxUsbError.value != null
        ) return@accept
        val parsedL4sStep=l4sDopplerStepHz.value.toLongOrNull()
        if(isL4s && (parsedL4sStep==null || parsedL4sStep !in 10L..10_000L || parsedL4sStep%10!=0L)) return@accept
        if(frequencyLocked)return@accept
        val correctionStep=l4sCorrectionStepHz.value.toLongOrNull()
        if(isL4s && (correctionStep==null || correctionStep !in 10L..1000L || correctionStep%10!=0L))return@accept
        onSave(
            RadioControlSettings(
                enabled        = enabled.value,
                radioModel     = radioModel.value,
                txRadioAddress = txAddress.value,
                rxRadioAddress = if (isSingleRadio) "" else rxAddress.value,
                txRadioName    = if (
                    catTransport.value == RadioControlSettings.TRANSPORT_TCP
                ) "" else txName.value,
                rxRadioName    = if (
                    isSingleRadio || catTransport.value == RadioControlSettings.TRANSPORT_TCP
                ) "" else rxName.value,
                baudRate       = baudRate.intValue,
                splitMode      = splitMode.value,
                catTransport   = catTransport.value,
                duplexMode     = if (supportsSatelliteMode) {
                    duplexMode.value
                } else {
                    RadioControlSettings.DUPLEX_MODE_SPLIT
                },
                civAddress     = parsedCivAddress,
                tcpProtocol    = tcpProtocol.value,
                dialSettleMillis = dialSettleMillis.value.toLongOrNull() ?: 1_500L,
                linearDialDeadbandHz = linearDialDeadbandHz.value.toLongOrNull() ?: 20L,
                fmDialDeadbandHz = fmDialDeadbandHz.value.toLongOrNull() ?: 200L,
                sharedBusCommandDelayMillis = sharedBusDelayMillis.value.toLongOrNull() ?: 0L,
                l4sDopplerStepHz = parsedL4sStep ?: 250L,
                l4sCorrectionEditStepHz = correctionStep ?: 10L
            )
        )
        onDismiss()
    }

    SharedDialog(
        title    = stringResource(R.string.rc_settings_title),
        onCancel = onDismiss,
        onAccept = onAccept
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.82f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = padding)
        ) {

            // Enable switch
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
                modifier              = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.rc_enable_switch))
                Switch(checked = enabled.value, onCheckedChange = { enabled.value = it })
            }
            Spacer(modifier = Modifier.height(6.dp))

            Text(stringResource(R.string.rc_cat_transport), fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RadioControlSettings.SUPPORTED_TRANSPORTS.filter {
                    !(isL4s || isFtx1) || it == RadioControlSettings.TRANSPORT_USB
                }.forEach { transport ->
                    FilterChip(
                        selected = catTransport.value == transport,
                        onClick = {
                            txAddressByTransport[catTransport.value] = txAddress.value
                            rxAddressByTransport[catTransport.value] = rxAddress.value
                            txNameByTransport[catTransport.value] = txName.value
                            rxNameByTransport[catTransport.value] = rxName.value
                            catTransport.value = transport
                            txAddress.value = txAddressByTransport[transport].orEmpty()
                            rxAddress.value = rxAddressByTransport[transport].orEmpty()
                            txName.value = txNameByTransport[transport].orEmpty()
                            rxName.value = rxNameByTransport[transport].orEmpty()
                            txAddressError.value = false
                            rxAddressError.value = false
                            txUsbError.value = null
                            rxUsbError.value = null
                            usbPermissionDenied.value = false
                            selectingFor.value = ""
                        },
                        label = { Text(transport, fontSize = 12.sp) },
                        enabled = enabled.value
                    )
                }
            }
            if (catTransport.value == RadioControlSettings.TRANSPORT_TCP) {
                Text(stringResource(R.string.rc_tcp_protocol), fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RadioControlSettings.SUPPORTED_TCP_PROTOCOLS.forEach { protocol ->
                        FilterChip(
                            selected = tcpProtocol.value == protocol,
                            onClick = { tcpProtocol.value = protocol },
                            label = {
                                Text(
                                    stringResource(
                                        if (protocol == RadioControlSettings.TCP_PROTOCOL_HAMLIB) {
                                            R.string.rc_tcp_protocol_hamlib
                                        } else {
                                            R.string.rc_tcp_protocol_raw
                                        }
                                    ),
                                    fontSize = 12.sp
                                )
                            },
                            enabled = enabled.value
                        )
                    }
                }
            }
            if (!isVox) {
            Spacer(modifier = Modifier.height(6.dp))

            // Radio model — FlowRow so chips wrap on small screens
            Text(
                text       = stringResource(R.string.rc_radio_model),
                fontWeight = FontWeight.Medium,
                color      = androidx.compose.material3.MaterialTheme.colorScheme.primary
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                com.rtbishop.look4sat.core.domain.model.RadioModelCatalog.modelsForTransport(catTransport.value).forEach { model ->
                    FilterChip(
                        selected = radioModel.value == model,
                        onClick  = {
                            val oldDefaultAddress = defaultCivAddress(radioModel.value)
                            val currentAddress = parseCivAddress(civAddress.value)
                            val modelChanged = radioModel.value != model
                            radioModel.value = model
                            if (radioModelDescriptor(model).protocolFamily == RadioProtocolFamily.L4S ||
                                model == RadioControlSettings.MODEL_YAESU_FTX1) {
                                splitMode.value = true
                                if (catTransport.value != RadioControlSettings.TRANSPORT_USB) {
                                    txAddressByTransport[catTransport.value] = txAddress.value
                                    txNameByTransport[catTransport.value] = txName.value
                                    catTransport.value = RadioControlSettings.TRANSPORT_USB
                                    txAddress.value = txAddressByTransport[RadioControlSettings.TRANSPORT_USB].orEmpty()
                                    txName.value = txNameByTransport[RadioControlSettings.TRANSPORT_USB].orEmpty()
                                }
                            }
                            val newCapabilities = radioModelDescriptor(model).capabilities
                            if (!newCapabilities.singleRadioSplit) splitMode.value = false
                            if (!newCapabilities.satelliteMode) {
                                duplexMode.value = RadioControlSettings.DUPLEX_MODE_SPLIT
                            }
                            val modelRates = radioBaudRates(model)
                            if ((modelChanged && model in setOf(RadioControlSettings.MODEL_ICOM_IC820,RadioControlSettings.MODEL_YAESU_FTX1)) || baudRate.intValue !in modelRates) {
                                baudRate.intValue = modelRates.first()
                            }
                            if (
                                oldDefaultAddress == null || currentAddress == oldDefaultAddress ||
                                currentAddress == null
                            ) {
                                defaultCivAddress(model)?.let { civAddress.value = "0x%02X".format(it) }
                            }
                            civAddressError.value = false
                            txUsbError.value = null
                            rxUsbError.value = null
                        },
                        label    = { Text(model, fontSize = 12.sp) },
                        enabled  = enabled.value
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (isFtx1) Text(stringResource(R.string.rc_ftx1_usb_hint),style=MaterialTheme.typography.bodySmall)
            if (
                isIcom &&
                !(catTransport.value == RadioControlSettings.TRANSPORT_TCP &&
                    tcpProtocol.value == RadioControlSettings.TCP_PROTOCOL_HAMLIB)
            ) {
                OutlinedTextField(
                    value = civAddress.value,
                    onValueChange = {
                        civAddress.value = it
                        civAddressError.value = false
                    },
                    label = { Text(stringResource(R.string.rc_civ_address)) },
                    supportingText = if (civAddressError.value) {
                        { Text(stringResource(R.string.rc_civ_address_error)) }
                    } else null,
                    isError = civAddressError.value,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled.value
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Single-radio duplex control for Icom split or dedicated satellite mode.
            if (radioModel.value == RadioControlSettings.MODEL_ICOM_IC820) {
                Text(
                    text = stringResource(R.string.rc_ic820_limits),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            if (isL4s) {
                val step=l4sDopplerStepHz.value.toLongOrNull()
                OutlinedTextField(value=l4sDopplerStepHz.value,onValueChange={l4sDopplerStepHz.value=it},
                    label={Text(stringResource(R.string.rc_l4s_step))},
                    isError=step==null || step !in 10L..10_000L || step%10!=0L,
                    enabled=enabled.value && !frequencyLocked,singleLine=true,modifier=Modifier.fillMaxWidth())
                val correctionStep=l4sCorrectionStepHz.value.toLongOrNull()
                OutlinedTextField(value=l4sCorrectionStepHz.value,onValueChange={l4sCorrectionStepHz.value=it},
                    label={Text(stringResource(R.string.rc_l4s_correction_step))},
                    isError=correctionStep==null || correctionStep !in 10L..1000L || correctionStep%10!=0L,
                    enabled=enabled.value && !frequencyLocked,singleLine=true,modifier=Modifier.fillMaxWidth())
                if(frequencyLocked)Text(stringResource(R.string.rc_l4s_edit_locked),color=MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick=onOpenProfiles,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.rc_l4s_profiles))}
            }
            if (modelDescriptor.capabilities.singleRadioSplit && !isL4s) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                    modifier              = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.rc_split_mode),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked         = splitMode.value,
                        onCheckedChange = { splitMode.value = it },
                        enabled         = enabled.value
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (isSingleRadio && supportsSatelliteMode) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.rc_satellite_mode),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = duplexMode.value == RadioControlSettings.DUPLEX_MODE_SATELLITE,
                        onCheckedChange = { enabled ->
                            duplexMode.value = if (enabled) {
                                RadioControlSettings.DUPLEX_MODE_SATELLITE
                            } else {
                                RadioControlSettings.DUPLEX_MODE_SPLIT
                            }
                        },
                        enabled = enabled.value
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // TX radio (the sole CAT connection in single-radio duplex mode).
            val txLabel = stringResource(when {
                isL4s -> R.string.rc_l4s_radio
                isSingleRadio -> R.string.rc_single_radio
                else -> R.string.rc_tx_radio
            })
            Text(txLabel, fontWeight = FontWeight.Medium)
            if (catTransport.value == RadioControlSettings.TRANSPORT_TCP) {
                OutlinedTextField(
                    value = txAddress.value,
                    onValueChange = {
                        txAddress.value = it
                        txAddressError.value = false
                    },
                    label = { Text(stringResource(R.string.rc_tx_tcp_address)) },
                    singleLine = true,
                    isError = txAddressError.value,
                    supportingText = if (txAddressError.value) {
                        { Text(stringResource(R.string.rc_tcp_address_error)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled.value
                )
            } else {
                if (txAddress.value.isNotBlank()) {
                    Text("${txName.value} — ${txAddress.value}", fontSize = 13.sp)
                }
                CardButton(
                    onClick  = { selectDevice("tx") },
                    text     = stringResource(if (isSingleRadio) R.string.rc_select_device else R.string.rc_select_tx_device),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            txUsbError.value?.takeIf {
                catTransport.value == RadioControlSettings.TRANSPORT_USB
            }?.let { error ->
                Text(
                    text = stringResource(error.messageResource),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            // RX Radio (hidden in split mode — the same radio handles both)
            if (!isSingleRadio) {
                Text(stringResource(R.string.rc_rx_radio), fontWeight = FontWeight.Medium)
                if (catTransport.value == RadioControlSettings.TRANSPORT_TCP) {
                    OutlinedTextField(
                        value = rxAddress.value,
                        onValueChange = {
                            rxAddress.value = it
                            rxAddressError.value = false
                        },
                        label = { Text(stringResource(R.string.rc_rx_tcp_address)) },
                        singleLine = true,
                        isError = rxAddressError.value,
                        supportingText = if (rxAddressError.value) {
                            { Text(stringResource(R.string.rc_tcp_address_error)) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled.value
                    )
                } else {
                    if (rxAddress.value.isNotBlank()) {
                        Text("${rxName.value} — ${rxAddress.value}", fontSize = 13.sp)
                    }
                    CardButton(
                        onClick  = { selectDevice("rx") },
                        text     = stringResource(R.string.rc_select_rx_device),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                rxUsbError.value?.takeIf {
                    catTransport.value == RadioControlSettings.TRANSPORT_USB
                }?.let { error ->
                    Text(
                        text = stringResource(error.messageResource),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Paired device picker (inline, shown while selecting)
            if (selectingFor.value.isNotBlank()) {
                Text(
                    text       = stringResource(R.string.rc_paired_devices),
                    fontWeight = FontWeight.Medium,
                    color      = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (pairedDevices.isEmpty()) {
                    Text(
                        stringResource(R.string.rc_no_paired_devices),
                        fontSize = 13.sp
                    )
                } else {
                    pairedDevices.forEach { entry ->
                        androidx.compose.material3.Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = entry.isSupported) {
                                    if (catTransport.value == RadioControlSettings.TRANSPORT_USB) {
                                        val device = usbManager.deviceList.values.firstOrNull {
                                            it.deviceId == entry.usbDeviceId
                                        }
                                        if (device == null) {
                                            if (selectingFor.value == "tx") {
                                                txUsbError.value = UsbSelectionError.DEVICE_MISSING
                                            } else {
                                                rxUsbError.value = UsbSelectionError.DEVICE_MISSING
                                            }
                                            usbDevicesRevision.intValue++
                                            return@clickable
                                        }
                                        val selection = PendingUsbSelection(
                                            selectingFor.value,
                                            entry.name,
                                            entry.address
                                        )
                                        if (!usbManager.hasPermission(device)) {
                                            pendingUsbSelection.value = selection
                                            usbPermissionDenied.value = false
                                            val permissionIntent = PendingIntent.getBroadcast(
                                                context,
                                                device.deviceId,
                                                Intent(usbPermissionAction)
                                                    .setPackage(context.packageName),
                                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                            )
                                            usbManager.requestPermission(device, permissionIntent)
                                            return@clickable
                                        }
                                        applyUsbSelection(selection)
                                    } else {
                                        if (selectingFor.value == "tx") {
                                            txAddress.value = entry.address
                                            txName.value = entry.name
                                        } else {
                                            rxAddress.value = entry.address
                                            rxName.value = entry.name
                                        }
                                        selectingFor.value = ""
                                    }
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = entry.name,
                                        modifier = Modifier.weight(1f),
                                        color = if (entry.isSupported) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Text(entry.address, fontSize = 12.sp)
                                }
                                if (!entry.isSupported) {
                                    Text(
                                        stringResource(R.string.rc_usb_port_incompatible),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else if (
                                    catTransport.value == RadioControlSettings.TRANSPORT_USB &&
                                    !entry.hasUsbPermission
                                ) {
                                    Text(
                                        stringResource(R.string.rc_usb_permission_tap),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                if (
                    catTransport.value == RadioControlSettings.TRANSPORT_USB &&
                    usbPermissionDenied.value
                ) {
                    Text(
                        stringResource(R.string.rc_usb_permission_denied),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (catTransport.value != RadioControlSettings.TRANSPORT_TCP) {
                // Baud rate belongs to the local serial link. TCP bridges/rigctld own their serial settings.
                Text(stringResource(R.string.rc_baud_rate), fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    baudRates.forEach { rate ->
                        FilterChip(
                            selected = rate == baudRate.intValue,
                            onClick  = { baudRate.intValue = rate },
                            label    = { Text(rate.toString(), fontSize = 12.sp) },
                            enabled  = enabled.value
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
            Text(stringResource(R.string.rc_dial_follow), fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dialSettleMillis.value,
                    onValueChange = { dialSettleMillis.value = it },
                    label = { Text(stringResource(R.string.rc_dial_settle_ms)) },
                    singleLine = true,
                    enabled = enabled.value,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = sharedBusDelayMillis.value,
                    onValueChange = { sharedBusDelayMillis.value = it },
                    label = { Text(stringResource(R.string.rc_shared_bus_delay_ms)) },
                    singleLine = true,
                    enabled = enabled.value,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = linearDialDeadbandHz.value,
                    onValueChange = { linearDialDeadbandHz.value = it },
                    label = { Text(stringResource(R.string.rc_linear_deadband_hz)) },
                    singleLine = true,
                    enabled = enabled.value,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = fmDialDeadbandHz.value,
                    onValueChange = { fmDialDeadbandHz.value = it },
                    label = { Text(stringResource(R.string.rc_fm_deadband_hz)) },
                    singleLine = true,
                    enabled = enabled.value,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

private fun radioBaudRates(model: String): List<Int> = supportedRadioBaudRates(model)

private fun defaultCivAddress(model: String): Int? = radioModelDescriptor(model).defaultCivAddress

private fun parseCivAddress(value: String): Int? {
    val text = value.trim()
    val parsed = if (text.startsWith("0x", ignoreCase = true)) {
        text.substring(2).toIntOrNull(16)
    } else {
        text.toIntOrNull()
    }
    return parsed?.takeIf { it in 0..0xFF }
}

internal data class UsbSerialUiPort(
    val driverName: String,
    val controlInterfaceId: Int,
    val dataInterfaceId: Int,
    val portIndex: Int
) {
    fun supportsLineConfiguration(interfaceCount: Int, stopBits: Int): Boolean {
        val restrictedCp2105Port = driverName == "CP210x" && interfaceCount == 2 && portIndex == 1
        return !restrictedCp2105Port || stopBits != 2
    }
}

internal data class RadioDeviceUiEntry(
    val name: String,
    val address: String,
    val usbDeviceId: Int? = null,
    val hasUsbPermission: Boolean = true,
    val isSupported: Boolean = true
)

private data class PendingUsbSelection(
    val target: String,
    val name: String,
    val address: String
)

private enum class UsbSelectionError(val messageResource: Int) {
    NOT_SELECTED(R.string.rc_usb_device_required),
    DEVICE_MISSING(R.string.rc_usb_device_missing),
    PERMISSION_REQUIRED(R.string.rc_usb_permission_required),
    INCOMPATIBLE(R.string.rc_usb_port_incompatible)
}

private data class UsbSerialUiSelector(
    val deviceId: Int,
    val controlInterfaceId: Int,
    val dataInterfaceId: Int,
    val vendorId: Int?,
    val productId: Int?
)

private fun validateUsbSelection(
    manager: UsbManager,
    value: String,
    stopBits: Int
): UsbSelectionError? {
    val selector = parseUsbSerialUiSelector(value) ?: return UsbSelectionError.DEVICE_MISSING
    val device = resolveUsbSerialUiDevice(manager.deviceList.values, selector)
        ?: return UsbSelectionError.DEVICE_MISSING
    val port = device.usbSerialPorts().firstOrNull {
        it.controlInterfaceId == selector.controlInterfaceId &&
            it.dataInterfaceId == selector.dataInterfaceId
    } ?: return UsbSelectionError.DEVICE_MISSING
    if (!port.supportsLineConfiguration(device.interfaceCount, stopBits)) {
        return UsbSelectionError.INCOMPATIBLE
    }
    return if (manager.hasPermission(device)) null else UsbSelectionError.PERMISSION_REQUIRED
}

private fun parseUsbSerialUiSelector(value: String): UsbSerialUiSelector? {
    val parts = value.split(':')
    if (parts.size != 3 && parts.size != 5) return null
    val deviceId = parts[0].toIntOrNull() ?: return null
    val controlId = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val dataId = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val vendorId = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it in 0..0xFFFF }
    val productId = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it in 0..0xFFFF }
    if (parts.size == 5 && (vendorId == null || productId == null)) return null
    return UsbSerialUiSelector(deviceId, controlId, dataId, vendorId, productId)
}

private fun resolveUsbSerialUiDevice(
    devices: Collection<UsbDevice>,
    selector: UsbSerialUiSelector
): UsbDevice? {
    val matchesIdentity: (UsbDevice) -> Boolean = { device ->
        selector.vendorId == null ||
            device.vendorId == selector.vendorId && device.productId == selector.productId
    }
    devices.firstOrNull { it.deviceId == selector.deviceId && matchesIdentity(it) }?.let { return it }
    if (selector.vendorId == null || selector.productId == null) return null
    return devices.filter(matchesIdentity).singleOrNull()
}

internal fun UsbDevice.usbSerialPorts(): List<UsbSerialUiPort> {
    val interfaces = (0 until interfaceCount).map(::getInterface)
    val controls = interfaces.filter {
        it.interfaceClass == UsbConstants.USB_CLASS_COMM && it.interfaceSubclass == 0x02
    }
    val dataInterfaces = interfaces.filter { intf ->
        intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA &&
            (0 until intf.endpointCount).map(intf::getEndpoint).any {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
            } &&
            (0 until intf.endpointCount).map(intf::getEndpoint).any {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
            }
    }
    val cdcPorts = controls.mapNotNull { control ->
        dataInterfaces.firstOrNull { it.id == control.id + 1 }?.let {
            UsbSerialUiPort("CDC-ACM", control.id, it.id, interfaces.indexOf(it))
        }
    }.ifEmpty {
        if (controls.size == 1 && dataInterfaces.size == 1) {
            val data = dataInterfaces.single()
            listOf(UsbSerialUiPort("CDC-ACM", controls.single().id, data.id, interfaces.indexOf(data)))
        } else {
            emptyList()
        }
    }
    if (cdcPorts.isNotEmpty()) return cdcPorts

    val driverName = when (vendorId) {
        0x10C4 -> "CP210x"
        0x0403 -> "FTDI"
        0x1A86, 0x4348 -> "CH34x"
        else -> return emptyList()
    }
    val serialInterfaces = interfaces.mapIndexedNotNull { index, intf ->
        val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint)
        val hasBulkPair = endpoints.any {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
        } && endpoints.any {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
        }
        if (hasBulkPair) intf to index else null
    }
    val ports = if (driverName == "CH34x") serialInterfaces.takeLast(1) else serialInterfaces
    return ports.map { (intf, index) -> UsbSerialUiPort(driverName, intf.id, intf.id, index) }
}
