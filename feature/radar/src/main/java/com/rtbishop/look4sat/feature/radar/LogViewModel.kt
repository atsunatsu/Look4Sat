/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.logbook.IQsoRepository
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.logbook.frequencyBand
import com.rtbishop.look4sat.core.domain.logbook.needsPreviewForConflicts
import com.rtbishop.look4sat.core.domain.logbook.officialSatelliteName
import com.rtbishop.look4sat.core.domain.logbook.operatorCallsign
import com.rtbishop.look4sat.core.domain.logbook.operatorGrids
import com.rtbishop.look4sat.core.domain.logbook.satelliteIdentity
import com.rtbishop.look4sat.core.domain.logbook.unavailableUploadSummary
import com.rtbishop.look4sat.core.domain.logbook.wavelogConfirmedSegment
import com.rtbishop.look4sat.core.domain.logbook.wavelogIdleSegment
import com.rtbishop.look4sat.core.domain.logbook.wavelogTransportFailureSegment
import com.rtbishop.look4sat.core.domain.logbook.wavelogUploadCandidates
import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.repository.ILoTWUploadRepository
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.IWavelogUploadRepository
import com.rtbishop.look4sat.core.domain.repository.LoTWOperationException
import com.rtbishop.look4sat.core.domain.repository.LoTWPositionWarning
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadPreview
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadOutcome
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadPreview
import com.rtbishop.look4sat.core.domain.repository.positionWarning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A contact waiting for the operator to confirm logging while the clock is outside the
 * current pass window (after LOS, before AOS, or against a pass card that is not the one
 * being worked). Confirmed entries are stored at the pass midpoint — inside [aos, los] —
 * so the window-filtered log page can show them; the exact time can be re-dated from the
 * edit dialog afterwards.
 */
data class LogRecordOverrides(
    val myCallsign: String? = null,
    val sentReport: String = "",
    val receivedReport: String = "",
    val mode: String? = null
)

data class OutOfWindowLog(
    val callsign: String,
    val satName: String,
    val mode: String,
    val txHz: Long?,
    val rxHz: Long?,
    val myGrid: String,
    val midpointUtcMillis: Long,
    val overrides: LogRecordOverrides = LogRecordOverrides()
)

data class LogUiState(
    val callsignInput: String = "",
    val recordSaving: Boolean = false,
    val recordSaveFailed: Boolean = false,
    /** Current logbook mode for the selected satellite (FM/CW/SSB/FT4). */
    val selectedMode: String = "",
    /** Station callsign from the imported LoTW certificate ("" when none). */
    val certificateCallsign: String = "",
    /** Callsign of the selected Wavelog station profile ("" when none). The own-callsign
     *  fallback for a Wavelog-only setup, which has no certificate to take it from. */
    val wavelogCallsign: String = "",
    /** Grids a record is logged under: the LoTW station location's, else the selected
     *  Wavelog station profile's (several gridsquares arrive comma-separated). */
    val stationGrid: String = "",
    /** ARRL satellite names (config.tq6) — the only names a record may be signed with. */
    val satelliteCatalog: List<String> = emptyList(),
    val preview: LoTWUploadPreview? = null,
    /** Roaming guard: the current position grid is outside the station grids the prepared
     *  batch would be signed with — shown as a dialog before the preview opens. */
    val uploadPositionWarning: LoTWPositionWarning? = null,
    /** Roaming hint on the log page: current position grid outside the station grids
     *  (null when covered or unknown). Tapping it jumps to the station location. */
    val stationMismatch: LoTWPositionWarning? = null,
    /** User-facing upload / record message ("" when none). */
    val message: String = "",
    val postText: String? = null,
    val busy: Boolean = false,
    /** True when uploads route through Wavelog (Wavelog configured — LoTW paused). */
    val wavelogMode: Boolean = false,
    /** Wavelog mode: the prepared batch awaiting the operator's confirmation. */
    val wavelogPreview: WavelogUploadPreview? = null,
    /** Out-of-window contact waiting for the operator's confirmation (null when closed). */
    val outOfWindowLog: OutOfWindowLog? = null
) {
    /** Own callsign written into a new record: the certificate's, else the Wavelog
     *  station profile's (a Wavelog-only setup installs no certificate). */
    val ownCallsign: String get() = operatorCallsign(certificateCallsign, wavelogCallsign)
}

