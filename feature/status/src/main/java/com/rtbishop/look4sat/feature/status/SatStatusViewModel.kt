package com.rtbishop.look4sat.feature.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.model.AmSatReportSubmission
import com.rtbishop.look4sat.core.domain.model.SatReport
import com.rtbishop.look4sat.core.domain.model.SatStatus
import com.rtbishop.look4sat.core.domain.repository.IAmSatRepository
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

const val AMSAT_REPORT_HEARD = "Heard"
const val AMSAT_REPORT_TELEMETRY_ONLY = "Telemetry Only"
const val AMSAT_REPORT_NOT_HEARD = "Not Heard"
const val AMSAT_REPORT_CREW_ACTIVE = "Crew Active"

const val UPLOAD_ERROR_CALLSIGN_REQUIRED = "callsign_required"
const val UPLOAD_ERROR_GRID_INVALID = "grid_invalid"
const val UPLOAD_MESSAGE_SUCCESS = "success"

data class AmSatUploadUiState(
    val isExpanded: Boolean = false,
    val selectedReport: String = AMSAT_REPORT_HEARD,
    val callsign: String = "",
    val gridSquare: String = "",
    val isSubmitting: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

data class SatStatusUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val statuses: List<SatStatus> = emptyList(),
    val reports: Map<String, SatReport> = emptyMap(),
    val fetchedAtUtcMs: Long = 0L,
    val error: String? = null,
    val upload: AmSatUploadUiState = AmSatUploadUiState()
)

class SatStatusViewModel(
    private val amSatRepo: IAmSatRepository,
    private val settingsRepo: ISettingsRepo
) : ViewModel() {

    private val _uiState = MutableStateFlow(SatStatusUiState(isLoading = true))
    val uiState: StateFlow<SatStatusUiState> = _uiState

    init {
        fetch()
    }

    fun fetch() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val page = amSatRepo.fetchStatus()
                if (page != null && page.statuses.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            statuses = page.statuses,
                            reports = page.reports,
                            fetchedAtUtcMs = page.fetchedAtUtcMs
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, isRefreshing = false, error = "fetch_failed")
                    }
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, error = exception.message ?: "fetch_failed")
                }
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            try {
                val page = amSatRepo.fetchStatus()
                if (page != null && page.statuses.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            statuses = page.statuses,
                            reports = page.reports,
                            fetchedAtUtcMs = page.fetchedAtUtcMs
                        )
                    }
                } else {
                    _uiState.update { it.copy(isRefreshing = false, error = "fetch_failed") }
                }
            } catch (exception: Exception) {
                _uiState.update { it.copy(isRefreshing = false, error = exception.message ?: "fetch_failed") }
            }
        }
    }

    fun toggleUploadPanel() {
        val storedCallsign = settingsRepo.getAmSatCallsign()
        val defaultGrid = defaultGridSquare()
        _uiState.update { state ->
            val upload = state.upload
            val expanded = !upload.isExpanded
            state.copy(
                upload = if (expanded) {
                    upload.copy(
                        isExpanded = true,
                        callsign = upload.callsign.ifBlank { storedCallsign },
                        gridSquare = upload.gridSquare.ifBlank { defaultGrid },
                        message = null,
                        error = null
                    )
                } else {
                    upload.copy(isExpanded = false, message = null, error = null)
                }
            )
        }
    }

    fun collapseUploadPanel() {
        _uiState.update { it.copy(upload = it.upload.copy(isExpanded = false, message = null, error = null)) }
    }

    fun setUploadReport(report: String) {
        _uiState.update { it.copy(upload = it.upload.copy(selectedReport = report, message = null, error = null)) }
    }

    fun setUploadCallsign(callsign: String) {
        val normalized = callsign.trim().uppercase(Locale.US)
        settingsRepo.setAmSatCallsign(normalized)
        _uiState.update { it.copy(upload = it.upload.copy(callsign = normalized, message = null, error = null)) }
    }

    fun setUploadGrid(grid: String) {
        val normalized = grid.trim().uppercase(Locale.US)
        _uiState.update { it.copy(upload = it.upload.copy(gridSquare = normalized, message = null, error = null)) }
    }

    fun submitReport(satelliteName: String) {
        val upload = _uiState.value.upload
        if (upload.isSubmitting) return
        val callsign = upload.callsign.trim().uppercase(Locale.US)
        val grid = upload.gridSquare.trim().uppercase(Locale.US)
        when {
            callsign.isBlank() -> {
                _uiState.update { it.copy(upload = it.upload.copy(error = UPLOAD_ERROR_CALLSIGN_REQUIRED, message = null)) }
                return
            }
            grid.isNotBlank() && !MAIDENHEAD_GRID.matches(grid) -> {
                _uiState.update { it.copy(upload = it.upload.copy(error = UPLOAD_ERROR_GRID_INVALID, message = null)) }
                return
            }
        }

        settingsRepo.setAmSatCallsign(callsign)
        _uiState.update { it.copy(upload = it.upload.copy(isSubmitting = true, error = null, message = null)) }
        viewModelScope.launch {
            val result = amSatRepo.submitReport(
                AmSatReportSubmission(
                    name = satelliteName,
                    report = upload.selectedReport,
                    callsign = callsign,
                    gridSquare = grid,
                    reportedAtUtcMillis = System.currentTimeMillis()
                )
            )
            if (result.success) {
                _uiState.update {
                    it.copy(
                        upload = it.upload.copy(
                            isSubmitting = false,
                            callsign = callsign,
                            gridSquare = grid,
                            message = UPLOAD_MESSAGE_SUCCESS,
                            error = null
                        )
                    )
                }
                refresh()
            } else {
                _uiState.update {
                    it.copy(
                        upload = it.upload.copy(
                            isSubmitting = false,
                            message = null,
                            error = result.message.ifBlank { "Upload failed" }
                        )
                    )
                }
            }
        }
    }

    private fun defaultGridSquare(): String {
        val grid = settingsRepo.stationPosition.value.qthLocator.trim().uppercase(Locale.US)
        return grid.takeUnless { it == "JJ00AA" }.orEmpty()
    }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                SatStatusViewModel(container.amSatRepo, container.settingsRepo)
            }
        }
    }
}

private val MAIDENHEAD_GRID = Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?$")
