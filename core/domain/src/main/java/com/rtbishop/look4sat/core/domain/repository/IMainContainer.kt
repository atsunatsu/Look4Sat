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
package com.rtbishop.look4sat.core.domain.repository
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.model.ReleaseEdition
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.audio.IAudioHub
import com.rtbishop.look4sat.core.domain.logbook.IQsoRepository
import com.rtbishop.look4sat.core.domain.ft4.IFt4Service
import com.rtbishop.look4sat.core.domain.ft4.IFt4AudioTransmitter
import com.rtbishop.look4sat.core.domain.ft4.IFt4TransmitCoordinator
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import com.rtbishop.look4sat.core.domain.time.ITimeSynchronizationService
import com.rtbishop.look4sat.core.domain.usecase.IAddToCalendar
import com.rtbishop.look4sat.core.domain.usecase.IAudioCapture
import com.rtbishop.look4sat.core.domain.usecase.ISaveImage
import com.rtbishop.look4sat.core.domain.usecase.IShowToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface IMainContainer {
    val appScope: CoroutineScope

    /**
     * Which edition this install is. The default matches the frozen basic container;
     * the jsuenhanced container (its own source set) overrides it. Used by the in-app
     * edition switcher on the update page, which installs the other edition over the
     * same applicationId and versionCode.
     */
    val edition: ReleaseEdition get() = ReleaseEdition.BASIC
    val settingsRepo: ISettingsRepo
    val selectionRepo: ISelectionRepo
    val satelliteRepo: ISatelliteRepo
    val databaseRepo: IDatabaseRepo
    val wavelogUploadRepository: IWavelogUploadRepository
    val wavelogSyncRepository: IWavelogSyncRepository
    val qsoRepository: IQsoRepository
    val lotwUploadRepository: ILoTWUploadRepository
    val amSatRepo: IAmSatRepository
    /** Shared audio hub: the CW panel listens here in both editions. */
    val audioHub: IAudioHub
    // Enhanced-edition (jsuenhanced) services. Declared here so shared code can address
    // them, but only the jsuenhanced container instantiates them. The basic container
    // keeps the defaults below and never touches these — they are deliberately loud
    // instead of returning a silently wrong value.
    val ft4Service: IFt4Service get() = error(ENHANCED_ONLY)
    val ft4AudioTransmitter: IFt4AudioTransmitter get() = error(ENHANCED_ONLY)
    val ft4TransmitCoordinator: IFt4TransmitCoordinator get() = error(ENHANCED_ONLY)
    val disciplinedClock: IDisciplinedClock get() = error(ENHANCED_ONLY)
    val timeSynchronizationService: ITimeSynchronizationService get() = error(ENHANCED_ONLY)
    val updateRepo: IUpdateRepository
    val lotwRepo: ILoTWRepository
    val radioTrackingService: IRadioTrackingService
    val rotatorTrackingService: IRotatorTrackingService get() = error(ENHANCED_ONLY)
    val controlDiagnostics: IControlDiagnostics get() = error(ENHANCED_ONLY)
    val locationRepo: ILocationRepo
    val mutualPassData: StateFlow<MutualPassData>
    /** Grid Finder → LoTW station page prefill, consumed once by that page. */
    val pendingLoTWStationGrid: StateFlow<String?>
    fun setMutualPassData(data: MutualPassData)
    fun setPendingLoTWStationGrid(grid: String?)
    fun provideAddToCalendar(): IAddToCalendar
    fun provideShowToast(): IShowToast
    fun provideBluetoothReporter(): IReporter
    fun provideNetworkReporter(): IReporter
    fun provideSensorsRepo(): ISensorsRepo
    fun provideSaveImage(): ISaveImage
    // Legacy basic-edition hooks kept for the frozen 4.4.8 code path (the jsuenhanced
    // container serves radios through the extended tracking service instead).
    fun provideTxRadioController(): IRadioController = error(BASIC_ONLY)
    fun provideRxRadioController(): IRadioController = error(BASIC_ONLY)
    fun provideAudioCapture(): IAudioCapture = error(BASIC_ONLY)
}

private const val ENHANCED_ONLY = "Available in the jsuenhanced edition only"
private const val BASIC_ONLY = "Provided by the basic edition container only"

data class MutualPassData(
    val samples: List<Pair<Long, Pair<Double, Double>>> = emptyList(),
    val trackSamples: List<TrackSampleData> = emptyList(),
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val maxElev: Double = 10.0,
    val labelA: String = "你",
    val labelB: String = "友台"
)

/** Minimal track sample for cross-module sharing (angles in degrees). */
data class TrackSampleData(
    val time: Long = 0L,
    val azimuthA: Double,
    val elevationA: Double,
    val azimuthB: Double,
    val elevationB: Double
)

interface IContainerProvider {
    fun getMainContainer(): IMainContainer
}