class LogViewModel(
    private val qsoRepository: IQsoRepository,
    private val lotwUploadRepository: ILoTWUploadRepository,
    private val wavelogUploadRepository: IWavelogUploadRepository,
    private val settingsRepo: ISettingsRepo
) : ViewModel() {

    /** Records shown on the radar log page: all records of the current pass window, including
     *  already-uploaded (UP) and confirmed (QSL) ones. Window filtering happens in LogPage. */
    val records: Flow<List<QsoRecord>> = qsoRepository.records

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState: StateFlow<LogUiState> = _uiState

    // Record ids submitted in the most recent prepare → upload cycle, so a
    // successful POST can mark them "uploaded" (distinct from "confirmed").
    private var lastUploadedIds: List<Long> = emptyList()
    private var passKey: Pair<Int, Long>? = null

    fun clearPassDraft() {
        passKey = null
        _uiState.update { it.copy(callsignInput = "", outOfWindowLog = null, recordSaveFailed = false) }
    }

    fun selectPass(catnum: Int, aosTime: Long) {
        val key = catnum to aosTime
        if (passKey == key) return
        passKey = key
        _uiState.update { it.copy(callsignInput = "", outOfWindowLog = null, recordSaveFailed = false) }
    }

    init {
        viewModelScope.launch {
            val cert = lotwUploadRepository.certificate()
            val catalog = runCatching { lotwUploadRepository.satelliteCatalog() }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(certificateCallsign = cert?.callsign.orEmpty(), satelliteCatalog = catalog)
            }
            // Station grids and the roaming hint come from the LoTW station location, with the
            // selected Wavelog station profile as the fallback (a Wavelog-only setup has no
            // station location at all).
            refreshPositionHintNow()
        }
        // Upload routing follows the Wavelog configuration: once it is ready the log
        // page uploads through Wavelog and skips LoTW entirely (the one-of-two rule).
        viewModelScope.launch {
            settingsRepo.wavelogUploadSettings.collect { settings ->
                _uiState.update {
                    it.copy(wavelogMode = settings.isReady, wavelogCallsign = settings.stationCallsign)
                }
                // A station profile picked (or cleared) while the page is open changes the own
                // callsign and the grids a record would be logged under.
                refreshPositionHintNow()
            }
        }
        // The roaming hint tracks the freshest position data: refresh on init and every fix.
        viewModelScope.launch {
            settingsRepo.stationPosition.collect { refreshPositionHintNow() }
        }
    }

    fun updateCallsign(value: String) =
        _uiState.update { it.copy(callsignInput = value.uppercase(Locale.US), recordSaveFailed = false) }

    /** Load the per-satellite mode preset when the selected satellite changes. */
    fun selectSatellite(catnum: Int) {
        val preset = settingsRepo.getSatelliteMode(catnum)
        _uiState.update { it.copy(selectedMode = preset) }
    }

    fun selectMode(catnum: Int, mode: String) {
        _uiState.update { it.copy(selectedMode = mode) }
        settingsRepo.setSatelliteMode(catnum, mode)
    }

    fun record(
        satName: String,
        mode: String,
        txHz: Long?,
        rxHz: Long?,
        myGrid: String,
        passWindow: ClosedRange<Long>?,
        overrides: LogRecordOverrides = LogRecordOverrides()
    ) {
        if (_uiState.value.recordSaving) return
        val call = _uiState.value.callsignInput.trim()
        if (call.isEmpty()) return
        // Whole minutes (seconds zeroed): matches the edit dialog's HH:mm granularity and the
        // minute-resolution of LoTW reports, so re-saving an unchanged record never looks edited.
        val now = System.currentTimeMillis() / 60_000L * 60_000L
        // Logging while the clock sits outside the current pass window (after LOS, before AOS,
        // or against a pass card that is not the one being worked): the log page only shows
        // records inside [aos, los], so a "now" timestamp could never appear there. Ask the
        // operator first; the confirmed contact is stored at the pass midpoint (always inside
        // the window) and can be re-dated later from the edit dialog.
        if (passWindow != null && now !in passWindow) {
            val midpoint = ((passWindow.start + passWindow.endInclusive) / 2L)
                .let { it / 60_000L * 60_000L }
                .coerceIn(passWindow.start, passWindow.endInclusive)
            _uiState.update {
                it.copy(outOfWindowLog = OutOfWindowLog(call, satName, mode, txHz, rxHz, myGrid, midpoint, overrides))
            }
            return
        }
        storeRecord(call, satName, mode, txHz, rxHz, myGrid, now, overrides)
    }

    /** The operator confirmed the out-of-window notice: store the contact at the pass midpoint. */
    fun confirmOutOfWindowLog() {
        val pending = _uiState.value.outOfWindowLog ?: return
        storeRecord(
            pending.callsign, pending.satName, pending.mode,
            pending.txHz, pending.rxHz, pending.myGrid, pending.midpointUtcMillis, pending.overrides
        )
        _uiState.update { it.copy(outOfWindowLog = null) }
    }

    fun dismissOutOfWindowLog() = _uiState.update { it.copy(outOfWindowLog = null) }

    private fun storeRecord(
        call: String,
        satName: String,
        mode: String,
        txHz: Long?,
        rxHz: Long?,
        myGrid: String,
        timeMillis: Long,
        overrides: LogRecordOverrides
    ) {
        if (_uiState.value.recordSaving) return
        val normalizedMode = (overrides.mode ?: mode).ifBlank { "FM" }.uppercase(Locale.US)
        val recordingPass = passKey
        // kHz granularity (3 decimals in MHz) — enough for operating and for the ADIF export.
        val tx = txHz?.let { (it + 500L) / 1000L * 1000L }
        val rx = rxHz?.let { (it + 500L) / 1000L * 1000L }
        val record = QsoRecord(
            startUtcMillis = timeMillis,
            endUtcMillis = timeMillis,
            theirCallsign = call,
            myCallsign = (overrides.myCallsign ?: _uiState.value.ownCallsign).trim().uppercase(Locale.US),
            sentReport = overrides.sentReport.trim(),
            receivedReport = overrides.receivedReport.trim(),
            myGrid = myGrid.take(6).uppercase(Locale.US),
            txFrequencyHz = tx,
            rxFrequencyHz = rx,
            band = frequencyBand(tx),
            rxBand = frequencyBand(rx),
            mode = if (normalizedMode == "FT4") "MFSK" else normalizedMode,
            submode = normalizedMode.takeIf { it == "FT4" }.orEmpty(),
            // Stored under the ARRL name (the tracker's "SAUDISAT 1C" is logged as "SO-50"):
            // one name for the logbook, the ADIF export and the signed record.
            satelliteName = officialSatelliteName(satName, _uiState.value.satelliteCatalog),
            satelliteMode = normalizedMode,
            status = QsoStatus.COMPLETE,
            propagationMode = "SAT"
        )
        _uiState.update { it.copy(recordSaving = true, recordSaveFailed = false) }
        viewModelScope.launch {
            try {
                qsoRepository.save(record)
                _uiState.update {
                    it.copy(callsignInput = if (passKey == recordingPass && it.callsignInput.trim() == call) "" else it.callsignInput)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { if (passKey == recordingPass) it.copy(recordSaveFailed = true) else it }
            } finally {
                _uiState.update { it.copy(recordSaving = false) }
            }
        }
    }

    fun delete(id: Long) = viewModelScope.launch { qsoRepository.delete(id) }

    /**
     * Persist an edited record (frequency/callsign/time/…). The repository keeps
     * the LoTW confirmation state consistent when the contact identity changes.
     */
    fun updateRecord(record: QsoRecord) = viewModelScope.launch { qsoRepository.save(record) }

    /** English social post from the recorded QSOs of the current satellite.
     *  Same window logic as the on-screen list: only the current pass (aos..los). */
    fun generatePost(satName: String, maxElev: Double, passWindow: ClosedRange<Long>?) {
        viewModelScope.launch {
            // Records carry the ARRL name while the pass carries the tracker's name, so the
            // operator's own contacts are collected by identity, not by the raw name.
            val identity = satelliteIdentity(satName)
            val list = qsoRepository.records.first()
                .filter { satelliteIdentity(it.satelliteName) == identity }
                .filter { passWindow?.contains(it.startUtcMillis) == true }
                .sortedBy { it.startUtcMillis }
            if (list.isEmpty()) return@launch
            val shortName = officialSatelliteName(satName).substringBefore('(').trim().uppercase(Locale.US)
            val utc = SimpleDateFormat("yyyyMMdd|HH:mm'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val firstLine = "🛰️ $shortName | ${utc.format(Date(list.first().startUtcMillis))}"
            val elLine = "🔺 EL ${String.format(Locale.US, "%.1f", maxElev)}° | ${list.size} QSOs"
            val modeLines = list.groupBy { it.displayMode }
                .map { (mode, qsos) -> "📡 $mode: ${qsos.joinToString(" ") { it.theirCallsign }}" }
                .joinToString("\n")
            val currentGrid = settingsRepo.stationPosition.value.qthLocator.take(6)
            val logLine = "📍 Log: ${_uiState.value.stationGrid.take(4)} | Current: $currentGrid"
            val viaLine = "via Look4Sat-BA7OPF — TNX de ${_uiState.value.ownCallsign}"
            val tag = "#HamRadio #SatelliteQSO #" + shortName.filter { it.isLetterOrDigit() }
            val text = listOf(firstLine, elLine, modeLines, "", logLine, viaLine, tag).joinToString("\n")
            _uiState.update { it.copy(postText = text) }
        }
    }

    fun dismissPost() = _uiState.update { it.copy(postText = null) }

    fun prepareUpload() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = "") }
            try {
                val all = qsoRepository.records.first()
                val wavelogSettings = settingsRepo.wavelogUploadSettings.value
                if (wavelogSettings.isReady) {
                    // Wavelog mode: the upload goes to Wavelog only (LoTW stays out).
                    prepareWavelogUpload(all, wavelogSettings)
                    return@launch
                }
                // Only local (non-confirmed) records are candidates for upload;
                // LoTW-imported confirmations are the feedback side.
                val pending = all.filter { !it.lotwConfirmed && !it.lotwUploaded && it.status == QsoStatus.COMPLETE }
                val audit = lotwUploadRepository.audit(pending)
                // A batch held back only by callsign conflicts still gets the preview: that dialog
                // carries the rewrite / use-another-certificate actions. Without this the operator
                // gets a dead-end message and those records can never be uploaded.
                if (audit.pending == 0 && !audit.needsPreviewForConflicts()) {
                    val msg = when {
                        audit.unavailable > 0 -> unavailableUploadSummary(
                            audit.unavailable, audit.reasons, audit.details, audit.duplicates, audit.incomplete
                        )

                        audit.unknown > 0 -> "${audit.unknown} QSO(s) have an unknown LoTW upload result. Check LoTW before manually resubmitting; automatic retry is disabled to avoid duplicates."
                        else -> "No pending QSOs to upload"
                    }
                    _uiState.update { it.copy(busy = false, message = msg) }
                    return@launch
                }
                val preview = lotwUploadRepository.prepare(pending, false)
                // Only the records that actually made it into the TQ8 may be
                // marked uploaded later — never the whole candidate list.
                lastUploadedIds = preview.submittedIds
                _uiState.update {
                    it.copy(
                        busy = false,
                        preview = preview,
                        uploadPositionWarning = positionWarning(settingsRepo.getCurrentGrid(), preview.grids)
                    )
                }
            } catch (e: LoTWOperationException) {
                _uiState.update { it.copy(busy = false, message = "Upload unavailable: ${e.reason}") }
            } catch (_: Exception) {
                _uiState.update { it.copy(busy = false, message = "Upload failed") }
            }
        }
    }

    /** Wavelog-mode branch of [prepareUpload]: prepare the batch and ask for confirmation. */
    private suspend fun prepareWavelogUpload(all: List<QsoRecord>, settings: WavelogUploadSettings) {
        val preview = runCatching {
            wavelogUploadRepository.prepare(wavelogUploadCandidates(all), settings)
        }.getOrNull()
        if (preview == null) {
            _uiState.update { it.copy(busy = false, message = "Wavelog upload failed to prepare — check the configuration") }
            return
        }
        if (preview.count == 0) {
            _uiState.update {
                it.copy(busy = false, message = wavelogIdleSegment(preview) ?: "No pending QSOs to upload to Wavelog")
            }
            return
        }
        _uiState.update { it.copy(busy = false, wavelogPreview = preview) }
    }

    fun confirmUpload() {
        val preview = _uiState.value.preview ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val result = lotwUploadRepository.upload(preview.id)
            if (result is LoTWUploadResult.Accepted && lastUploadedIds.isNotEmpty()) {
                // Mark the submitted QSOs as uploaded (distinct from confirmed) and stamp
                // the station grids this batch went out under.
                qsoRepository.markUploaded(lastUploadedIds, preview.grids, preview.callsign)
                lastUploadedIds = emptyList()
            }
            val msg = when (result) {
                is LoTWUploadResult.Accepted -> "Uploaded ${result.count} QSO(s) — accepted by LoTW"
                is LoTWUploadResult.Rejected -> "Rejected: ${result.message}"
                is LoTWUploadResult.Unknown -> buildString {
                    append("LoTW upload result is unknown. The server may have received these QSOs, so they were not retried automatically.\n\n")
                    append(result.reason)
                    result.httpStatus?.let { append("\nHTTP status: ").append(it) }
                    result.technicalDetail.takeIf { it.isNotBlank() }?.let { append("\n\nTechnical details: ").append(it) }
                    append("\n\nCheck LoTW before manually resubmitting these QSOs.")
                }
                LoTWUploadResult.ExpiredPreview -> "Preview expired — tap upload again"
            }
            _uiState.update { it.copy(busy = false, preview = null, message = msg) }
        }
    }

    /** Confirms the Wavelog-mode batch prepared by [prepareUpload]. */
    fun confirmWavelogUpload() {
        val preview = _uiState.value.wavelogPreview ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val msg = uploadWavelogNow(preview, settingsRepo.wavelogUploadSettings.value)
            _uiState.update { it.copy(busy = false, wavelogPreview = null, message = msg) }
        }
    }

    fun dismissPreview() = _uiState.update { it.copy(preview = null) }

    /**
     * Operator chose "rewrite" on the callsign conflict: the refused records get this certificate's
     * callsign so the next upload can sign them. The prepared batch is dropped — nothing was sent.
     */
    fun rewriteCallsignConflicts() {
        val preview = _uiState.value.preview ?: return
        val ids = preview.callsignConflicts
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val changed = qsoRepository.rewriteMyCallsign(ids, preview.callsign)
            lotwUploadRepository.discardPreview()
            lastUploadedIds = emptyList()
            _uiState.update {
                it.copy(busy = false, preview = null, message = "$changed record(s) rewritten to ${preview.callsign} — upload again")
            }
        }
    }

    /** Operator chose "use another certificate": that certificate is imported on the settings page. */
    fun switchCertificateForConflicts() {
        lotwUploadRepository.discardPreview()
        lastUploadedIds = emptyList()
        _uiState.update {
            it.copy(preview = null, message = "Import the other callsign's certificate in Settings → LoTW upload, then upload again")
        }
    }

    fun dismissWavelogPreview() = _uiState.update { it.copy(wavelogPreview = null) }

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
            qsoRepository.markWavelogUploaded(outcome.markIds, settings.stationId, settings.stationCallsign)
        }
        wavelogConfirmedSegment(outcome, preview)
    } catch (_: Exception) {
        wavelogTransportFailureSegment()
    }

    /** Operator chose "ignore" on the position check: keep the prepared preview. */
    fun ignorePositionWarning() = _uiState.update { it.copy(uploadPositionWarning = null) }

    /** Operator chose to fix the station grid first: drop the prepared preview and leave. */
    fun abandonForGridFix() {
        lotwUploadRepository.discardPreview()
        lastUploadedIds = emptyList()
        _uiState.update { it.copy(uploadPositionWarning = null, preview = null) }
    }

    /** Recompute the log-page roaming hint from the freshest station/position data. */
    fun refreshPositionHint() = viewModelScope.launch { refreshPositionHintNow() }

    private suspend fun refreshPositionHintNow() {
        val station = runCatching { lotwUploadRepository.station() }.getOrNull()
        // The station location first; a Wavelog-only setup has none, so its records are logged —
        // and its roaming hint measured — against the selected Wavelog station profile's grids.
        val grids = operatorGrids(station?.grid.orEmpty(), settingsRepo.wavelogUploadSettings.value.stationGrid)
        _uiState.update {
            it.copy(
                stationMismatch = positionWarning(settingsRepo.getCurrentGrid(), grids),
                stationGrid = grids.joinToString(",")
            )
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = "") }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                LogViewModel(
                    container.qsoRepository, container.lotwUploadRepository,
                    container.wavelogUploadRepository, container.settingsRepo
                )
            }
        }
    }
}
