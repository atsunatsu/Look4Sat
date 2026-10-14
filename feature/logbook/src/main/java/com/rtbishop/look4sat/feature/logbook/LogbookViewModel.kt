/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.logbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.logbook.AdifImportResult
import com.rtbishop.look4sat.core.domain.logbook.IQsoRepository
import com.rtbishop.look4sat.core.domain.logbook.LogbookFilter
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.SATELLITE_MODE_FILTER
import com.rtbishop.look4sat.core.domain.logbook.filterRecords
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.logbook.frequencyBand
import com.rtbishop.look4sat.core.domain.logbook.isSatellite
import com.rtbishop.look4sat.core.domain.model.LoTWSettings
import com.rtbishop.look4sat.core.domain.repository.ILoTWRepository
import com.rtbishop.look4sat.core.domain.repository.ILoTWUploadRepository
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.LoTWCertificate
import com.rtbishop.look4sat.core.domain.repository.LoTWOperationException
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import com.rtbishop.look4sat.core.domain.repository.LoTWProgress
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadAudit
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadPreview
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult
import com.rtbishop.look4sat.core.domain.repository.applyLoTWGridResult
import com.rtbishop.look4sat.core.domain.repository.lotwCursorApi
import com.rtbishop.look4sat.core.domain.repository.resolveLoTWSyncMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class LogbookEditor(
    val id: Long = 0L,
    val startUtcMillis: Long,
    val utcText: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(startUtcMillis)),
    val theirCallsign: String = "",
    val myCallsign: String = "",
    val theirGrid: String = "",
    val myGrid: String = "",
    val sentReport: String = "",
    val receivedReport: String = "",
    val txFrequencyHz: String = "",
    val rxFrequencyHz: String = "",
    val mode: String = "SSB",
    val submode: String = "",
    val satelliteName: String = "",
    val transponderName: String = "",
    val satelliteMode: String = "",
    val automatic: Boolean = false,
    val status: QsoStatus = QsoStatus.COMPLETE,
    val comment: String = "",
    val source: QsoRecord? = null
) {
    fun toRecord(): QsoRecord {
        val call = theirCallsign.trim().uppercase(Locale.US)
        if (call.isBlank()) throw LogbookValidationException(LogbookError.CALLSIGN_REQUIRED)
        if (!call.matches(Regex("[A-Z0-9]+(/[A-Z0-9]+)*")) ||
            call.none(Char::isLetter) || call.none(Char::isDigit)
        ) {
            throw LogbookValidationException(LogbookError.CALLSIGN_INVALID)
        }
        if (mode.isBlank()) throw LogbookValidationException(LogbookError.MODE_REQUIRED)
        fun frequency(value: String): Long? {
            if (value.isBlank()) return null
            return value.toLongOrNull()?.takeIf { it > 0 }
                ?: throw LogbookValidationException(LogbookError.FREQUENCY_INVALID)
        }
        val tx = frequency(txFrequencyHz)
        val rx = frequency(rxFrequencyHz)
        if (!utcText.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))) {
            throw LogbookValidationException(LogbookError.UTC_INVALID)
        }
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val started = if (utcText == formatter.format(Date(startUtcMillis))) startUtcMillis
        else runCatching { formatter.parse(utcText)?.time }.getOrNull()
            ?: throw LogbookValidationException(LogbookError.UTC_INVALID)
        return (source ?: QsoRecord(startUtcMillis = startUtcMillis, theirCallsign = call, myCallsign = "")).copy(
            id = id,
            startUtcMillis = started,
            endUtcMillis = if (status == QsoStatus.COMPLETE) source?.endUtcMillis?.takeIf { it >= started } ?: started else null,
            theirCallsign = call,
            myCallsign = myCallsign.trim().uppercase(Locale.US),
            theirGrid = theirGrid.trim().uppercase(Locale.US),
            myGrid = myGrid.trim().uppercase(Locale.US),
            sentReport = sentReport.trim(),
            receivedReport = receivedReport.trim(),
            txFrequencyHz = tx,
            rxFrequencyHz = rx,
            band = frequencyBand(tx).ifBlank { source?.band.orEmpty() },
            rxBand = frequencyBand(rx).ifBlank { source?.rxBand.orEmpty() },
            mode = mode.trim().uppercase(Locale.US),
            submode = submode.trim().uppercase(Locale.US),
            satelliteName = satelliteName.trim(),
            transponderName = transponderName.trim(),
            satelliteMode = satelliteMode.trim(),
            automatic = automatic,
            status = status,
            propagationMode = when {
                satelliteName.isNotBlank() -> "SAT"
                source?.satelliteName?.isNotBlank() == true -> ""
                else -> source?.propagationMode.orEmpty()
            },
            comment = comment.trim()
        )
    }
}



