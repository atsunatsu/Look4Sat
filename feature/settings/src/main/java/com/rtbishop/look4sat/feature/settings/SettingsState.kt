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

import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.LatestRelease
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.CompassAccuracy
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import java.io.File

data class PositionSettings(
    val isUpdating: Boolean, val stationPos: GeoPos, val messageResId: Int
)

data class DataSettings(
    val isUpdating: Boolean,
    val entriesTotal: Int,
    val radiosTotal: Int,
    val timestamp: Long
)

data class UpdateCheckerState(
    val isChecking: Boolean = false,
    val release: LatestRelease? = null,
    val hasUpdate: Boolean = false,
    val isDownloading: Boolean = false,
    val apkFile: File? = null,
    val errorResId: Int? = null
)

data class SettingsState(
    val appVersionName: String,
    val positionSettings: PositionSettings,
    val dataSettings: DataSettings,
    val otherSettings: OtherSettings,
    val rcSettings: RCSettings,
    val radioControlSettings: RadioControlSettings,
    val radioTrackingState: com.rtbishop.look4sat.core.domain.repository.RadioTrackingState = com.rtbishop.look4sat.core.domain.repository.RadioTrackingState(),
    val radioProfiles: com.rtbishop.look4sat.core.domain.repository.RadioProfileManagerState = com.rtbishop.look4sat.core.domain.repository.RadioProfileManagerState(),
    val radioProfilesExport: String? = null,
    val controlDiagnosticCount: Int = 0,
    val controlDiagnosticsExportText: String? = null,
    val dataSourcesSettings: DataSourcesSettings,
    val dataSourcesStatus: Map<String, Int> = emptyMap(),
    val workedGridsCount: Int = 0,
    /** Wavelog configuration (one block; LoTW and Wavelog are mutually exclusive). */
    val wavelogUploadSettings: WavelogUploadSettings = WavelogUploadSettings(),
    val wavelogUploadStations: List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo> = emptyList(),
    val wavelogUploadRights: String = "",
    val wavelogUploadProbeBusy: Boolean = false,
    /** Config-block feedback (probe failures, sync results); also shown inside the config dialog. */
    val wavelogUploadMessage: String? = null,
    /** Wavelog sync (download of QSOs + grids). */
    val wavelogSyncing: Boolean = false,
    /** Epoch ms of the last successful Wavelog sync (0 = never). */
    val wavelogLastSyncEpochMs: Long = 0L,
    /** Mode-switch guard: a change that would hand the logbook over to Wavelog is
     *  waiting for the operator's confirmation (LoTW is configured). */
    val wavelogSwitchWarning: Boolean = false,
    /** Logbook 台址 selector: selected Wavelog station id (null = 全部). */
    val logbookStationFilter: String? = null,
    /** Wavelog-mode logbook upload: prepared batch awaiting confirmation. */
    val logbookWavelogPreview: com.rtbishop.look4sat.core.domain.repository.WavelogUploadPreview? = null,
    val lotwSettings: com.rtbishop.look4sat.core.domain.model.LoTWSettings = com.rtbishop.look4sat.core.domain.model.LoTWSettings(),
    val lotwSyncing: Boolean = false,
    val lotwSyncMode: LoTWSyncMode? = null,
    val lotwProgress: com.rtbishop.look4sat.core.domain.repository.LoTWProgress? = null,
    val lotwError: LoTWError? = null,
    /** Epoch ms of the last successful LoTW sync (0 = never) — shown like the ephemeris update time. */
    val lotwLastSyncEpochMs: Long = 0L,
    /** Logbook (QSO records + LoTW confirmations), newest first. */
    val logbookRecords: List<com.rtbishop.look4sat.core.domain.logbook.QsoRecord> = emptyList(),
    /** ARRL satellite names (config.tq6) — the only names a record may be signed with. */
    val satelliteCatalog: List<String> = emptyList(),
    /** Imported LoTW upload certificate (null when none). */
    val lotwCertificate: com.rtbishop.look4sat.core.domain.repository.LoTWCertificate? = null,
    /** LoTW upload station location (null when unset). */
    val lotwStation: com.rtbishop.look4sat.core.domain.repository.LoTWStation? = null,
    /** Region-field options and national zonemap for the certificate's DXCC entity. */
    val lotwStationMeta: com.rtbishop.look4sat.core.domain.repository.LoTWStationMeta? = null,
    val lotwUploadBusy: Boolean = false,
    /** Last certificate import outcome; shown inside the upload config dialog. */
    val lotwUploadError: LoTWUploadError? = null,
    /** Parser detail for FORMAT errors (e.g. the unsupported algorithm name). */
    val lotwUploadErrorDetail: String = "",
    /** One-click logbook upload: prepared preview awaiting confirmation. */
    val logbookPreview: com.rtbishop.look4sat.core.domain.repository.LoTWUploadPreview? = null,
    /** Roaming guard: the current position grid is outside the station grids the prepared
     *  batch would be signed with — shown as a dialog before the preview opens. */
    val logbookPositionWarning: com.rtbishop.look4sat.core.domain.repository.LoTWPositionWarning? = null,
    val logbookUploadBusy: Boolean = false,
    /** User-facing upload message shown inside the logbook dialog ("" = none). */
    val logbookUploadMessage: String = "",
    /** Logbook multi-select mode (entered by long-pressing a row); checked records can be
     *  re-uploaded so a corrected station location updates them on LoTW (resubmit). */
    val logbookSelectionMode: Boolean = false,
    val logbookSelectedIds: Set<Long> = emptySet(),
    /** 指南针校准精度等级 (校准对话框进度条). */
    val compassAccuracy: CompassAccuracy = CompassAccuracy.UNRELIABLE,
    /** 校正后航向(度, 含磁偏角+手动偏置), 校准对话框实时显示. */
    val compassHeadingDegrees: Float = 0f,
    val updateChecker: UpdateCheckerState = UpdateCheckerState()
)

