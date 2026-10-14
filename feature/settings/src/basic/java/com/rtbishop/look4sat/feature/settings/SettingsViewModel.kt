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

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.repository.IDatabaseRepo
import com.rtbishop.look4sat.core.domain.model.NominalFrequencies
import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISensorsRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.IUpdateRepository
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.applyLoTWGridResult
import com.rtbishop.look4sat.core.domain.repository.lotwCursorApi
import com.rtbishop.look4sat.core.domain.repository.lotwCursorEpochMs
import com.rtbishop.look4sat.core.domain.repository.resolveLoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.positionWarning
import com.rtbishop.look4sat.core.domain.repository.IWavelogUploadRepository
import com.rtbishop.look4sat.core.domain.repository.IWavelogSyncRepository
import com.rtbishop.look4sat.core.domain.repository.WavelogSyncFetch
import com.rtbishop.look4sat.core.domain.repository.WavelogSyncMode
import com.rtbishop.look4sat.core.domain.repository.applyWavelogSyncResult
import com.rtbishop.look4sat.core.domain.repository.resolveWavelogSyncMode
import com.rtbishop.look4sat.core.domain.repository.shouldPromoteWavelogFullSync
import com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadOutcome
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadPreview
import com.rtbishop.look4sat.core.domain.usecase.IShowToast
import com.rtbishop.look4sat.core.domain.utility.VersionComparator
import com.rtbishop.look4sat.core.domain.logbook.needsPreviewForConflicts
import com.rtbishop.look4sat.core.domain.logbook.resubmitCandidates
import com.rtbishop.look4sat.core.domain.logbook.toConfirmedRecord
import com.rtbishop.look4sat.core.domain.logbook.unavailableUploadSummary
import com.rtbishop.look4sat.core.domain.logbook.wavelogConfirmedSegment
import com.rtbishop.look4sat.core.domain.logbook.wavelogIdleSegment
import com.rtbishop.look4sat.core.domain.logbook.wavelogTransportFailureSegment
import com.rtbishop.look4sat.core.domain.logbook.wavelogUploadCandidates
import com.rtbishop.look4sat.core.presentation.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import com.rtbishop.look4sat.core.domain.model.ReleaseEdition