enum class LogbookError {
    CALLSIGN_REQUIRED,
    CALLSIGN_INVALID,
    MODE_REQUIRED,
    FREQUENCY_INVALID,
    UTC_INVALID,
    IMPORT_READ,
    EXPORT_WRITE,
    EXPORT_PREPARE,
    SAVE,
    DELETE,
    IMPORT
}

sealed interface LoTWDownloadError {
    data object NotConfigured : LoTWDownloadError
    data object BadCredentials : LoTWDownloadError
    data object RateLimited : LoTWDownloadError
    data object Timeout : LoTWDownloadError
    data object Storage : LoTWDownloadError
    data class Network(val detail: String) : LoTWDownloadError
}

data class LoTWDownloadSummary(
    val downloaded: Int,
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    val confirmedGrids: Int
)

data class LogbookState(
    val records: List<QsoRecord> = emptyList(),
    val editor: LogbookEditor? = null,
    val deleteCandidate: QsoRecord? = null,
    val importResult: AdifImportResult? = null,
    val error: LogbookError? = null,
    val query: String = "",
    val modeFilter: String = SATELLITE_MODE_FILTER,
    val confirmationFilter: LogbookFilter = LogbookFilter.ALL,
    val groupByCallsign: Boolean = false,
    val isBusy: Boolean = false,
    val showLoTW: Boolean = false,
    val lotwSyncing: Boolean = false,
    val lotwSettings: LoTWSettings = LoTWSettings(),
    val lotwSyncMode: LoTWSyncMode? = null,
    val lotwProgress: LoTWProgress? = null,
    val lotwDownloadError: LoTWDownloadError? = null,
    val lotwDownloadSummary: LoTWDownloadSummary? = null,
    val workedGridsCount: Int = 0,
    val lotwCertificate: LoTWCertificate? = null,
    val lotwPreview: LoTWUploadPreview? = null,
    val lotwUploadResult: LoTWUploadResult? = null,
    val lotwProblem: LoTWOperationException? = null,
    val lotwDefaultGrid: String = "",
    val lotwStation: LoTWStation? = null,
    val lotwAudit: LoTWUploadAudit? = null,
    val lotwAuditing: Boolean = false,
    val lotwProfileSaved: Boolean = false,
    val showStation: Boolean = false,
    val stationCallsign: String = ""
) {
    /** Same predicate as the settings logbook dialog's query sheet — one definition, two surfaces. */
    val filteredRecords: List<QsoRecord>
        get() = filterRecords(records, query, modeFilter, confirmationFilter)
}