/** LoTW sync failure, kept as a translatable code until the UI renders it. */
sealed interface LoTWError {
    data object NotConfigured : LoTWError
    data object BadCredentials : LoTWError
    data object RateLimited : LoTWError
    data object Timeout : LoTWError
    data class Network(val detail: String) : LoTWError
}

enum class LoTWUploadError { PASSWORD, INVALID_FILE, EXPIRED, FORMAT, UNKNOWN }

sealed interface SettingsAction {
    data object RefreshRadioProfiles : SettingsAction
    data class DeleteRadioProfile(val slot: Int) : SettingsAction
    data class ImportRadioProfiles(val json: String) : SettingsAction
    data class RestoreRadioProfiles(val overwrite: Set<String>) : SettingsAction
    data object CancelRadioProfileOperation : SettingsAction
    data object ExportRadioProfiles : SettingsAction
    data object ConsumeRadioProfilesExport : SettingsAction
    data class SetRadioProfileAlias(val key: String, val alias: String) : SettingsAction
    data object ExportControlDiagnostics : SettingsAction
    data object ClearControlDiagnostics : SettingsAction
    data object ConsumeControlDiagnosticsExport : SettingsAction
    // Position
    data object SetGpsPosition : SettingsAction
    data class SetGeoPosition(val latitude: Double, val longitude: Double) : SettingsAction
    data class SetQthPosition(val locator: String) : SettingsAction
    data object DismissPosMessages : SettingsAction

    // Data
    data object UpdateFromWeb : SettingsAction
    data class UpdateTLEFromFile(val uri: String, val invalidFileMessage: String) : SettingsAction
    data class UpdateTransceiversFromFile(val uri: String, val invalidFileMessage: String) : SettingsAction
    data object ClearAllData : SettingsAction

    // Toggles
    data class ToggleUtc(val value: Boolean) : SettingsAction
    data class ToggleUpdate(val value: Boolean) : SettingsAction
    data class ToggleAutoLotwSync(val value: Boolean) : SettingsAction
    data class ToggleSweep(val value: Boolean) : SettingsAction
    data class ToggleSensor(val value: Boolean) : SettingsAction
    data object StartCompassCalibration : SettingsAction
    data object StopCompassCalibration : SettingsAction
    data class SetCompassOffset(val degrees: Float) : SettingsAction
    data class ToggleLightTheme(val value: Boolean) : SettingsAction
    data class ToggleNightMode(val value: Boolean) : SettingsAction
    data class UpdateMapSettings(val mapSource: String, val tiandituKey: String) : SettingsAction

