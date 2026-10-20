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
package com.rtbishop.look4sat.core.data.injection

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.rtbishop.look4sat.core.data.database.Look4SatDb
import com.rtbishop.look4sat.core.data.database.MIGRATION_1_2
import com.rtbishop.look4sat.core.data.database.MIGRATION_2_3
import com.rtbishop.look4sat.core.data.database.MIGRATION_QSO_1_2
import com.rtbishop.look4sat.core.data.database.MIGRATION_QSO_2_3
import com.rtbishop.look4sat.core.data.database.MIGRATION_QSO_3_4
import com.rtbishop.look4sat.core.data.database.MIGRATION_QSO_4_5
import com.rtbishop.look4sat.core.data.database.QsoDatabase
import com.rtbishop.look4sat.core.data.framework.BluetoothReporter
import com.rtbishop.look4sat.core.data.framework.NetworkReporter
import com.rtbishop.look4sat.core.data.framework.RadioTrackingService
import com.rtbishop.look4sat.core.data.lotw.LoTWUploadRepository
import com.rtbishop.look4sat.core.data.repository.AmSatRepository
import com.rtbishop.look4sat.core.data.repository.DatabaseRepo
import com.rtbishop.look4sat.core.data.repository.QsoRepository
import com.rtbishop.look4sat.core.data.repository.SatelliteRepo
import com.rtbishop.look4sat.core.data.repository.SelectionRepo
import com.rtbishop.look4sat.core.data.repository.SensorsRepo
import com.rtbishop.look4sat.core.data.repository.SettingsRepo
import com.rtbishop.look4sat.core.data.repository.UpdateRepository
import com.rtbishop.look4sat.core.data.repository.LoTWRepository
import com.rtbishop.look4sat.core.data.repository.LocationRepo

import com.rtbishop.look4sat.core.data.repository.WavelogUploadRepository
import com.rtbishop.look4sat.core.data.repository.WavelogSyncRepository
import com.rtbishop.look4sat.core.data.source.LocalSource
import com.rtbishop.look4sat.core.data.source.RemoteSource
import com.rtbishop.look4sat.core.data.usecase.AddToCalendar
import com.rtbishop.look4sat.core.data.usecase.AudioCapture
import com.rtbishop.look4sat.core.data.usecase.SaveImage
import com.rtbishop.look4sat.core.data.usecase.ShowToast
import com.rtbishop.look4sat.core.domain.logbook.IQsoRepository
import com.rtbishop.look4sat.core.domain.repository.IDatabaseRepo
import com.rtbishop.look4sat.core.domain.repository.ILoTWUploadRepository
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.IRadioTrackingService
import com.rtbishop.look4sat.core.domain.repository.IReporter
import com.rtbishop.look4sat.core.domain.repository.ILoTWRepository
import com.rtbishop.look4sat.core.domain.repository.ILocationRepo
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISelectionRepo
import com.rtbishop.look4sat.core.domain.repository.ISensorsRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo

