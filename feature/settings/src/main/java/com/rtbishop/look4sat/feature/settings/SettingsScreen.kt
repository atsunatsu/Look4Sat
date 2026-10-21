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
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.operatorCallsign
import com.rtbishop.look4sat.core.domain.logbook.operatorGrids
import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.NominalFrequencies
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.CompassAccuracy
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.WavelogSyncMode
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.SharedDialog
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.MainTheme
import com.rtbishop.look4sat.core.presentation.PrimaryIconCard
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.infiniteMarquee
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsDestination(onOpenGridFinder: () -> Unit = {}) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container, context))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Station-grid prefill for the "fix it" jump from the logbook grid check (single-use,
    // mirrors the Grid Finder's "Set as LoTW station" hand-off).
    val pendingStationGrid by container.pendingLoTWStationGrid.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        nominalFrequencies = viewModel::nominalFrequencies,
        onOpenGridFinder = onOpenGridFinder,
        pendingStationGrid = pendingStationGrid.orEmpty(),
        onFixStationGrid = { grids ->
            if (grids.isNotEmpty()) container.setPendingLoTWStationGrid(grids.joinToString(","))
        },
        onClearStationGridPrefill = { container.setPendingLoTWStationGrid(null) }
    )
}

@Composable
private fun SettingsScreen(
    uiState: SettingsState,
    onAction: (SettingsAction) -> Unit,
    nominalFrequencies: suspend (String) -> NominalFrequencies? = { null },
    onOpenGridFinder: () -> Unit,
    pendingStationGrid: String = "",
    onFixStationGrid: (List<String>) -> Unit = {},
    onClearStationGridPrefill: () -> Unit = {}
) {
    var showUpdateChecker by rememberSaveable { mutableStateOf(false) }
    var showMapSettings by rememberSaveable { mutableStateOf(false) }
    var showProjectWiki by rememberSaveable { mutableStateOf(false) }
    if (showProjectWiki) {
        ProjectWikiDialog(onDismiss = { showProjectWiki = false })
    }
    if (showUpdateChecker) {
        UpdateCheckerScreen(
            currentVersion = uiState.appVersionName,
            state = uiState.updateChecker,
            onBack = { showUpdateChecker = false },
            onCheck = { onAction(SettingsAction.CheckForUpdate) },
            onDownload = { onAction(SettingsAction.DownloadUpdate) },
            onConsumeApk = { onAction(SettingsAction.ConsumeDownloadedApk) }
        )
        return
    }
    val dialogs = rememberDialogVisibility()
    var showRadioProfiles by rememberSaveable {mutableStateOf(false)}
    val diagnosticContext=LocalContext.current
    LaunchedEffect(uiState.controlDiagnosticsExportText) {
        uiState.controlDiagnosticsExportText?.let {text ->
            val intent=android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type="text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT,text)
            }
            diagnosticContext.startActivity(android.content.Intent.createChooser(intent,diagnosticContext.getString(R.string.control_diagnostics_export)))
            onAction(SettingsAction.ConsumeControlDiagnosticsExport)
        }
    }
    val pendingCustomSourcesGrant = remember { mutableStateOf<(() -> Unit)?>(null) }
    val pendingCustomSourcesDeny = remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberSettingsPermissions(
        sendAction = onAction,
        onBluetoothGranted = { dialogs.bluetooth = true },
        onNetworkGranted = { dialogs.network = true },
        onCustomSourcesPermissionGranted = {
            pendingCustomSourcesGrant.value?.invoke()
            pendingCustomSourcesGrant.value = null
            pendingCustomSourcesDeny.value = null
        },
        onCustomSourcesPermissionDenied = {
            pendingCustomSourcesDeny.value?.invoke()
            pendingCustomSourcesGrant.value = null
            pendingCustomSourcesDeny.value = null
        }
    )

    if (showMapSettings) {
        MapSettingsDialog(
            settings = uiState.otherSettings,
            onDismiss = { showMapSettings = false },
            onSave = { mapSource, tiandituKey ->
                onAction(SettingsAction.UpdateMapSettings(mapSource, tiandituKey))
                showMapSettings = false
            }
        )
    }

    // Dialogs
    if (dialogs.position) {
        PositionDialog(
            uiState.positionSettings.stationPos.latitude,
            uiState.positionSettings.stationPos.longitude,
            dismiss = { dialogs.position = false },
            save = { lat, lon -> onAction(SettingsAction.SetGeoPosition(lat, lon)) }
        )
    }
    if (dialogs.locator) {
        LocatorDialog(
            uiState.positionSettings.stationPos.qthLocator,
            dismiss = { dialogs.locator = false },
            save = { onAction(SettingsAction.SetQthPosition(it)) }
        )
    }
    if (dialogs.dataSources) {
        DataSourcesDialog(
            satelliteUrls = uiState.dataSourcesSettings.satelliteUrls,
            transceiversUrls = uiState.dataSourcesSettings.transceiversUrls,
            satelliteEnabled = uiState.dataSourcesSettings.satelliteEnabled,
            transceiversEnabled = uiState.dataSourcesSettings.transceiversEnabled,
            statusCodes = uiState.dataSourcesStatus,
            onImportTle = { permissions.launchTleImport(); dialogs.dataSources = false },
            onImportTransceivers = { permissions.launchTransceiverImport(); dialogs.dataSources = false },
            onDismiss = { dialogs.dataSources = false },
            onSave = { satUrls, txUrls, satEnabled, txEnabled ->
                val newSettings = DataSourcesSettings(
                    satelliteUrls = satUrls,
                    transceiversUrls = txUrls,
                    satelliteEnabled = satEnabled,
                    transceiversEnabled = txEnabled
                )
                if (newSettings != uiState.dataSourcesSettings) onAction(SettingsAction.UpdateDataSources(newSettings))
                onAction(SettingsAction.UpdateFromWeb)
            }
        )
    }
    if (dialogs.network) {
        NetworkOutputDialog(
            initialSettings = uiState.rcSettings,
            onDismiss = { dialogs.network = false },
            onSave = { rotState, rotAddr, rotPort, rotFmt, freqState, freqAddr, freqPort, freqFmt, freqOffsetHz ->
                onAction(
                    SettingsAction.UpdateRC(
                        uiState.rcSettings.copy(
                            rotatorState = rotState, rotatorAddress = rotAddr,
                            rotatorPort = rotPort, rotatorFormat = rotFmt,
                            frequencyState = freqState, frequencyAddress = freqAddr,
                            frequencyPort = freqPort, frequencyFormat = freqFmt,
                            frequencyOffsetHz = freqOffsetHz
                        )
                    )
                )
            }
        )
    }
    if (dialogs.bluetooth) {
        BluetoothOutputDialog(
            initialSettings = uiState.rcSettings,
            onDismiss = { dialogs.bluetooth = false },
            onSave = { rotState, rotAddr, rotFmt, freqState, freqAddr, freqFmt ->
                onAction(
                    SettingsAction.UpdateRC(
                        uiState.rcSettings.copy(
                            bluetoothRotatorState = rotState, bluetoothRotatorAddress = rotAddr,
                            bluetoothRotatorFormat = rotFmt, bluetoothFrequencyState = freqState,
                            bluetoothFrequencyAddress = freqAddr, bluetoothFrequencyFormat = freqFmt
                        )
                    )
                )
            }
        )
    }
    if (dialogs.radioControl) {
        RadioControlDialog(
            initialSettings = uiState.radioControlSettings,
            onDismiss = { dialogs.radioControl = false },
            onSave = { onAction(SettingsAction.UpdateRadioControl(it)) },
            frequencyLocked=uiState.radioTrackingState.frequencyEditingLocked,
            onOpenProfiles={dialogs.radioControl=false;showRadioProfiles=true;onAction(SettingsAction.RefreshRadioProfiles)},
            diagnosticCount=uiState.controlDiagnosticCount,
            onExportDiagnostics={onAction(SettingsAction.ExportControlDiagnostics)},
            onClearDiagnostics={onAction(SettingsAction.ClearControlDiagnostics)}
        )
    }
    if(showRadioProfiles) RadioProfilesDialog(uiState.radioProfiles,uiState.radioTrackingState,
        uiState.radioProfilesExport,onAction) {showRadioProfiles=false}
    if (dialogs.compassCalibration) {
        LaunchedEffect(Unit) { onAction(SettingsAction.StartCompassCalibration) }
        CompassCalibrationDialog(
            accuracy = uiState.compassAccuracy,
            headingDegrees = uiState.compassHeadingDegrees,
            initialOffsetDegrees = uiState.otherSettings.compassOffsetDegrees,
            onDismiss = {
                onAction(SettingsAction.StopCompassCalibration)
                dialogs.compassCalibration = false
            },
            onSave = { onAction(SettingsAction.SetCompassOffset(it)) }
        )
    }
    if (dialogs.lotw) {
        // LoTW sync failures are typed codes from the ViewModel; render them
        // through string resources so the dialog follows the system language.
        val lotwErrorMessage = uiState.lotwError?.let { error ->
            when (error) {
                LoTWError.NotConfigured -> stringResource(R.string.lotw_sync_error_not_configured)
                LoTWError.BadCredentials -> stringResource(R.string.lotw_sync_error_credentials)
                LoTWError.RateLimited -> stringResource(R.string.lotw_sync_error_rate_limited)
                LoTWError.Timeout -> stringResource(R.string.lotw_sync_error_timeout)
                is LoTWError.Network -> stringResource(R.string.lotw_sync_error_network, error.detail)
            }
        }
        LoTWDialog(
            initialSettings = uiState.lotwSettings,
            workedGridsCount = uiState.workedGridsCount,
            isSyncing = uiState.lotwSyncing,
            syncMode = uiState.lotwSyncMode,
            progress = uiState.lotwProgress,
            message = lotwErrorMessage,
            dismiss = { dialogs.lotw = false },
            onCancelSync = { onAction(SettingsAction.CancelLoTWSync); dialogs.lotw = false },
            onSave = { onAction(SettingsAction.UpdateLoTW(it)) },
            onSyncFull = { onAction(SettingsAction.SyncLoTWGrids(it, LoTWSyncMode.Full)) },
            onSyncIncremental = { onAction(SettingsAction.SyncLoTWGrids(it, LoTWSyncMode.Incremental)) }
        )
    }
    if (dialogs.logbook) {
        val stationFilter = uiState.logbookStationFilter
        LogbookDialog(
            records = uiState.logbookRecords.filter { stationFilter == null || it.wavelogStation == stationFilter },
            satelliteCandidates = uiState.satelliteCatalog,
            newRecord = {
                // Blank draft: minute-truncated "now" (the radar Log tab's granularity), the own
                // callsign and the station's first grid, so the record is uploadable as it stands.
                // Certificate / LoTW station location first, the selected Wavelog station profile
                // as the fallback — a Wavelog-only setup has neither of the LoTW pair.
                val now = System.currentTimeMillis() / 60_000L * 60_000L
                QsoRecord(
                    startUtcMillis = now,
                    endUtcMillis = now,
                    theirCallsign = "",
                    myCallsign = operatorCallsign(
                        uiState.lotwCertificate?.callsign.orEmpty(),
                        uiState.wavelogUploadSettings.stationCallsign
                    ),
                    myGrid = operatorGrids(
                        uiState.lotwStation?.grid.orEmpty(),
                        uiState.wavelogUploadSettings.stationGrid
                    ).firstOrNull().orEmpty(),
                    status = QsoStatus.COMPLETE
                )
            },
            nominalFrequencies = nominalFrequencies,
            uploadBusy = uiState.logbookUploadBusy,
            uploadMessage = uiState.logbookUploadMessage,
            preview = uiState.logbookPreview,
            positionWarning = uiState.logbookPositionWarning,
            onDismiss = {
                // Leaving the logbook resets the resubmit selection: re-entering starts
                // clean — the operator long-presses again to pick records.
                onAction(SettingsAction.ExitLogbookSelection)
                dialogs.logbook = false
            },
            onDelete = { onAction(SettingsAction.DeleteLogbookRecord(it)) },
            onEdit = { onAction(SettingsAction.UpdateLogbookRecord(it)) },
            onUpload = { onAction(SettingsAction.PrepareLogbookUpload) },
            onConfirmUpload = { onAction(SettingsAction.ConfirmLogbookUpload) },
            onDismissPreview = { onAction(SettingsAction.DismissLogbookPreview) },
            onDismissMessage = { onAction(SettingsAction.ClearLogbookMessage) },
            onRewriteCallsign = { onAction(SettingsAction.RewriteLogbookCallsigns) },
            onSwitchCertificate = {
                // The other callsign's certificate lives in the LoTW upload settings: leave the
                // logbook and open that dialog.
                onAction(SettingsAction.DismissLogbookPreview)
                onAction(SettingsAction.ExitLogbookSelection)
                dialogs.logbook = false
                dialogs.lotwUpload = true
            },
            onIgnorePositionWarning = { onAction(SettingsAction.IgnoreLogbookPositionWarning) },
            onFixGrid = { grids ->
                onAction(SettingsAction.AbandonLogbookForGridFix)
                onAction(SettingsAction.ExitLogbookSelection)
                dialogs.logbook = false
                onFixStationGrid(grids)
                dialogs.lotwUpload = true
            },
            selectionMode = uiState.logbookSelectionMode,
            selectedIds = uiState.logbookSelectedIds,
            onStartSelection = { onAction(SettingsAction.StartLogbookSelection(it)) },
            onToggleSelection = { onAction(SettingsAction.ToggleLogbookSelection(it)) },
            onExitSelection = { onAction(SettingsAction.ExitLogbookSelection) },
            onResubmitSelected = { onAction(SettingsAction.ResubmitSelectedLogbook) },
            wavelogPreview = uiState.logbookWavelogPreview,
            wavelogMode = uiState.wavelogUploadSettings.isReady,
            stationOptions = if (uiState.wavelogUploadSettings.isReady) uiState.wavelogUploadStations else emptyList(),
            stationFilter = uiState.logbookStationFilter,
            onStationFilterChange = { onAction(SettingsAction.SetLogbookStationFilter(it)) }
        )
    }
    if (dialogs.lotwUpload) {
        LoTWUploadConfigDialog(
            certificate = uiState.lotwCertificate,
            station = uiState.lotwStation,
            stationMeta = uiState.lotwStationMeta,
            busy = uiState.lotwUploadBusy,
            error = uiState.lotwUploadError,
            errorDetail = uiState.lotwUploadErrorDetail,
            initialGrid = pendingStationGrid,
            onDismiss = {
                dialogs.lotwUpload = false
                onClearStationGridPrefill()
            },
            onImport = { bytes, password -> onAction(SettingsAction.ImportLoTWCertificate(bytes, password)) },
            onPreview = { bytes, password -> onAction(SettingsAction.PreviewLoTWCertificate(bytes, password)) },
            onRemove = { onAction(SettingsAction.RemoveLoTWCertificate) },
            onSaveStation = { onAction(SettingsAction.SaveLoTWStation(it)) }
        )
    }

    if (dialogs.wavelogUpload) {
        WavelogUploadDialog(
            initialSettings = uiState.wavelogUploadSettings,
            stations = uiState.wavelogUploadStations,
            rights = uiState.wavelogUploadRights,
            probeBusy = uiState.wavelogUploadProbeBusy,
            isSyncing = uiState.wavelogSyncing,
            lastSyncEpochMs = uiState.wavelogLastSyncEpochMs,
            message = uiState.wavelogUploadMessage,
            dismiss = { dialogs.wavelogUpload = false },
            onSave = { onAction(SettingsAction.UpdateWavelogUpload(it)) },
            onFetchStations = { url, apiKey -> onAction(SettingsAction.FetchWavelogUploadStations(url, apiKey)) },
            onSelectStation = { onAction(SettingsAction.SelectWavelogUploadStation(it)) },
            onSyncIncremental = { onAction(SettingsAction.SyncWavelog(WavelogSyncMode.Incremental)) },
            onSyncFull = { onAction(SettingsAction.SyncWavelog(WavelogSyncMode.Full)) },
            onClear = { onAction(SettingsAction.ClearWavelogConfig) }
        )
    }
    if (uiState.wavelogSwitchWarning) {
        WavelogSwitchWarningDialog(
            onConfirm = { onAction(SettingsAction.ConfirmWavelogSwitch) },
            onCancel = { onAction(SettingsAction.CancelWavelogSwitch) }
        )
    }

    // URLs for top bar
    val uriHandler = LocalUriHandler.current
    val upstreamTitle = stringResource(R.string.prefs_upstream_title)
    val upstreamUrl = stringResource(R.string.prefs_upstream_url)
    val gitHubTitle = stringResource(R.string.prefs_github_title)
    val gitHubUrl = stringResource(R.string.prefs_github_url)
    val licenseUrl = stringResource(R.string.prefs_license_url)
    val privacyUrl = stringResource(R.string.prefs_privacy_url)
    val safeOpenUri: (String) -> Unit = { url ->
        try { uriHandler.openUri(url) } catch (_: Exception) {}
    }

    ScreenColumn(
        topBar = { isVerticalLayout ->
            if (isVerticalLayout) {
                TopBar {
                    TopCard(
                        onClick = { showUpdateChecker = true },
                        version = uiState.appVersionName,
                        modifier = Modifier.weight(1f)
                    )
                }
                TopBar {
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotCard(
                            onClick = { safeOpenUri(upstreamUrl) },
                            resId = R.drawable.ic_github,
                            text = upstreamTitle,
                            modifier = Modifier.weight(1f)
                        )
                        BotCard(
                            onClick = { safeOpenUri(gitHubUrl) },
                            resId = R.drawable.ic_github,
                            text = gitHubTitle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    IconCard(action = { safeOpenUri(licenseUrl) }, resId = R.drawable.ic_license)
                    IconCard(action = { safeOpenUri(privacyUrl) }, resId = R.drawable.ic_policy)
                }
            } else {
                TopBar {
                    TopCard(
                        onClick = { showUpdateChecker = true },
                        version = uiState.appVersionName,
                        modifier = Modifier.weight(1f)
                    )
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotCard(
                            onClick = { safeOpenUri(upstreamUrl) },
                            resId = R.drawable.ic_github,
                            text = upstreamTitle,
                            modifier = Modifier.weight(1f)
                        )
                        BotCard(
                            onClick = { safeOpenUri(gitHubUrl) },
                            resId = R.drawable.ic_github,
                            text = gitHubTitle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    IconCard(action = { safeOpenUri(licenseUrl) }, resId = R.drawable.ic_license)
                    IconCard(action = { safeOpenUri(privacyUrl) }, resId = R.drawable.ic_policy)
                }
            }
        }
    ) { isVerticalLayout ->
        val wavelogMode = uiState.wavelogUploadSettings.isReady
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isVerticalLayout) 1 else 2),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clip(MaterialTheme.shapes.medium)
        ) {
            item {
                LocationCard(
                    settings = uiState.positionSettings,
                    setGpsPos = permissions.launchLocation,
                    showPosDialog = { dialogs.position = true },
                    showLocDialog = { dialogs.locator = true },
                    dismissPosMessage = { onAction(SettingsAction.DismissPosMessages) },
                    openGridFinder = onOpenGridFinder,
                    onAction = onAction
                )
            }
            item {
                DataCard(
                    settings = uiState.dataSettings,
                    updateFromWeb = { onAction(SettingsAction.UpdateFromWeb) },
                    clearAllData = { onAction(SettingsAction.ClearAllData) },
                    showDataSourcesDialog = { dialogs.dataSources = true }
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutputCard(
                    onNetworkClick = permissions.launchNetwork,
                    onBluetoothClick = permissions.launchBluetooth,
                    onRadioControlClick = { dialogs.radioControl = true }
                )
            }
            item {
                LogbookCard(
                    recordCount = uiState.logbookRecords.size,
                    showLogbookDialog = { dialogs.logbook = true }
                )
            }
            item {
                LoTWUploadCard(
                    hasCertificate = uiState.lotwCertificate != null,
                    stationGrid = uiState.lotwStation?.grid.orEmpty(),
                    enabled = !wavelogMode,
                    showUploadConfigDialog = { onAction(SettingsAction.LoadLoTWUploadStatus); dialogs.lotwUpload = true }
                )
            }
            item {
                LoTWCard(
                    settings = uiState.lotwSettings,
                    workedGridsCount = uiState.workedGridsCount,
                    lastSyncEpochMs = uiState.lotwLastSyncEpochMs,
                    enabled = !wavelogMode,
                    showLoTWDialog = { dialogs.lotw = true }
                )
            }
            item {
                WavelogUploadCard(
                    settings = uiState.wavelogUploadSettings,
                    lastSyncEpochMs = uiState.wavelogLastSyncEpochMs,
                    showDialog = { dialogs.wavelogUpload = true }
                )
            }
            item {
                OtherCard(
                    settings = uiState.otherSettings,
                    onCompassCalibration = { dialogs.compassCalibration = true },
                    loTWEnabled = !wavelogMode,
                    onAction = onAction
                )
            }
            item { MapSettingsCard(onClick = { showMapSettings = true }) }
            item { ProjectWikiCard(onClick = { showProjectWiki = true }) }
            item { CardCredits() }
            item(span = { GridItemSpan(maxLineSpan) }) {
                CardButton(
                    onClick = { showUpdateChecker = true },
                    text = stringResource(R.string.update_check_button),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LocationCardPreview() = MainTheme {
    val stationPos = GeoPos(0.0, 0.0, 0.0, "IO91vl", 0L)
    val settings = PositionSettings(true, stationPos, 0)
    LocationCard(settings = settings, setGpsPos = {}, showPosDialog = {}, {}, {}, {}) {}
}

@Composable
private fun LocationCard(
    settings: PositionSettings,
    setGpsPos: () -> Unit,
    showPosDialog: () -> Unit,
    showLocDialog: () -> Unit,
    dismissPosMessage: () -> Unit,
    openGridFinder: () -> Unit,
    onAction: (SettingsAction) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.prefs_loc_title),
                    color = MaterialTheme.colorScheme.primary
                )
                UpdateIndicator(isUpdating = settings.isUpdating, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = formatUpdateTime(updateTime = settings.stationPos.timestamp))
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Lat: ${settings.stationPos.latitude}°")
                Text(text = "Lon: ${settings.stationPos.longitude}°")
                Text(text = "Qth: ${settings.stationPos.qthLocator}")
            }
            Spacer(modifier = Modifier.height(1.dp))
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                CardButton(
                    onClick = setGpsPos,
                    text = stringResource(id = R.string.prefs_loc_gps_title),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                CardButton(
                    onClick = showPosDialog,
                    text = stringResource(id = R.string.prefs_loc_input_title),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                CardButton(
                    onClick = showLocDialog,
                    text = stringResource(id = R.string.prefs_loc_qth_title),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            CardButton(
                onClick = openGridFinder,
                text = stringResource(id = R.string.gridfinder_title),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (settings.messageResId != 0) {
        val errorString = stringResource(id = settings.messageResId)
        LaunchedEffect(key1 = settings.messageResId) {
            onAction(SettingsAction.ShowToast(errorString))
            dismissPosMessage()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DataCardPreview() = MainTheme {
    val settings = DataSettings(true, 5000, 2500, 0L)
    DataCard(settings = settings, updateFromWeb = {}, clearAllData = {}, showDataSourcesDialog = {})
}

@Composable
private fun DataCard(
    settings: DataSettings,
    updateFromWeb: () -> Unit,
    clearAllData: () -> Unit,
    showDataSourcesDialog: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.prefs_data_title),
                    color = MaterialTheme.colorScheme.primary
                )
                UpdateIndicator(isUpdating = settings.isUpdating, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = formatUpdateTime(updateTime = settings.timestamp))
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.prefs_data_entries, settings.entriesTotal))
                Text(text = stringResource(R.string.prefs_data_radios, settings.radiosTotal))
            }
            Spacer(modifier = Modifier.height(1.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = updateFromWeb,
                    text = stringResource(id = R.string.prefs_data_update),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = showDataSourcesDialog,
                    text = stringResource(id = R.string.prefs_data_import),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = clearAllData,
                    text = stringResource(id = R.string.prefs_data_clear),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OutputCardPreview() = MainTheme { OutputCard({}, {}, {}) }

@Composable
private fun OutputCard(
    onNetworkClick: () -> Unit,
    onBluetoothClick: () -> Unit,
    onRadioControlClick: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(id = R.string.prefs_data_output_title),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = onNetworkClick,
                    text = stringResource(id = R.string.prefs_net_output),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = onBluetoothClick,
                    text = stringResource(id = R.string.prefs_bt_output),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = onRadioControlClick,
                    text = stringResource(id = R.string.prefs_cat_output),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OtherCardPreview() = MainTheme {
    val values = OtherSettings(
        stateOfAutoUpdate = true,
        stateOfSensors = true,
        stateOfSweep = true,
        stateOfUtc = false,
        stateOfLightTheme = false,
        stateOfNightMode = false,
        stateOfMapGrid = false,
        shouldSeeWarning = false,
        shouldSeeWhatsNew = false
    )
    OtherCard(settings = values, onCompassCalibration = {}, onAction = {})
}

@Composable
private fun MapSettingsCard(onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = stringResource(R.string.prefs_map_title),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            CardButton(
                onClick = onClick,
                text = stringResource(R.string.prefs_map_configure),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun OtherCard(
    settings: OtherSettings,
    onCompassCalibration: () -> Unit,
    loTWEnabled: Boolean = true,
    onAction: (SettingsAction) -> Unit
) {
    // Let the settings switches determine their own height. A fixed 420 dp
    // panel wastes an entire landscape viewport and can clip larger text.
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(id = R.string.prefs_other_title),
                color = MaterialTheme.colorScheme.primary
            )
            SwitchRow(R.string.prefs_other_switch_utc, settings.stateOfUtc) {
                onAction(SettingsAction.ToggleUtc(it))
            }
            SwitchRow(R.string.prefs_other_switch_update, settings.stateOfAutoUpdate) {
                onAction(SettingsAction.ToggleUpdate(it))
            }
            SwitchRow(
                R.string.prefs_other_switch_lotw_sync,
                settings.stateOfAutoLotwSync,
                enabled = loTWEnabled
            ) {
                onAction(SettingsAction.ToggleAutoLotwSync(it))
            }
            SwitchRow(R.string.prefs_other_switch_sweep, settings.stateOfSweep) {
                onAction(SettingsAction.ToggleSweep(it))
            }
            SwitchRow(R.string.prefs_other_switch_sensors, settings.stateOfSensors) {
                onAction(SettingsAction.ToggleSensor(it))
            }
            CardButton(
                onClick = onCompassCalibration,
                text = stringResource(R.string.prefs_compass_calibration_button),
                modifier = Modifier.fillMaxWidth()
            )
            // Appearance: light theme + the red night filter (mutually exclusive —
            // the night filter is disabled while the light theme is on).
            SwitchRow(R.string.prefs_other_switch_light_theme, settings.stateOfLightTheme) {
                onAction(SettingsAction.ToggleLightTheme(it))
            }
            SwitchRow(
                R.string.prefs_other_switch_night_mode,
                settings.stateOfNightMode,
                enabled = !settings.stateOfLightTheme
            ) {
                onAction(SettingsAction.ToggleNightMode(it))
            }
        }
    }
}

/**
 * 指南针校准对话框: 8 字动作提升精度, 实时显示校正后航向, 可手动输入偏置(±180°).
 */
@Composable
private fun CompassCalibrationDialog(
    accuracy: CompassAccuracy,
    headingDegrees: Float,
    initialOffsetDegrees: Float,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit
) {
    var offsetText by rememberSaveable(initialOffsetDegrees) {
        mutableStateOf(initialOffsetDegrees.toString())
    }
    val parsedOffset = offsetText.replace(',', '.').toFloatOrNull()?.takeIf { it in -180f..180f }
    val statusRes = when (accuracy) {
        CompassAccuracy.HIGH -> R.string.prefs_compass_accuracy_high
        CompassAccuracy.MEDIUM -> R.string.prefs_compass_accuracy_medium
        CompassAccuracy.LOW -> R.string.prefs_compass_accuracy_low
        CompassAccuracy.UNRELIABLE -> R.string.prefs_compass_accuracy_unreliable
        CompassAccuracy.UNAVAILABLE -> R.string.prefs_compass_accuracy_unavailable
    }
    val progress = when (accuracy) {
        CompassAccuracy.HIGH -> 1f
        CompassAccuracy.MEDIUM -> 0.66f
        CompassAccuracy.LOW -> 0.33f
        CompassAccuracy.UNRELIABLE, CompassAccuracy.UNAVAILABLE -> 0f
    }
    SharedDialog(
        title = stringResource(R.string.prefs_compass_calibration_title),
        onDismissRequest = onDismiss,
        onCancel = onDismiss,
        onAccept = {
            parsedOffset?.let {
                onSave(it)
                onDismiss()
            }
        }
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = padding)
        ) {
            Text(
                text = "∞",
                fontSize = 92.sp,
                color = MaterialTheme.colorScheme.primary,
                lineHeight = 92.sp
            )
            Text(
                text = stringResource(R.string.prefs_compass_calibration_instruction),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.prefs_compass_accuracy, stringResource(statusRes)),
                fontWeight = FontWeight.Medium,
                color = if (accuracy == CompassAccuracy.HIGH) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.prefs_compass_heading, headingDegrees),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
            OutlinedTextField(
                value = offsetText,
                onValueChange = { offsetText = it },
                label = { Text(stringResource(R.string.prefs_compass_offset)) },
                supportingText = { Text(stringResource(R.string.prefs_compass_offset_support)) },
                isError = parsedOffset == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            CardButton(
                onClick = { offsetText = "0" },
                text = stringResource(R.string.prefs_compass_offset_reset),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SwitchRow(
    labelResId: Int,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = stringResource(id = labelResId))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LoTWCard(
    settings: com.rtbishop.look4sat.core.domain.model.LoTWSettings,
    workedGridsCount: Int,
    lastSyncEpochMs: Long,
    enabled: Boolean = true,
    showLoTWDialog: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .alpha(if (enabled) 1f else 0.5f)
        ) {
            Text(
                text = stringResource(id = R.string.prefs_lotw_title),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (settings.isConfigured) {
                    stringResource(R.string.prefs_lotw_configured, workedGridsCount)
                } else {
                    stringResource(R.string.prefs_lotw_not_configured)
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
            // Last successful sync, shown exactly like the ephemeris update time.
            if (lastSyncEpochMs != 0L) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatUpdateTime(updateTime = lastSyncEpochMs),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!enabled) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.prefs_lotw_disabled_wavelog),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            CardButton(
                onClick = showLoTWDialog,
                text = stringResource(id = R.string.prefs_wavelog_configure),
                isEnabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WavelogUploadCard(
    settings: com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings,
    lastSyncEpochMs: Long,
    showDialog: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(id = R.string.prefs_wavelog_upload_title),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.prefs_wavelog_exclusive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (settings.isReady) {
                    stringResource(
                        R.string.prefs_wavelog_upload_configured,
                        listOf(settings.stationName, settings.stationCallsign, settings.stationGrid)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                    )
                } else {
                    stringResource(R.string.prefs_wavelog_upload_not_configured)
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
            if (lastSyncEpochMs != 0L) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatUpdateTime(updateTime = lastSyncEpochMs),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            CardButton(
                onClick = showDialog,
                text = stringResource(id = R.string.prefs_wavelog_configure),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun formatUpdateTime(updateTime: Long): String {
    val timePattern = stringResource(id = R.string.prefs_updated_time)
    val placeholder = stringResource(id = R.string.pass_time_placeholder)
    val updateDate = remember(updateTime) {
        if (updateTime != 0L) {
            SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(updateTime))
        } else {
            placeholder
        }
    }
    return stringResource(id = R.string.prefs_updated_title, updateDate)
}

@Composable
private fun UpdateIndicator(isUpdating: Boolean, modifier: Modifier = Modifier) = if (isUpdating) {
    LinearProgressIndicator(modifier = modifier.padding(start = 6.dp))
} else {
    LinearProgressIndicator(
        progress = { 0f },
        drawStopIndicator = {},
        modifier = modifier.padding(start = 6.dp)
    )
}

@Composable
private fun ProjectWikiCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable { onClick() }) {
            Spacer(Modifier)
            Icon(painter = painterResource(id = R.drawable.ic_policy), contentDescription = null)
            Text(
                text = stringResource(R.string.prefs_project_wiki),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CardCreditsPreview() = MainTheme { CardCredits() }

@Composable
private fun CardCredits(modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(id = R.string.prefs_outro_title),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(id = R.string.prefs_outro_notice),
                fontSize = 12.sp
            )
            HorizontalDivider()
            Text(
                text = stringResource(id = R.string.prefs_outro_thanks),
                fontSize = 12.sp
            )
            Text(
                text = stringResource(id = R.string.prefs_outro_license),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun TopCard(onClick: () -> Unit, modifier: Modifier = Modifier, version: String) {
    ElevatedCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(48.dp)
                .clickable { onClick() }) {
            Spacer(Modifier)
            Icon(
                painter = painterResource(id = R.drawable.ic_satellites),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.prefs_app_title, version),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
                    .infiniteMarquee()
            )
        }
    }
}

@Composable
private fun BotCard(onClick: () -> Unit, resId: Int, text: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(48.dp)
                .clickable { onClick() }) {
            Spacer(Modifier)
            Icon(painter = painterResource(id = resId), contentDescription = null)
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
                    .infiniteMarquee()
            )
        }
    }
}

// region Dialog visibility state holder

@Stable
private class DialogVisibility {
    var position by mutableStateOf(false)
    var locator by mutableStateOf(false)
    var dataSources by mutableStateOf(false)
    var network by mutableStateOf(false)
    var bluetooth by mutableStateOf(false)
    var radioControl by mutableStateOf(false)
    var wavelog by mutableStateOf(false)
    var lotw by mutableStateOf(false)
    var compassCalibration by mutableStateOf(false)
    var logbook by mutableStateOf(false)
    var lotwUpload by mutableStateOf(false)
    var wavelogUpload by mutableStateOf(false)
}

@Composable
private fun rememberDialogVisibility(): DialogVisibility {
    return rememberSaveable(saver = run {
        androidx.compose.runtime.saveable.Saver(
            save = {
                listOf(it.position, it.locator, it.dataSources, it.network, it.bluetooth, it.radioControl, it.wavelog, it.lotw, it.compassCalibration, it.logbook, it.lotwUpload, it.wavelogUpload)
            },
            restore = {
                DialogVisibility().apply {
                    position = it[0]; locator = it[1]; dataSources = it[2]
                    network = it[3]; bluetooth = it[4]; radioControl = it[5]; wavelog = it[6]
                    lotw = it.getOrElse(7) { false }
                    compassCalibration = it.getOrElse(8) { false }
                    logbook = it.getOrElse(9) { false }
                    lotwUpload = it.getOrElse(10) { false }
                    wavelogUpload = it.getOrElse(11) { false }
                }
            }
        )
    }) { DialogVisibility() }
}

// endregion

// region Permission launchers holder

@Stable
private class SettingsPermissions(
    val launchLocation: () -> Unit,
    val launchTleImport: () -> Unit,
    val launchTransceiverImport: () -> Unit,
    val launchBluetooth: () -> Unit,
    val launchNetwork: () -> Unit,
    val launchCustomSourcesPermission: () -> Unit
)

@Composable
private fun rememberSettingsPermissions(
    sendAction: (SettingsAction) -> Unit,
    onBluetoothGranted: () -> Unit,
    onNetworkGranted: () -> Unit,
    onCustomSourcesPermissionGranted: () -> Unit,
    onCustomSourcesPermissionDenied: () -> Unit
): SettingsPermissions {
    val locationError = stringResource(R.string.prefs_loc_gps_error)
    val locationRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) sendAction(SettingsAction.SetGpsPosition)
        else sendAction(SettingsAction.ShowToast(locationError))
    }

    val satellitesImportError = stringResource(R.string.prefs_data_import_satellites_error)
    val transceiversImportError = stringResource(R.string.prefs_data_import_transceivers_error)

    val tleRequest = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendAction(SettingsAction.UpdateTLEFromFile(it.toString(), satellitesImportError)) }
    }

    val transceiversRequest = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendAction(SettingsAction.UpdateTransceiversFromFile(it.toString(), transceiversImportError)) }
    }

    val bluetoothError = stringResource(R.string.prefs_bt_perm_error)
    val bluetoothPerm = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        Manifest.permission.BLUETOOTH else Manifest.permission.BLUETOOTH_CONNECT
    val bluetoothRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onBluetoothGranted()
        else sendAction(SettingsAction.ShowToast(bluetoothError))
    }

    val networkError = stringResource(R.string.prefs_net_perm_error)
    val networkRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onNetworkGranted()
        else sendAction(SettingsAction.ShowToast(networkError))
    }

    val customSourcesRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onCustomSourcesPermissionGranted()
        } else {
            onCustomSourcesPermissionDenied()
            sendAction(SettingsAction.ShowToast(networkError))
        }
    }

    return remember {
        SettingsPermissions(
            launchLocation = {
                locationRequest.launch(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                )
            },
            launchTleImport = { tleRequest.launch("*/*") },
            launchTransceiverImport = { transceiversRequest.launch("*/*") },
            launchBluetooth = { bluetoothRequest.launch(bluetoothPerm) },
            launchNetwork = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    networkRequest.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    onNetworkGranted()
                }
            },
            launchCustomSourcesPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    customSourcesRequest.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    onCustomSourcesPermissionGranted()
                }
            }
        )
    }
}

// endregion