sealed interface LogbookAction {
    data object Add : LogbookAction
    data class Edit(val record: QsoRecord) : LogbookAction
    data class Update(val editor: LogbookEditor) : LogbookAction
    data object Save : LogbookAction
    data object DismissEditor : LogbookAction
    data class RequestDelete(val record: QsoRecord) : LogbookAction
    data object ConfirmDelete : LogbookAction
    data object DismissDelete : LogbookAction
    data class Import(val content: String) : LogbookAction
    data object ClearNotice : LogbookAction
    data class Search(val value: String) : LogbookAction
    data class ModeFilter(val value: String) : LogbookAction
    data class ConfirmationFilter(val value: LogbookFilter) : LogbookAction
    data object ToggleGrouping : LogbookAction
    data object ShowLoTW : LogbookAction
    data object DismissLoTW : LogbookAction
    data class SyncLoTW(val settings: LoTWSettings, val mode: LoTWSyncMode) : LogbookAction
    data class SaveLoTWProfile(
        val certificate: ByteArray?,
        val password: CharArray?,
        val station: LoTWStation
    ) : LogbookAction
    data object RemoveLoTWCertificate : LogbookAction
    data object RefreshLoTWUpload : LogbookAction
    data class PrepareLoTWUpload(val resubmit: Boolean) : LogbookAction
    data object DismissLoTWPreview : LogbookAction
    data object ConfirmLoTWUpload : LogbookAction
    data object CancelLoTW : LogbookAction
    data class Error(val error: LogbookError) : LogbookAction
    data object ShowStation : LogbookAction
    data object DismissStation : LogbookAction
    data class StationCallsign(val value: String) : LogbookAction
    data object SaveStation : LogbookAction
}