import com.rtbishop.look4sat.core.domain.repository.IWavelogUploadRepository
import com.rtbishop.look4sat.core.domain.repository.IWavelogSyncRepository
import com.rtbishop.look4sat.core.domain.repository.MutualPassData
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import com.rtbishop.look4sat.core.domain.usecase.IAddToCalendar
import com.rtbishop.look4sat.core.domain.usecase.IAudioCapture
import com.rtbishop.look4sat.core.domain.usecase.ISaveImage
import com.rtbishop.look4sat.core.domain.usecase.IShowToast
import com.rtbishop.look4sat.core.domain.utility.DataParser
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainContainer(private val context: Context) : IMainContainer {

    private val localSource = provideLocalSource()
    private val remoteSource = provideRemoteSource()
    private val mainHandler = CoroutineExceptionHandler { _, error -> println("MainHandler: $error") }
    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + mainHandler)
    override val settingsRepo = provideSettingsRepo()
    override val controlDiagnostics = com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer()
    override val audioHub: com.rtbishop.look4sat.core.domain.audio.IAudioHub by lazy {
        com.rtbishop.look4sat.core.data.usecase.SharedAudioHub(context, appScope)
    }
    override val selectionRepo = provideSelectionRepo()
    override val satelliteRepo = provideSatelliteRepo()
    override val databaseRepo = provideDatabaseRepo()
    override val amSatRepo by lazy { AmSatRepository(remoteSource, appScope) }
    override val updateRepo by lazy { UpdateRepository(remoteSource) }

    override val wavelogUploadRepository: IWavelogUploadRepository by lazy { WavelogUploadRepository() }
    override val wavelogSyncRepository: IWavelogSyncRepository by lazy { WavelogSyncRepository() }
    override val lotwRepo: ILoTWRepository by lazy { LoTWRepository() }
    override val qsoRepository: IQsoRepository by lazy {
        val database = Room.databaseBuilder(context, QsoDatabase::class.java, "Look4SatQsoDB")
            .addMigrations(MIGRATION_QSO_1_2, MIGRATION_QSO_2_3, MIGRATION_QSO_3_4)
            .addMigrations(MIGRATION_QSO_4_5)
            .build()
        QsoRepository(database.qsoDao(), Dispatchers.IO)
    }
    override val lotwUploadRepository: ILoTWUploadRepository by lazy { LoTWUploadRepository(context) }
    override val radioTrackingService: IRadioTrackingService by lazy {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val transportFactory = com.rtbishop.look4sat.core.data.framework.AndroidRadioTransportFactory(context, manager, controlDiagnostics)
        RadioTrackingService(appScope, manager, satelliteRepo, settingsRepo,
            transportFactory = transportFactory::create, diagnostics = controlDiagnostics,
            n76AudioFactory = { address -> com.rtbishop.look4sat.core.data.framework.N76AudioSession(
                com.rtbishop.look4sat.core.data.framework.BluetoothSppRadioTransport(manager,address,controlDiagnostics,
                    java.util.UUID.fromString(com.rtbishop.look4sat.core.data.framework.N76AudioProtocol.SERVICE_UUID)),
                com.rtbishop.look4sat.core.data.framework.AndroidN76AudioIo(context,audioHub) {settingsRepo.radioControlSettings.value.n76Options},
                appScope,controlDiagnostics)
            },
            n76RecordingFactory = {com.rtbishop.look4sat.core.data.framework.AndroidN76Recording(context,
                com.rtbishop.look4sat.core.data.framework.AndroidN76AudioIo(context,audioHub) {settingsRepo.radioControlSettings.value.n76Options},
                appScope,controlDiagnostics) {settingsRepo.radioControlSettings.value.n76Options}}
        )
    }
    override val locationRepo: ILocationRepo by lazy {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        LocationRepo(manager) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private val _mutualPassData = MutableStateFlow(MutualPassData())
    override val mutualPassData: StateFlow<MutualPassData> = _mutualPassData.asStateFlow()
    private val _pendingLoTWStationGrid = MutableStateFlow<String?>(null)
    override val pendingLoTWStationGrid: StateFlow<String?> = _pendingLoTWStationGrid.asStateFlow()

    override fun setMutualPassData(data: MutualPassData) {
        _mutualPassData.value = data
    }
    override fun setPendingLoTWStationGrid(grid: String?) {
        _pendingLoTWStationGrid.value = grid
    }

    override fun provideAddToCalendar(): IAddToCalendar = AddToCalendar(context)

    override fun provideShowToast(): IShowToast = ShowToast(context)

    override fun provideAudioCapture(): IAudioCapture = AudioCapture(audioHub)

    override fun provideSaveImage(): ISaveImage = SaveImage(context)

    override fun provideBluetoothReporter(): IReporter {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val rc = settingsRepo.rcSettings.value
        return BluetoothReporter(
            manager,
            CoroutineScope(Dispatchers.IO),
            rc.bluetoothRotatorAddress,
            rc.bluetoothFrequencyAddress
        )
    }

    override fun provideNetworkReporter(): IReporter {
        val rc = settingsRepo.rcSettings.value
        return NetworkReporter(
            CoroutineScope(Dispatchers.IO),
            rc.rotatorAddress,
            rc.rotatorPort.toIntOrNull() ?: 0,
            rc.frequencyAddress,
            rc.frequencyPort.toIntOrNull() ?: 0,
            rc.frequencyOffsetHz
        )
    }

    override fun provideSensorsRepo(): ISensorsRepo {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val displayManager = context.getSystemService(DisplayManager::class.java)
        return SensorsRepo(manager, displayManager)
    }

    private fun provideDatabaseRepo(): IDatabaseRepo {
        val dbDispatcher = Dispatchers.Default
        val dataParser = DataParser(dbDispatcher)
        val remoteSource = provideRemoteSource()
        return DatabaseRepo(dbDispatcher, dataParser, localSource, remoteSource, settingsRepo)
    }

    private fun provideLocalSource(): ILocalSource {
        val builder = Room.databaseBuilder(context, Look4SatDb::class.java, "Look4SatDBv400")
        val database = builder
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .fallbackToDestructiveMigration(false)
            .build()
        return LocalSource(database.look4SatDao())
    }

    private fun provideRemoteSource(): IRemoteSource {
        // amsat.org live pages are slow (7-10s+ from some networks); the
        // OkHttp defaults (10s read timeout) made every AMSAT Live update
        // time out, silently keeping stale FM/Linear lists forever.
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
        return RemoteSource(Dispatchers.IO, context.contentResolver, client)
    }

    private fun provideSatelliteRepo(): ISatelliteRepo {
        return SatelliteRepo(Dispatchers.Default, localSource, settingsRepo)
    }

    private fun provideSelectionRepo(): ISelectionRepo {
        return SelectionRepo(Dispatchers.Default, localSource, settingsRepo)
    }

    private fun provideSettingsRepo(): ISettingsRepo {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val appPrefsFileName = "${context.packageName}_preferences"
        val appPreferences = context.getSharedPreferences(appPrefsFileName, Context.MODE_PRIVATE)
        val appVersionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "4.0.4"
        return SettingsRepo(manager, appPreferences, appVersionName)
    }
}