    // Remote control
    data class UpdateRC(val settings: RCSettings) : SettingsAction
    data class UpdateRadioControl(val settings: RadioControlSettings) : SettingsAction

    // Data sources
    data class UpdateDataSources(val settings: DataSourcesSettings) : SettingsAction

    // Wavelog configuration (single block; LoTW and Wavelog are mutually exclusive)
    data class UpdateWavelogUpload(val settings: WavelogUploadSettings) : SettingsAction
    data class FetchWavelogUploadStations(val url: String, val apiKey: String) : SettingsAction
    data class SelectWavelogUploadStation(val station: com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo) : SettingsAction
    /** Download QSOs (+ grids) from Wavelog. */
    data class SyncWavelog(val mode: com.rtbishop.look4sat.core.domain.repository.WavelogSyncMode) : SettingsAction
    /** Confirmed the LoTW→Wavelog mode switch warning. */
    data object ConfirmWavelogSwitch : SettingsAction
    /** Declined the switch warning — nothing changes. */
    data object CancelWavelogSwitch : SettingsAction
    /** Wipe the Wavelog config (back to LoTW mode). */
    data object ClearWavelogConfig : SettingsAction
    /** Logbook 台址 selector choice (null = 全部). */
    data class SetLogbookStationFilter(val stationId: String?) : SettingsAction

    // LoTW confirmed grids
    data class UpdateLoTW(val settings: com.rtbishop.look4sat.core.domain.model.LoTWSettings) : SettingsAction
    data class SyncLoTWGrids(
        val settings: com.rtbishop.look4sat.core.domain.model.LoTWSettings,
        val mode: LoTWSyncMode
    ) : SettingsAction
    /** Abort an in-flight LoTW sync (wrong button / changed mind). */
    data object CancelLoTWSync : SettingsAction

    // Logbook (QSO records + LoTW confirmations)
    data object RefreshLogbook : SettingsAction
    data class DeleteLogbookRecord(val id: Long) : SettingsAction
    /** Persist an edited record (frequency/callsign/time/…) from the logbook dialog. */
    data class UpdateLogbookRecord(val record: com.rtbishop.look4sat.core.domain.logbook.QsoRecord) : SettingsAction
    data object PrepareLogbookUpload : SettingsAction
    data object ConfirmLogbookUpload : SettingsAction
    data object DismissLogbookPreview : SettingsAction
    data object RewriteLogbookCallsigns : SettingsAction
    /** The operator acknowledged the position mismatch and wants to upload anyway. */
    data object IgnoreLogbookPositionWarning : SettingsAction
    /** The operator chose to fix the station location first; the preview is discarded. */
    data object AbandonLogbookForGridFix : SettingsAction
    /** Long-press on a row: enter selection mode with that record checked. */
    data class StartLogbookSelection(val id: Long) : SettingsAction
    data class ToggleLogbookSelection(val id: Long) : SettingsAction
    data object ExitLogbookSelection : SettingsAction
    /** Re-upload the checked records — uploaded/confirmed rows included (resubmit). */
    data object ResubmitSelectedLogbook : SettingsAction
    data object ClearLogbookMessage : SettingsAction

    // LoTW upload configuration (certificate + station)
    data object LoadLoTWUploadStatus : SettingsAction
    data class ImportLoTWCertificate(val bytes: ByteArray, val password: CharArray) : SettingsAction
    /** Pre-import .p12 parse (not persisted) so the region field appears before confirming. */
    data class PreviewLoTWCertificate(val bytes: ByteArray, val password: CharArray) : SettingsAction
    data object RemoveLoTWCertificate : SettingsAction
    data class SaveLoTWStation(val station: com.rtbishop.look4sat.core.domain.repository.LoTWStation) : SettingsAction

    // Update checker
    data object CheckForUpdate : SettingsAction
    data object DownloadUpdate : SettingsAction
    data object ConsumeDownloadedApk : SettingsAction

    // System
    data class ShowToast(val message: String) : SettingsAction
}