class SettingsViewModel(
    private val databaseRepo: IDatabaseRepo,
    private val settingsRepo: ISettingsRepo,
    private val updateRepo: IUpdateRepository,
    private val installedEdition: ReleaseEdition,
    private val wavelogSyncRepository: IWavelogSyncRepository,
    private val wavelogUploadRepository: IWavelogUploadRepository,
    private val lotwRepo: com.rtbishop.look4sat.core.domain.repository.ILoTWRepository,
    private val qsoRepository: com.rtbishop.look4sat.core.domain.logbook.IQsoRepository,
    private val lotwUploadRepository: com.rtbishop.look4sat.core.domain.repository.ILoTWUploadRepository,
    private val satelliteRepo: ISatelliteRepo,
    private val sensorsRepo: ISensorsRepo,
    private val apkFile: File,
    private val showToast: IShowToast
) : ViewModel() {

    private val defaultPosSettings = PositionSettings(false, settingsRepo.stationPosition.value, 0)
    private val defaultDataSettings = DataSettings(false, 0, 0, 0L)
    /** In-flight LoTW sync job, cancelled by CancelLoTWSync. */
    private var lotwSyncJob: kotlinx.coroutines.Job? = null
    /** 指南针校准: 传感器原始方位(未加磁偏角/偏置), 校准对话框实时显示校正后航向. */
    private var rawCompassHeading = sensorsRepo.sensorData.value.first
    private val _uiState = MutableStateFlow(
        SettingsState(
            appVersionName = settingsRepo.appVersionName,
            positionSettings = defaultPosSettings,
            dataSettings = defaultDataSettings,
            otherSettings = settingsRepo.otherSettings.value,
            rcSettings = settingsRepo.rcSettings.value,
            radioControlSettings = settingsRepo.radioControlSettings.value,
            dataSourcesSettings = settingsRepo.dataSourcesSettings.value,
            dataSourcesStatus = settingsRepo.dataSourcesStatus.value,
            updateChecker = UpdateCheckerState(installedEdition = installedEdition),
            wavelogUploadSettings = settingsRepo.wavelogUploadSettings.value,
            wavelogUploadStations = settingsRepo.getWavelogStations(),
            wavelogLastSyncEpochMs = settingsRepo.getLastWavelogSyncEpochMs(),
            workedGridsCount = settingsRepo.getWorkedGrids().size,
            compassAccuracy = sensorsRepo.compassAccuracy.value,
            compassHeadingDegrees = correctedCompassHeading(),
            lotwLastSyncEpochMs = lotwCursorEpochMs(settingsRepo.getLastLotwSyncDate())
        )
    )

    val uiState: StateFlow<SettingsState> = _uiState

    init {
        viewModelScope.launch {
            settingsRepo.stationPosition.collect { geoPos ->
                _uiState.update {
                    it.copy(
                        positionSettings = it.positionSettings.copy(isUpdating = false, stationPos = geoPos),
                        compassHeadingDegrees = correctedCompassHeading()
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.databaseState.collect { state ->
                _uiState.update {
                    it.copy(
                        dataSettings = it.dataSettings.copy(
                            isUpdating = false,
                            entriesTotal = state.numberOfSatellites,
                            radiosTotal = state.numberOfRadios,
                            timestamp = state.updateTimestamp
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.rcSettings.collect { settings ->
                _uiState.update { it.copy(rcSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepo.otherSettings.collect { settings ->
                _uiState.update {
                    it.copy(otherSettings = settings, compassHeadingDegrees = correctedCompassHeading())
                }
            }
        }
        viewModelScope.launch {
            sensorsRepo.compassAccuracy.collect { accuracy ->
                _uiState.update { it.copy(compassAccuracy = accuracy) }
            }
        }
        viewModelScope.launch {
            sensorsRepo.sensorData.collect { orientation ->
                rawCompassHeading = orientation.first
                _uiState.update { it.copy(compassHeadingDegrees = correctedCompassHeading()) }
            }
        }
        viewModelScope.launch {
            settingsRepo.dataSourcesSettings.collect { settings ->
                _uiState.update { it.copy(dataSourcesSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepo.dataSourcesStatus.collect { status ->
                _uiState.update { it.copy(dataSourcesStatus = status) }
            }
        }
        viewModelScope.launch {
            settingsRepo.radioControlSettings.collect { settings ->
                _uiState.update { it.copy(radioControlSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepo.wavelogUploadSettings.collect { settings ->
                _uiState.update { it.copy(wavelogUploadSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepo.lotwSettings.collect { settings ->
                _uiState.update { it.copy(lotwSettings = settings) }
            }
        }
        viewModelScope.launch {
            qsoRepository.records.collect { records ->
                _uiState.update { it.copy(logbookRecords = records) }
            }
        }
        // ARRL satellite names for the logbook edit dialog's satellite picker.
        viewModelScope.launch {
            val catalog = runCatching { lotwUploadRepository.satelliteCatalog() }.getOrDefault(emptyList())
            _uiState.update { it.copy(satelliteCatalog = catalog) }
        }
        // Load the LoTW upload certificate + station once at startup so the
        // settings card reflects the real state on first frame (previously it
        // stayed "not imported" until the config dialog was opened).
        loadLoTWUploadStatus()
    }


    fun onAction(action: SettingsAction) {
        when (action) {
            // Position
            SettingsAction.SetGpsPosition -> setGpsPosition()
            is SettingsAction.SetGeoPosition -> setGeoPosition(action.latitude, action.longitude)
            is SettingsAction.SetQthPosition -> setQthPosition(action.locator)
            SettingsAction.DismissPosMessages -> dismissPosMessage()
            // Data
            SettingsAction.UpdateFromWeb -> runDataUpdate { databaseRepo.updateFromRemote() }
            is SettingsAction.UpdateTLEFromFile -> runManualImport(action.invalidFileMessage) {
                databaseRepo.updateTLEFromFile(action.uri)
            }
            is SettingsAction.UpdateTransceiversFromFile -> runManualImport(action.invalidFileMessage) {
                databaseRepo.updateTransceiversFromFile(action.uri)
            }
            SettingsAction.ClearAllData -> viewModelScope.launch { databaseRepo.clearAllData() }
            // Toggles
            is SettingsAction.ToggleUtc -> settingsRepo.updateOtherSettings { it.copy(stateOfUtc = action.value) }
            is SettingsAction.ToggleUpdate -> settingsRepo.updateOtherSettings { it.copy(stateOfAutoUpdate = action.value) }
            is SettingsAction.ToggleAutoLotwSync -> settingsRepo.updateOtherSettings { it.copy(stateOfAutoLotwSync = action.value) }
            is SettingsAction.ToggleSweep -> settingsRepo.updateOtherSettings { it.copy(stateOfSweep = action.value) }
            is SettingsAction.ToggleSensor -> settingsRepo.updateOtherSettings { it.copy(stateOfSensors = action.value) }
            SettingsAction.StartCompassCalibration -> sensorsRepo.enableSensor()
            SettingsAction.StopCompassCalibration -> sensorsRepo.disableSensor()
            is SettingsAction.SetCompassOffset -> settingsRepo.updateOtherSettings {
                it.copy(compassOffsetDegrees = action.degrees.coerceIn(-180f, 180f))
            }
            is SettingsAction.ToggleLightTheme -> settingsRepo.updateOtherSettings {
                // The red night filter is a dark-screen aid; turning the light theme on
                // switches it off so the UI isn't red-on-white. Turning the light theme
                // off leaves the filter as the user set it (off).
                it.copy(
                    stateOfLightTheme = action.value,
                    stateOfNightMode = if (action.value) false else it.stateOfNightMode
                )
            }
            is SettingsAction.ToggleNightMode -> settingsRepo.updateOtherSettings { it.copy(stateOfNightMode = action.value) }
            is SettingsAction.UpdateMapSettings -> settingsRepo.updateOtherSettings {
                it.copy(mapSource = action.mapSource, tiandituKey = action.tiandituKey)
            }
            // Remote control & data sources
            is SettingsAction.UpdateRC -> settingsRepo.updateRCSettings(action.settings)
            is SettingsAction.UpdateRadioControl -> settingsRepo.updateRadioControlSettings(action.settings)
            is SettingsAction.UpdateDataSources -> settingsRepo.updateDataSourcesSettings(action.settings)
            // Wavelog configuration (single block; LoTW and Wavelog are mutually exclusive)
            is SettingsAction.UpdateWavelogUpload -> applyWavelogUploadSettings(action.settings)
            is SettingsAction.FetchWavelogUploadStations -> fetchWavelogUploadStations(action.url, action.apiKey)
            is SettingsAction.SelectWavelogUploadStation -> applyWavelogUploadStation(action.station)
            is SettingsAction.SyncWavelog -> syncWavelog(action.mode)
            SettingsAction.ConfirmWavelogSwitch -> confirmWavelogSwitch()
            SettingsAction.CancelWavelogSwitch -> cancelWavelogSwitch()
            SettingsAction.ClearWavelogConfig -> clearWavelogConfig()
            is SettingsAction.SetLogbookStationFilter -> _uiState.update { it.copy(logbookStationFilter = action.stationId) }
            // LoTW confirmed grids
            is SettingsAction.UpdateLoTW -> settingsRepo.updateLoTWSettings(action.settings)
            is SettingsAction.SyncLoTWGrids -> syncLoTWGrids(action.settings, action.mode)
            SettingsAction.CancelLoTWSync -> cancelLoTWSync()
            // Logbook
            SettingsAction.RefreshLogbook -> refreshLogbook()
            is SettingsAction.DeleteLogbookRecord -> viewModelScope.launch { qsoRepository.delete(action.id) }
            is SettingsAction.UpdateLogbookRecord -> viewModelScope.launch { qsoRepository.save(action.record) }
            SettingsAction.PrepareLogbookUpload -> prepareLogbookUpload()
            SettingsAction.ConfirmLogbookUpload -> confirmLogbookUpload()
            SettingsAction.DismissLogbookPreview -> dismissLogbookPreview()
            SettingsAction.RewriteLogbookCallsigns -> rewriteLogbookCallsigns()
            SettingsAction.IgnoreLogbookPositionWarning -> ignoreLogbookPositionWarning()
            SettingsAction.AbandonLogbookForGridFix -> abandonLogbookForGridFix()
            is SettingsAction.StartLogbookSelection -> startLogbookSelection(action.id)
            is SettingsAction.ToggleLogbookSelection -> toggleLogbookSelection(action.id)
            SettingsAction.ExitLogbookSelection -> exitLogbookSelection()
            SettingsAction.ResubmitSelectedLogbook -> resubmitSelectedLogbook()
            SettingsAction.ClearLogbookMessage -> clearLogbookMessage()
            // LoTW upload configuration
            SettingsAction.LoadLoTWUploadStatus -> loadLoTWUploadStatus()
            is SettingsAction.ImportLoTWCertificate -> importLoTWCertificate(action.bytes, action.password)
            is SettingsAction.PreviewLoTWCertificate -> previewLoTWCertificate(action.bytes, action.password)
            SettingsAction.RemoveLoTWCertificate -> removeLoTWCertificate()
            is SettingsAction.SaveLoTWStation -> saveLoTWStation(action.station)
            // Update checker
            SettingsAction.CheckForUpdate -> checkForUpdate()
            is SettingsAction.DownloadEdition -> downloadEdition(action.edition)
            SettingsAction.ConsumeDownloadedApk -> consumeDownloadedApk()
            // System
            is SettingsAction.ShowToast -> showToast(action.message)
        }
    }

    // Wavelog configuration + sync

    /** A switch that needs the LoTW→Wavelog hand-over confirmation before it applies. */
    private var pendingWavelogSwitch: (() -> Unit)? = null

    private fun currentLoTWConfigured(): Boolean =
        settingsRepo.lotwSettings.value.isConfigured || _uiState.value.lotwCertificate != null

    /**
     * Applies a Wavelog config change. Completing the configuration (the block turning
     * ready) while LoTW is configured pauses for the operator's confirmation: from then
     * on uploads and syncs go through Wavelog only and the LoTW sides stay unavailable
     * (the one-of-two rule). A half-filled config changes nothing about the routing.
     */
    private fun applyWavelogUploadSettings(settings: WavelogUploadSettings) {
        val current = settingsRepo.wavelogUploadSettings.value
        if (!current.isReady && settings.isReady && currentLoTWConfigured()) {
            pendingWavelogSwitch = { settingsRepo.updateWavelogUploadSettings(settings) }
            _uiState.update { it.copy(wavelogSwitchWarning = true) }
            return
        }
        settingsRepo.updateWavelogUploadSettings(settings)
    }

    private fun applyWavelogUploadStation(station: WavelogStationInfo) {
        val current = settingsRepo.wavelogUploadSettings.value
        val updated = current.copy(
            stationId = station.id,
            stationName = station.name,
            stationCallsign = station.callsign,
            stationGrid = station.grid
        )
        if (!current.isReady && updated.isReady && currentLoTWConfigured()) {
            pendingWavelogSwitch = { settingsRepo.updateWavelogUploadSettings(updated) }
            _uiState.update { it.copy(wavelogSwitchWarning = true) }
            return
        }
        settingsRepo.updateWavelogUploadSettings(updated)
    }

    private fun confirmWavelogSwitch() {
        pendingWavelogSwitch?.invoke()
        pendingWavelogSwitch = null
        _uiState.update { it.copy(wavelogSwitchWarning = false) }
    }

    private fun cancelWavelogSwitch() {
        pendingWavelogSwitch = null
        _uiState.update { it.copy(wavelogSwitchWarning = false) }
    }

    /** Wipe the configuration — routing falls back to LoTW on the next check. */
    private fun clearWavelogConfig() {
        settingsRepo.updateWavelogUploadSettings(WavelogUploadSettings())
        _uiState.update { it.copy(logbookStationFilter = null, logbookWavelogPreview = null) }
    }

    private fun fetchWavelogUploadStations(url: String, apiKey: String) {
        if (_uiState.value.wavelogUploadProbeBusy) return
        _uiState.update { it.copy(wavelogUploadProbeBusy = true, wavelogUploadMessage = null) }
        viewModelScope.launch {
            val probe = wavelogUploadRepository.probe(url.trim(), apiKey.trim())
            if (probe == null) {
                _uiState.update {
                    it.copy(
                        wavelogUploadProbeBusy = false,
                        wavelogUploadMessage = "Wavelog probe failed — check URL/key/network"
                    )
                }
                return@launch
            }
            // Persist the full list: the logbook's 台址 selector and the sync use it,
            // not just the dialog that fetched it.
            settingsRepo.setWavelogStations(probe.stations)
            _uiState.update {
                it.copy(
                    wavelogUploadProbeBusy = false,
                    wavelogUploadStations = probe.stations,
                    wavelogUploadRights = probe.rights
                )
            }
            // Single-station setups select themselves; a saved station that the server
            // still reports stays selected.
            val current = settingsRepo.wavelogUploadSettings.value
            val savedStillPresent = current.stationId.isNotBlank() && probe.stations.any { it.id == current.stationId }
            val auto = if (probe.stations.size == 1 && !savedStillPresent) probe.stations.single() else null
            if (auto != null) applyWavelogUploadStation(auto)
        }
    }

    /**
     * Downloads QSOs from Wavelog and folds them into the logbook + map grid data.
     * Incremental resumes from the per-station cursors; the full mode re-pulls each
     * station from the start (healing anything a cursor missed) — neither ever deletes
     * data the app already has.
     */
    private fun syncWavelog(requested: WavelogSyncMode) {
        if (_uiState.value.wavelogSyncing) return
        val settings = settingsRepo.wavelogUploadSettings.value
        if (!settings.isConfigured) {
            _uiState.update { it.copy(wavelogUploadMessage = "Wavelog URL/key not configured") }
            return
        }
        val stations = settingsRepo.getWavelogStations()
        if (stations.isEmpty()) {
            _uiState.update { it.copy(wavelogUploadMessage = "Fetch stations first — no station profile known") }
            return
        }
        val mode = resolveWavelogSyncMode(settingsRepo.getWavelogSyncUrl(), settings.url, requested)
        // Late confirmations can only be picked up by a full pull (Wavelog's incremental
        // cursor is a row id), so promote a stale incremental request to a full re-scan.
        val promoted = mode == WavelogSyncMode.Incremental &&
            shouldPromoteWavelogFullSync(settingsRepo.getLastWavelogFullSyncEpochMs())
        val actualMode = if (promoted) WavelogSyncMode.Full else mode
        _uiState.update { it.copy(wavelogSyncing = true, wavelogUploadMessage = null) }
        viewModelScope.launch {
            try {
                val fetch = wavelogSyncRepository.fetchNewRecords(
                    settings.url,
                    settings.apiKey,
                    stations,
                    settingsRepo.getWavelogSyncCursors(),
                    full = actualMode == WavelogSyncMode.Full
                )
                if (fetch == null) {
                    _uiState.update {
                        it.copy(wavelogSyncing = false, wavelogUploadMessage = "Wavelog sync failed — check URL/key/network")
                    }
                    return@launch
                }
                val merge = if (fetch.records.isEmpty()) {
                    com.rtbishop.look4sat.core.domain.logbook.AdifImportResult(0, 0, 0)
                } else {
                    qsoRepository.mergeWavelog(fetch.records)
                }
                // Stations that failed keep their previous cursor; fetched ones advance.
                val cursors = settingsRepo.getWavelogSyncCursors() + fetch.cursors
                applyWavelogSyncResult(
                    settingsRepo,
                    fetch.records,
                    cursors,
                    settings.url,
                    actualMode,
                    failedStations = fetch.failedStations.toSet()
                )
                _uiState.update {
                    it.copy(
                        wavelogSyncing = false,
                        workedGridsCount = settingsRepo.getWorkedGrids().size,
                        wavelogLastSyncEpochMs = settingsRepo.getLastWavelogSyncEpochMs(),
                        wavelogUploadMessage = wavelogSyncSummary(actualMode, fetch, merge, stations.size, promoted)
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(wavelogSyncing = false, wavelogUploadMessage = "Wavelog sync failed — check URL/key/network")
                }
            }
        }
    }

    private fun wavelogSyncSummary(
        mode: WavelogSyncMode,
        fetch: WavelogSyncFetch,
        merge: com.rtbishop.look4sat.core.domain.logbook.AdifImportResult,
        totalStations: Int,
        promoted: Boolean = false
    ): String {
        val head = when {
            promoted -> "Full Wavelog sync (auto re-scan)"
            mode == WavelogSyncMode.Full -> "Full Wavelog sync"
            else -> "Incremental Wavelog sync"
        }
        val base = "$head — ${merge.imported} new, ${merge.skipped} already there"
        return if (fetch.failedStations.isEmpty()) base
        else "$base · ${fetch.failedStations.size}/$totalStations station(s) failed"
    }

    /**
     * Uploads one prepared Wavelog batch and marks what the server accepted (stamped
     * with the station profile the batch went out through). Returns the operator-facing
     * message for this upload.
     */
    private suspend fun uploadWavelogNow(
        preview: WavelogUploadPreview,
        settings: WavelogUploadSettings
    ): String = try {
        val outcome = wavelogUploadRepository.upload(preview, settings)
        if (outcome is WavelogUploadOutcome.Imported && outcome.markIds.isNotEmpty()) {
            qsoRepository.markWavelogUploaded(outcome.markIds, settings.stationId)
        }
        wavelogConfirmedSegment(outcome, preview)
    } catch (_: Exception) {
        wavelogTransportFailureSegment()
    }

    private fun syncLoTWGrids(
        settings: com.rtbishop.look4sat.core.domain.model.LoTWSettings,
        mode: LoTWSyncMode
    ) {
        if (!settings.isConfigured) {
            _uiState.update { it.copy(lotwError = LoTWError.NotConfigured) }
            return
        }
        // Persist the credentials first, then sync with the freshly-typed values.
        settingsRepo.updateLoTWSettings(settings)
        // Incremental only makes sense for the same callsign as the last sync;
        // a first sync, an unknown/empty record or a callsign change must fall
        // back to a full report so no stale grids from another account linger.
        val callsign = settings.callsign.trim().uppercase()
        val effectiveMode = resolveLoTWSyncMode(settingsRepo.getLastLotwSyncCallsign(), callsign, mode)
        val since = if (effectiveMode == LoTWSyncMode.Incremental) {
            lotwCursorApi(settingsRepo.getLastLotwSyncDate())
        } else ""
        _uiState.update {
            it.copy(lotwSyncing = true, lotwSyncMode = effectiveMode, lotwProgress = null, lotwError = null)
        }
        lotwSyncJob = viewModelScope.launch {
            val result = lotwRepo.fetchConfirmedGridQsos(callsign, settings.password, since) { progress ->
                _uiState.update { it.copy(lotwProgress = progress) }
            }
            when (result) {
                is LoTWResult.Success -> {
                    // Incremental: merge new grids/QSOs into the stored set (dedup
                    // by call + QSO time); full: the fresh report replaces it all.
                    // Shared with the automatic sync on app start (MainApplication).
                    val mergedGridsCount = applyLoTWGridResult(settingsRepo, result, effectiveMode, callsign)
                    // Feed the logbook: imported confirmations show up in 日志本 and
                    // match local uploads (sameConfirmedContact) to mark them confirmed.
                    viewModelScope.launch {
                        val confirmed = result.qsos.values.flatten()
                            .map { it.toConfirmedRecord(callsign) }
                        qsoRepository.mergeLoTW(confirmed)
                    }
                    _uiState.update { state ->
                        state.copy(
                            lotwSyncing = false, lotwSyncMode = null, lotwProgress = null,
                            workedGridsCount = mergedGridsCount, lotwError = null,
                            lotwLastSyncEpochMs = lotwCursorEpochMs(settingsRepo.getLastLotwSyncDate())
                        )
                    }
                }
                is LoTWResult.BadCredentials ->
                    _uiState.update { state ->
                        state.copy(
                            lotwSyncing = false, lotwSyncMode = null, lotwProgress = null,
                            lotwError = LoTWError.BadCredentials
                        )
                    }
                is LoTWResult.RateLimited ->
                    _uiState.update { state ->
                        state.copy(
                            lotwSyncing = false, lotwSyncMode = null, lotwProgress = null,
                            lotwError = LoTWError.RateLimited
                        )
                    }
                is LoTWResult.Timeout ->
                    _uiState.update { state ->
                        state.copy(
                            lotwSyncing = false, lotwSyncMode = null, lotwProgress = null,
                            lotwError = LoTWError.Timeout
                        )
                    }
                is LoTWResult.NetworkError ->
                    _uiState.update { state ->
                        state.copy(
                            lotwSyncing = false, lotwSyncMode = null, lotwProgress = null,
                            lotwError = LoTWError.Network(result.detail)
                        )
                    }
            }
        }
    }

    /** Abort the in-flight LoTW sync job and reset its progress state. */
    private fun cancelLoTWSync() {
        lotwSyncJob?.cancel()
        lotwSyncJob = null
        _uiState.update { it.copy(lotwSyncing = false, lotwSyncMode = null, lotwProgress = null) }
    }

    // endregion

    // region Logbook + LoTW upload configuration

    /**
     * Nominal (no Doppler) uplink/downlink of the satellite [name] is logged through — the
     * logbook's Create draft fills them into a blank record, so the operator does not have to
     * look the pair up and a record without a frequency (which LoTW rejects) cannot be saved.
     */
    suspend fun nominalFrequencies(name: String): NominalFrequencies? = satelliteRepo.nominalFrequencies(name)

    private fun refreshLogbook() {
        viewModelScope.launch {
            _uiState.update { it.copy(logbookRecords = qsoRepository.records.first()) }
        }
    }

    // Record ids submitted in the most recent logbook prepare → upload cycle, so a
    // successful POST can mark them "uploaded" (distinct from "confirmed").
    private var lastLogbookUploadIds: List<Long> = emptyList()

    private fun prepareLogbookUpload() {
        if (_uiState.value.logbookUploadBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(logbookUploadBusy = true, logbookUploadMessage = "") }
            try {
                val all = qsoRepository.records.first()
                val wavelogSettings = settingsRepo.wavelogUploadSettings.value
                if (wavelogSettings.isReady) {
                    // Wavelog mode: the logbook upload goes to Wavelog only.
                    prepareLogbookWavelogUpload(all, wavelogSettings)
                    return@launch
                }
                // Only local (non-confirmed) records are candidates for upload;
                // LoTW-imported confirmations are the feedback side.
                val pending = all.filter { !it.lotwConfirmed && !it.lotwUploaded && it.status == com.rtbishop.look4sat.core.domain.logbook.QsoStatus.COMPLETE }
                val audit = lotwUploadRepository.audit(pending)
                // A batch held back only by callsign conflicts still gets the preview: that dialog
                // carries the rewrite / use-another-certificate actions. Without this the operator
                // gets a dead-end message and those records can never be uploaded.
                if (audit.pending == 0 && !audit.needsPreviewForConflicts()) {
                    val msg = when {
                        audit.unavailable > 0 -> com.rtbishop.look4sat.core.domain.logbook.unavailableUploadSummary(
                            audit.unavailable, audit.reasons, audit.details, audit.duplicates, audit.incomplete
                        )

                        audit.unknown > 0 -> "${audit.unknown} QSO(s) had an unknown upload result — not retried automatically"
                        else -> "No pending QSOs to upload"
                    }
                    _uiState.update { it.copy(logbookUploadBusy = false, logbookUploadMessage = msg) }
                    return@launch
                }
                val preview = lotwUploadRepository.prepare(pending, false)
                // Only the records that actually made it into the TQ8 may be
                // marked uploaded later — never the whole candidate list.
                lastLogbookUploadIds = preview.submittedIds
                _uiState.update {
                    it.copy(
                        logbookUploadBusy = false,
                        logbookPreview = preview,
                        logbookPositionWarning = positionWarning(settingsRepo.getCurrentGrid(), preview.grids)
                    )
                }
            } catch (e: com.rtbishop.look4sat.core.domain.repository.LoTWOperationException) {
                _uiState.update { it.copy(logbookUploadBusy = false, logbookUploadMessage = "Upload unavailable: ${e.reason}") }
            } catch (_: Exception) {
                _uiState.update { it.copy(logbookUploadBusy = false, logbookUploadMessage = "Upload failed") }
            }
        }
    }

    /** Wavelog-mode branch of [prepareLogbookUpload]: prepare the batch, then confirm. */
    private suspend fun prepareLogbookWavelogUpload(
        all: List<com.rtbishop.look4sat.core.domain.logbook.QsoRecord>,
        settings: WavelogUploadSettings
    ) {
        val preview = runCatching {
            wavelogUploadRepository.prepare(wavelogUploadCandidates(all), settings)
        }.getOrNull()
        if (preview == null) {
            _uiState.update {
                it.copy(logbookUploadBusy = false, logbookUploadMessage = "Wavelog upload failed to prepare — check the configuration")
            }
            return
        }
        if (preview.count == 0) {
            _uiState.update {
                it.copy(
                    logbookUploadBusy = false,
                    logbookUploadMessage = wavelogIdleSegment(preview) ?: "No pending QSOs to upload to Wavelog"
                )
            }
            return
        }
        _uiState.update { it.copy(logbookUploadBusy = false, logbookWavelogPreview = preview) }
    }

    private fun confirmLogbookUpload() {
        val wavelogPreview = _uiState.value.logbookWavelogPreview
        if (wavelogPreview != null) {
            viewModelScope.launch {
                _uiState.update { it.copy(logbookUploadBusy = true) }
                val msg = uploadWavelogNow(wavelogPreview, settingsRepo.wavelogUploadSettings.value)
                _uiState.update { it.copy(logbookUploadBusy = false, logbookWavelogPreview = null, logbookUploadMessage = msg) }
            }
            return
        }
        val preview = _uiState.value.logbookPreview ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(logbookUploadBusy = true) }
            val result = lotwUploadRepository.upload(preview.id)
            val accepted = result is com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult.Accepted
            if (accepted && lastLogbookUploadIds.isNotEmpty()) {
                qsoRepository.markUploaded(lastLogbookUploadIds, preview.grids, preview.callsign)
                lastLogbookUploadIds = emptyList()
            }
            val msg = when (result) {
                is com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult.Accepted -> "Uploaded ${result.count} QSO(s) — accepted by LoTW"
                is com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult.Rejected -> "Rejected: ${result.message}"
                com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult.Unknown -> "Unknown result — will not auto-retry"
                com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult.ExpiredPreview -> "Preview expired — tap upload again"
            }
            _uiState.update {
                it.copy(
                    logbookUploadBusy = false,
                    logbookPreview = null,
                    logbookUploadMessage = msg,
                    // An accepted resubmit is done — drop the checks; a failed one keeps
                    // them so retrying stays one tap away.
                    logbookSelectionMode = if (accepted) false else it.logbookSelectionMode,
                    logbookSelectedIds = if (accepted) emptySet() else it.logbookSelectedIds
                )
            }
        }
    }

    private fun dismissLogbookPreview() = _uiState.update { it.copy(logbookPreview = null, logbookWavelogPreview = null) }

    /**
     * Operator chose "rewrite" on the callsign conflict: the refused records get this certificate's
     * callsign so the next upload can sign them. The batch is discarded — nothing was uploaded.
     */
    private fun rewriteLogbookCallsigns() {
        val preview = _uiState.value.logbookPreview ?: return
        val ids = preview.callsignConflicts
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(logbookUploadBusy = true) }
            val changed = qsoRepository.rewriteMyCallsign(ids, preview.callsign)
            lotwUploadRepository.discardPreview()
            lastLogbookUploadIds = emptyList()
            _uiState.update {
                it.copy(
                    logbookUploadBusy = false,
                    logbookPreview = null,
                    logbookUploadMessage = "$changed record(s) rewritten to ${preview.callsign} — upload again"
                )
            }
        }
    }

    /** Operator chose "ignore" on the position check: keep the prepared preview. */
    private fun ignoreLogbookPositionWarning() = _uiState.update { it.copy(logbookPositionWarning = null) }

    /** Operator chose to fix the station grid first: drop the prepared preview and leave. */
    private fun abandonLogbookForGridFix() {
        lotwUploadRepository.discardPreview()
        lastLogbookUploadIds = emptyList()
        _uiState.update { it.copy(logbookPositionWarning = null, logbookPreview = null) }
    }

    /** Long-press entry: selection mode with the pressed record checked. */
    private fun startLogbookSelection(id: Long) = _uiState.update {
        it.copy(logbookSelectionMode = true, logbookSelectedIds = setOf(id))
    }

    private fun toggleLogbookSelection(id: Long) = _uiState.update {
        val selection = it.logbookSelectedIds.toMutableSet().apply { if (!add(id)) remove(id) }
        it.copy(logbookSelectedIds = selection)
    }

    private fun exitLogbookSelection() = _uiState.update {
        it.copy(logbookSelectionMode = false, logbookSelectedIds = emptySet())
    }

    /**
     * Re-upload the checked records — already-uploaded and confirmed rows included — so a
     * corrected station location reaches LoTW and the server updates the existing contacts.
     * The rest is the normal prepare → preview → upload cycle, resubmit flag included.
     */
    private fun resubmitSelectedLogbook() {
        if (_uiState.value.logbookUploadBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(logbookUploadBusy = true, logbookUploadMessage = "") }
            try {
                val all = qsoRepository.records.first()
                val wavelogSettings = settingsRepo.wavelogUploadSettings.value
                if (wavelogSettings.isReady) {
                    // Wavelog mode: re-send the checked records through Wavelog (the
                    // server dedupes what is already there).
                    val selected = all.filter {
                        it.id in _uiState.value.logbookSelectedIds &&
                            it.status == com.rtbishop.look4sat.core.domain.logbook.QsoStatus.COMPLETE
                    }
                    if (selected.isEmpty()) {
                        _uiState.update { it.copy(logbookUploadBusy = false, logbookUploadMessage = "No complete QSOs in the selection") }
                        return@launch
                    }
                    val preview = runCatching {
                        wavelogUploadRepository.prepare(selected, wavelogSettings)
                    }.getOrNull()
                    if (preview == null) {
                        _uiState.update {
                            it.copy(logbookUploadBusy = false, logbookUploadMessage = "Wavelog upload failed to prepare — check the configuration")
                        }
                        return@launch
                    }
                    if (preview.count == 0) {
                        _uiState.update {
                            it.copy(
                                logbookUploadBusy = false,
                                logbookUploadMessage = wavelogIdleSegment(preview) ?: "No QSOs to resubmit"
                            )
                        }
                        return@launch
                    }
                    _uiState.update { it.copy(logbookUploadBusy = false, logbookWavelogPreview = preview) }
                    return@launch
                }
                val selected = resubmitCandidates(all, _uiState.value.logbookSelectedIds)
                if (selected.isEmpty()) {
                    _uiState.update { it.copy(logbookUploadBusy = false, logbookUploadMessage = "No complete QSOs in the selection") }
                    return@launch
                }
                val preview = lotwUploadRepository.prepare(selected, true)
                if (preview.count == 0) {
                    // Every checked record proved un-signable — say why instead of opening
                    // a dead preview whose POST could only expire.
                    val message = unavailableUploadSummary(
                        preview.unavailableSkipped, preview.unavailableReasons, duplicates = preview.duplicateSkipped
                    ).ifBlank { "No QSOs to resubmit" }
                    _uiState.update { it.copy(logbookUploadBusy = false, logbookUploadMessage = message) }
                    return@launch
                }
                lastLogbookUploadIds = preview.submittedIds
                _uiState.update {
                    it.copy(
                        logbookUploadBusy = false,
                        logbookPreview = preview,
                        logbookPositionWarning = positionWarning(settingsRepo.getCurrentGrid(), preview.grids)
                    )
                }
            } catch (e: com.rtbishop.look4sat.core.domain.repository.LoTWOperationException) {
                _uiState.update { it.copy(logbookUploadBusy = false, logbookUploadMessage = "Upload unavailable: ${e.reason}") }
            } catch (_: Exception) {
                _uiState.update { it.copy(logbookUploadBusy = false, logbookUploadMessage = "Upload failed") }
            }
        }
    }

    private fun clearLogbookMessage() = _uiState.update { it.copy(logbookUploadMessage = "") }

    private fun loadLoTWUploadStatus() {
        viewModelScope.launch {
            val cert = lotwUploadRepository.certificate()
            val station = lotwUploadRepository.station()
            // Config parsing failure must never crash the dialog — degrade to no meta.
            val meta = cert?.let { runCatching { lotwUploadRepository.stationMeta(it.dxcc) }.getOrNull() }
            _uiState.update { it.copy(lotwCertificate = cert, lotwStation = station, lotwStationMeta = meta, lotwUploadBusy = false) }
        }
    }

    private fun importLoTWCertificate(bytes: ByteArray, password: CharArray) {
        viewModelScope.launch {
            _uiState.update { it.copy(lotwUploadBusy = true, lotwUploadError = null) }
            try {
                val cert = lotwUploadRepository.importCertificate(bytes, password)
                // Publish the region field for the fresh certificate immediately —
                // previously it only appeared after closing and reopening the dialog.
                val meta = runCatching { lotwUploadRepository.stationMeta(cert.dxcc) }.getOrNull()
                _uiState.update { it.copy(lotwCertificate = cert, lotwStationMeta = meta, lotwUploadBusy = false, lotwUploadError = null) }
            } catch (e: com.rtbishop.look4sat.core.domain.repository.LoTWOperationException) {
                val error = when (e.reason) {
                    com.rtbishop.look4sat.core.domain.repository.LoTWProblem.CERTIFICATE_PASSWORD -> LoTWUploadError.PASSWORD
                    com.rtbishop.look4sat.core.domain.repository.LoTWProblem.CERTIFICATE_EXPIRED -> LoTWUploadError.EXPIRED
                    com.rtbishop.look4sat.core.domain.repository.LoTWProblem.CERTIFICATE_FORMAT -> LoTWUploadError.FORMAT
                    else -> LoTWUploadError.INVALID_FILE
                }
                _uiState.update { it.copy(lotwUploadBusy = false, lotwUploadError = error, lotwUploadErrorDetail = e.detail) }
            } catch (_: Exception) {
                _uiState.update { it.copy(lotwUploadBusy = false, lotwUploadError = LoTWUploadError.UNKNOWN, lotwUploadErrorDetail = "") }
            }
        }
    }

    // Monotonic token so a slow stale preview never overwrites a newer one's meta.
    private var certificatePreviewSeq = 0

    private fun previewLoTWCertificate(bytes: ByteArray, password: CharArray) {
        val seq = ++certificatePreviewSeq
        viewModelScope.launch {
            // Silent by design: a wrong password or a non-p12 file must not disturb
            // the dialog — only a successful parse publishes the region field.
            val meta = runCatching { lotwUploadRepository.previewCertificate(bytes, password) }.fold(
                onSuccess = { cert -> runCatching { lotwUploadRepository.stationMeta(cert.dxcc) }.getOrNull() },
                onFailure = { null }
            )
            if (meta != null && seq == certificatePreviewSeq) {
                _uiState.update { it.copy(lotwStationMeta = meta) }
            }
        }
    }

    private fun removeLoTWCertificate() {
        viewModelScope.launch {
            lotwUploadRepository.removeCertificate()
            _uiState.update { it.copy(lotwCertificate = null, lotwStation = null, lotwStationMeta = null) }
        }
    }

    private fun saveLoTWStation(station: com.rtbishop.look4sat.core.domain.repository.LoTWStation) {
        viewModelScope.launch {
            _uiState.update { it.copy(lotwUploadBusy = true) }
            try {
                val saved = lotwUploadRepository.saveStation(station)
                _uiState.update { it.copy(lotwStation = saved, lotwUploadBusy = false) }
            } catch (_: Exception) {
                _uiState.update { it.copy(lotwUploadBusy = false) }
            }
        }
    }

    // endregion

    // region Update checker

    private fun checkForUpdate() {
        _uiState.update { it.copy(updateChecker = it.updateChecker.copy(isChecking = true, errorResId = null)) }
        viewModelScope.launch {
            val release = updateRepo.getLatestRelease()
            val hasUpdate = release != null &&
                VersionComparator.isNewer(release.versionTag, settingsRepo.appVersionName)
            _uiState.update {
                it.copy(
                    updateChecker = it.updateChecker.copy(
                        isChecking = false,
                        release = release,
                        hasUpdate = hasUpdate,
                        errorResId = if (release == null) R.string.update_check_error else null
                    )
                )
            }
        }
    }

    private fun downloadEdition(edition: ReleaseEdition) {
        val url = _uiState.value.updateChecker.release?.urlOf(edition) ?: return
        _uiState.update { it.copy(updateChecker = it.updateChecker.copy(isDownloading = true, errorResId = null)) }
        viewModelScope.launch {
            val success = updateRepo.downloadApk(url, edition, apkFile)
            _uiState.update {
                it.copy(
                    updateChecker = it.updateChecker.copy(
                        isDownloading = false,
                        apkFile = if (success) apkFile else null,
                        errorResId = if (success) null else R.string.update_check_download_error
                    )
                )
            }
        }
    }

    private fun consumeDownloadedApk() {
        _uiState.update { it.copy(updateChecker = it.updateChecker.copy(apkFile = null)) }
    }

    // endregion

    // region Position helpers — consolidated from 3 near-identical functions

    private fun setGpsPosition() {
        updatePosition(R.string.prefs_loc_gps_error) { settingsRepo.setStationPosition() }
    }

    private fun setGeoPosition(latitude: Double, longitude: Double) {
        updatePosition(R.string.prefs_loc_input_error) { settingsRepo.setStationPosition(latitude, longitude, 0.0) }
    }

    private fun setQthPosition(locator: String) {
        updatePosition(R.string.prefs_loc_qth_error) { settingsRepo.setStationPosition(locator) }
    }

    /**
     * Common position update logic. Calls [action], emits success or error message.
     */
    private inline fun updatePosition(errorResId: Int, action: () -> Boolean) {
        val success = action()
        val resId = if (success) R.string.prefs_loc_success else errorResId
        val isUpdating = success // GPS update is async; geo/qth are immediate
        _uiState.update {
            it.copy(positionSettings = it.positionSettings.copy(isUpdating = isUpdating, messageResId = resId))
        }
    }

    private fun dismissPosMessage() {
        _uiState.update {
            it.copy(positionSettings = it.positionSettings.copy(isUpdating = false, messageResId = 0))
        }
    }

    /** 校正后航向 = 传感器原始方位 + 磁偏角 + 手动偏置. */
    private fun correctedCompassHeading(): Float {
        val declination = sensorsRepo.getMagDeclination(settingsRepo.stationPosition.value)
        val offset = settingsRepo.otherSettings.value.compassOffsetDegrees
        return ((rawCompassHeading + declination + offset) % 360f + 360f) % 360f
    }

    override fun onCleared() {
        sensorsRepo.disableSensor()
        super.onCleared()
    }

    // endregion

    // region Data update helpers — consolidated from 3 near-identical functions

    /**
     * Common data update logic. Sets isUpdating=true, runs the suspending [block],
     * and resets isUpdating=false on failure.
     */
    private fun runDataUpdate(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            _uiState.update {
                it.copy(dataSettings = it.dataSettings.copy(isUpdating = true))
            }
            block()
        } catch (exception: Exception) {
            _uiState.update {
                it.copy(dataSettings = it.dataSettings.copy(isUpdating = false))
            }
            println(exception)
        }
    }

    private fun runManualImport(importErrorMessage: String, block: suspend () -> Int) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(dataSettings = it.dataSettings.copy(isUpdating = true)) }
                if (block() == 0) showToast(importErrorMessage)
            } catch (exception: Exception) {
                _uiState.update { it.copy(dataSettings = it.dataSettings.copy(isUpdating = false)) }
                println(exception)
            }
        }
    }


    // endregion

    companion object {

        fun factory(container: IMainContainer, context: Context) = viewModelFactory {
            initializer {
                SettingsViewModel(
                    databaseRepo = container.databaseRepo,
                    settingsRepo = container.settingsRepo,
                    updateRepo = container.updateRepo,
                    installedEdition = container.edition,
                    wavelogSyncRepository = container.wavelogSyncRepository,
                    wavelogUploadRepository = container.wavelogUploadRepository,
                    lotwRepo = container.lotwRepo,
                    qsoRepository = container.qsoRepository,
                    lotwUploadRepository = container.lotwUploadRepository,
                    satelliteRepo = container.satelliteRepo,
                    sensorsRepo = container.provideSensorsRepo(),
                    apkFile = File(context.cacheDir, "look4sat-update.apk"),
                    showToast = container.provideShowToast()
                )
            }
        }
    }
}