class LogbookViewModel(
    private val repository: IQsoRepository,
    private val nowMillis: () -> Long,
    private val defaultCallsign: () -> String,
    private val defaultGrid: () -> String,
    private val settingsRepo: ISettingsRepo,
    private val lotwRepository: ILoTWRepository,
    private val lotwUploadRepository: ILoTWUploadRepository,
    private val updateDefaultCallsign: (String) -> Unit
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        LogbookState(
            lotwSettings = settingsRepo.lotwSettings.value,
            workedGridsCount = settingsRepo.getWorkedGrids().size
        )
    )
    val state: StateFlow<LogbookState> = mutableState
    private var syncJob: Job? = null
    private var auditJob: Job? = null
    private var auditGeneration = 0

    init {
        viewModelScope.launch {
            repository.records.collect { records ->
                mutableState.update { it.copy(records = records) }
                if (mutableState.value.showLoTW) refreshLoTWAudit()
            }
        }
        viewModelScope.launch {
            settingsRepo.lotwSettings.collect { settings ->
                mutableState.update { it.copy(lotwSettings = settings) }
            }
        }
    }

    fun onAction(action: LogbookAction) {
        when (action) {
            LogbookAction.Add -> mutableState.update {
                it.copy(
                    editor = LogbookEditor(
                        startUtcMillis = nowMillis(),
                        myCallsign = defaultCallsign(),
                        myGrid = defaultGrid()
                    ),
                    error = null
                )
            }
            is LogbookAction.Edit -> mutableState.update { it.copy(editor = action.record.toEditor(), error = null) }
            is LogbookAction.Update -> mutableState.update { it.copy(editor = action.editor) }
            LogbookAction.Save -> saveEditor()
            LogbookAction.DismissEditor -> if (!mutableState.value.isBusy) {
                mutableState.update { it.copy(editor = null, error = null) }
            }
            is LogbookAction.RequestDelete -> mutableState.update { it.copy(deleteCandidate = action.record) }
            LogbookAction.ConfirmDelete -> deleteCandidate()
            LogbookAction.DismissDelete -> mutableState.update { it.copy(deleteCandidate = null) }
            is LogbookAction.Import -> importAdi(action.content)
            LogbookAction.ClearNotice -> mutableState.update {
                it.copy(importResult = null, error = null)
            }
            is LogbookAction.Search -> mutableState.update { it.copy(query = action.value) }
            is LogbookAction.ModeFilter -> mutableState.update { it.copy(modeFilter = action.value) }
            is LogbookAction.ConfirmationFilter -> mutableState.update { it.copy(confirmationFilter = action.value) }
            LogbookAction.ToggleGrouping -> mutableState.update { it.copy(groupByCallsign = !it.groupByCallsign) }
            LogbookAction.ShowLoTW -> {
                mutableState.update {
                    it.copy(
                        showLoTW = true,
                        lotwProblem = null,
                        lotwDownloadError = null,
                        workedGridsCount = settingsRepo.getWorkedGrids().size,
                        lotwDefaultGrid = defaultGrid(),
                        lotwProfileSaved = false
                    )
                }
                viewModelScope.launch {
                    try {
                        val certificate = lotwUploadRepository.certificate()
                        val station = lotwUploadRepository.station()
                        mutableState.update { it.copy(lotwCertificate = certificate, lotwStation = station) }
                        refreshLoTWAudit()
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableState.update { it.copy(lotwProblem = LoTWOperationException(LoTWProblem.STORAGE)) } }
                }
            }
            LogbookAction.DismissLoTW -> if (!mutableState.value.lotwSyncing) {
                lotwUploadRepository.discardPreview()
                auditJob?.cancel()
                mutableState.update { it.copy(showLoTW = false, lotwAudit = null, lotwAuditing = false) }
            }
            is LogbookAction.SyncLoTW -> syncLoTW(action.settings, action.mode)
            is LogbookAction.SaveLoTWProfile -> lotwOperation {
                val certificate = when {
                    action.certificate != null -> lotwUploadRepository.importCertificate(
                        action.certificate,
                        action.password ?: throw LoTWOperationException(LoTWProblem.CERTIFICATE_PASSWORD)
                    )
                    action.password != null -> lotwUploadRepository.saveCertificatePassword(action.password)
                    else -> mutableState.value.lotwCertificate
                        ?: throw LoTWOperationException(LoTWProblem.CERTIFICATE_MISSING)
                }
                mutableState.update { it.copy(lotwCertificate = certificate) }
                val station = lotwUploadRepository.saveStation(action.station)
                mutableState.update { it.copy(lotwStation = station, lotwProfileSaved = true) }
                refreshLoTWAudit()
            }
            LogbookAction.RemoveLoTWCertificate -> lotwOperation {
                lotwUploadRepository.removeCertificate()
                auditJob?.cancel()
                mutableState.update { it.copy(lotwCertificate = null, lotwPreview = null, lotwAudit = null, lotwAuditing = false) }
            }
            LogbookAction.RefreshLoTWUpload -> refreshLoTWAudit()
            is LogbookAction.PrepareLoTWUpload -> lotwOperation {
                val preview = lotwUploadRepository.prepare(mutableState.value.filteredRecords, action.resubmit)
                mutableState.update { it.copy(lotwPreview = preview) }
            }
            LogbookAction.DismissLoTWPreview -> if (!mutableState.value.lotwSyncing) {
                lotwUploadRepository.discardPreview()
                mutableState.update { it.copy(lotwPreview = null) }
            }
            LogbookAction.ConfirmLoTWUpload -> {
                val preview = mutableState.value.lotwPreview
                if (preview != null) lotwOperation {
                    mutableState.update { it.copy(lotwPreview = null, lotwUploadResult = LoTWUploadResult.Unknown) }
                    val result = lotwUploadRepository.upload(preview.id)
                    mutableState.update { it.copy(lotwUploadResult = result) }
                    refreshLoTWAudit()
                }
            }
            LogbookAction.CancelLoTW -> syncJob?.cancel()
            is LogbookAction.Error -> mutableState.update { it.copy(error = action.error) }
            LogbookAction.ShowStation -> mutableState.update {
                it.copy(showStation = true, stationCallsign = defaultCallsign(), error = null)
            }
            LogbookAction.DismissStation -> mutableState.update { it.copy(showStation = false) }
            is LogbookAction.StationCallsign -> mutableState.update { it.copy(stationCallsign = action.value) }
            LogbookAction.SaveStation -> {
                val call = mutableState.value.stationCallsign.trim().uppercase(Locale.US)
                if (com.rtbishop.look4sat.core.domain.logbook.quickLogValidation(call, "59", "59", "FM") == null) {
                    updateDefaultCallsign(call)
                    mutableState.update { it.copy(showStation = false, error = null) }
                } else {
                    mutableState.update {
                        it.copy(
                            error = if (call.isBlank()) LogbookError.CALLSIGN_REQUIRED
                            else LogbookError.CALLSIGN_INVALID
                        )
                    }
                }
            }
        }
    }

    suspend fun exportAdi(includeIncomplete: Boolean = false): String = repository.exportAdi(
        ids = mutableState.value.filteredRecords.mapTo(mutableSetOf()) { it.id },
        includeIncomplete = includeIncomplete
    )

    private fun syncLoTW(settings: LoTWSettings, requestedMode: LoTWSyncMode) {
        if (syncJob?.isActive == true) return
        if (!settings.isConfigured) {
            mutableState.update {
                it.copy(
                    lotwDownloadError = LoTWDownloadError.NotConfigured,
                    lotwDownloadSummary = null
                )
            }
            return
        }
        settingsRepo.updateLoTWSettings(settings)
        val callsign = settings.callsign.trim().uppercase(Locale.US)
        val mode = resolveLoTWSyncMode(settingsRepo.getLastLotwSyncCallsign(), callsign, requestedMode)
        val since = if (mode == LoTWSyncMode.Incremental) {
            lotwCursorApi(settingsRepo.getLastLotwSyncDate())
        } else ""
        mutableState.update {
            it.copy(
                lotwSyncing = true,
                lotwSyncMode = mode,
                lotwProgress = null,
                lotwDownloadError = null,
                lotwDownloadSummary = null
            )
        }
        syncJob = viewModelScope.launch {
            try {
                when (val result = lotwRepository.fetchQsos(
                    callsign,
                    settings.password,
                    mode,
                    since
                ) { progress -> mutableState.update { it.copy(lotwProgress = progress) } }) {
                    is LoTWResult.Success -> {
                        val logbook = repository.mergeLoTW(result.records)
                        val grids = applyLoTWGridResult(settingsRepo, result, mode, callsign)
                        mutableState.update {
                            it.copy(
                                workedGridsCount = grids,
                                lotwDownloadSummary = LoTWDownloadSummary(
                                    downloaded = result.downloaded,
                                    imported = logbook.imported,
                                    updated = logbook.updated,
                                    skipped = logbook.skipped,
                                    confirmedGrids = grids
                                )
                            )
                        }
                    }
                    LoTWResult.BadCredentials -> mutableState.update {
                        it.copy(lotwDownloadError = LoTWDownloadError.BadCredentials)
                    }
                    LoTWResult.RateLimited -> mutableState.update {
                        it.copy(lotwDownloadError = LoTWDownloadError.RateLimited)
                    }
                    LoTWResult.Timeout -> mutableState.update {
                        it.copy(lotwDownloadError = LoTWDownloadError.Timeout)
                    }
                    is LoTWResult.NetworkError -> mutableState.update {
                        it.copy(lotwDownloadError = LoTWDownloadError.Network(result.detail))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(lotwDownloadError = LoTWDownloadError.Storage) }
            } finally {
                mutableState.update {
                    it.copy(lotwSyncing = false, lotwSyncMode = null, lotwProgress = null)
                }
            }
        }
    }

    private fun lotwOperation(block: suspend () -> Unit) {
        if (syncJob?.isActive == true) return
        mutableState.update { it.copy(lotwSyncing = true, lotwProblem = null, lotwUploadResult = null, lotwProfileSaved = false) }
        syncJob = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: LoTWOperationException) { mutableState.update { it.copy(lotwProblem = error) } }
            catch (_: Exception) { mutableState.update { it.copy(lotwProblem = LoTWOperationException(LoTWProblem.STORAGE)) } }
            finally { mutableState.update { it.copy(lotwSyncing = false) } }
        }
    }

    private fun refreshLoTWAudit() {
        auditJob?.cancel()
        val generation = ++auditGeneration
        val current = mutableState.value
        if (!current.showLoTW || current.lotwCertificate?.passwordSaved != true || current.lotwStation == null) {
            mutableState.update { it.copy(lotwAudit = null, lotwAuditing = false) }
            return
        }
        val records = current.filteredRecords
        mutableState.update { it.copy(lotwAudit = null, lotwAuditing = true) }
        auditJob = viewModelScope.launch {
            try {
                val audit = lotwUploadRepository.audit(records)
                mutableState.update { it.copy(lotwAudit = audit, lotwProblem = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LoTWOperationException) {
                mutableState.update { it.copy(lotwAudit = null, lotwProblem = error) }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(lotwAudit = null, lotwProblem = LoTWOperationException(LoTWProblem.STORAGE))
                }
            } finally {
                if (generation == auditGeneration) mutableState.update { it.copy(lotwAuditing = false) }
            }
        }
    }

    override fun onCleared() {
        lotwUploadRepository.discardPreview()
        super.onCleared()
    }

    private fun saveEditor() = viewModelScope.launch {
        if (mutableState.value.isBusy) return@launch
        val editor = mutableState.value.editor ?: return@launch
        mutableState.update { it.copy(isBusy = true) }
        try {
            repository.save(editor.toRecord())
            mutableState.update { it.copy(editor = null, error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LogbookValidationException) {
            mutableState.update { it.copy(error = error.error) }
        } catch (_: Exception) {
            mutableState.update { it.copy(error = LogbookError.SAVE) }
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    private fun deleteCandidate() = viewModelScope.launch {
        if (mutableState.value.isBusy) return@launch
        val record = mutableState.value.deleteCandidate ?: return@launch
        mutableState.update { it.copy(isBusy = true) }
        try {
            repository.delete(record.id)
            mutableState.update { it.copy(deleteCandidate = null, error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(error = LogbookError.DELETE) }
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    private fun importAdi(content: String) = viewModelScope.launch {
        if (mutableState.value.isBusy) return@launch
        mutableState.update { it.copy(isBusy = true) }
        try {
            val result = repository.importAdi(content)
            mutableState.update { it.copy(importResult = result, error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(error = LogbookError.IMPORT) }
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                LogbookViewModel(
                    repository = container.qsoRepository,
                    nowMillis = container.disciplinedClock::nowMillis,
                    defaultCallsign = { container.settingsRepo.ft4Settings.value.operatorCallsign },
                    defaultGrid = { container.settingsRepo.stationPosition.value.qthLocator },
                    settingsRepo = container.settingsRepo,
                    lotwRepository = container.lotwRepo,
                    lotwUploadRepository = container.lotwUploadRepository,
                    updateDefaultCallsign = { call -> container.settingsRepo.updateFt4Settings { it.copy(operatorCallsign = call) } }
                )
            }
        }
    }
}

private class LogbookValidationException(val error: LogbookError) : IllegalArgumentException()

private fun QsoRecord.toEditor() = LogbookEditor(
    id = id,
    startUtcMillis = startUtcMillis,
    theirCallsign = theirCallsign,
    myCallsign = myCallsign,
    theirGrid = theirGrid,
    myGrid = myGrid,
    sentReport = sentReport,
    receivedReport = receivedReport,
    txFrequencyHz = txFrequencyHz?.toString().orEmpty(),
    rxFrequencyHz = rxFrequencyHz?.toString().orEmpty(),
    mode = mode,
    submode = submode,
    satelliteName = satelliteName,
    transponderName = transponderName,
    satelliteMode = satelliteMode,
    automatic = automatic,
    status = status,
    comment = comment,
    source = this
)
