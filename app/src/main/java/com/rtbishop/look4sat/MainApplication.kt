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
package com.rtbishop.look4sat

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Intent
import androidx.core.content.ContextCompat
import com.rtbishop.look4sat.core.domain.model.LoTWSettings
import com.rtbishop.look4sat.core.domain.repository.ILoTWForegroundSyncLauncher
import com.rtbishop.look4sat.core.domain.repository.LoTWForegroundStatus
import com.rtbishop.look4sat.core.domain.repository.LoTWForegroundSyncStatus
import android.os.Bundle
import com.rtbishop.look4sat.core.data.injection.MainContainer
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import com.rtbishop.look4sat.core.domain.logbook.toConfirmedRecord
import com.rtbishop.look4sat.core.domain.repository.applyLoTWGridResult
import com.rtbishop.look4sat.core.domain.repository.lotwIncrementalSince
import com.rtbishop.look4sat.core.domain.repository.lotwSyncToday
import com.rtbishop.look4sat.core.domain.repository.resolveLoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.shouldAutoSyncLoTW
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainApplication : Application(), IContainerProvider, ILoTWForegroundSyncLauncher,
    com.rtbishop.look4sat.core.domain.audio.IRecordingForegroundLauncher {

    override fun startRecordingForeground(request: com.rtbishop.look4sat.core.domain.audio.RecordingRequest) {
        ContextCompat.startForegroundService(this, RecordingForegroundService.startIntent(this, request))
    }

    private lateinit var container: IMainContainer

    override fun getMainContainer(): IMainContainer = container

    override fun startManualLoTWSync(account: LoTWSettings, mode: LoTWSyncMode): Boolean {
        if (!account.isConfigured || LoTWForegroundSyncStatus.state.value.running) return false
        // Save the just-entered credentials in the existing encrypted settings
        // store. Never put a password or callsign into a service Intent.
        container.settingsRepo.updateLoTWSettings(account)
        LoTWForegroundSyncStatus.publish(LoTWForegroundStatus(running = true, mode = mode,
            message = "Preparing LoTW synchronization"))
        return runCatching {
            ContextCompat.startForegroundService(this, Intent(this, LoTWForegroundSyncService::class.java).apply {
                action = LoTWForegroundSyncService.ACTION_MANUAL
                putExtra(LoTWForegroundSyncService.EXTRA_MODE, mode.name)
            })
            true
        }.getOrElse { e ->
            android.util.Log.w("LoTWForegroundSync", "Unable to start foreground service", e)
            LoTWForegroundSyncStatus.publish(
                LoTWForegroundStatus(message = "Unable to start background LoTW synchronization"))
            false
        }
    }

    override fun cancelManualLoTWSync() {
        startService(Intent(this, LoTWForegroundSyncService::class.java).apply {
            action = LoTWForegroundSyncService.ACTION_CANCEL
        })
    }

    override fun onCreate() {
        super.onCreate()
        container = MainContainer(this)
        clearAmSatCacheOnAppBackground()
        // trigger automatic update every 48 hours
        container.appScope.launch { checkAutoUpdate() }
        // LoTW foreground data-sync starts from the first visible Activity,
        // not Application.onCreate, to respect Android 12+ FGS launch limits.
        // repair logbooks split by the old name/band identity mismatch, independent of
        // whether a sync is due (cheap read + in-memory match on every start)
        container.appScope.launch { runCatching { container.qsoRepository.consolidateConfirmations() } }
        // load satellite data on every app start
        container.appScope.launch { container.satelliteRepo.initRepository() }
    }

    private fun clearAmSatCacheOnAppBackground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivityCount = 0

            override fun onActivityStarted(activity: Activity) {
                if (startedActivityCount == 0) {
                    container.appScope.launch { container.amSatRepo.prefetchStatus() }
                    container.appScope.launch { checkLoTWAutoSync() }
                }
                startedActivityCount += 1
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) container.amSatRepo.clearStatusCache()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private suspend fun checkAutoUpdate(timeNow: Long = System.currentTimeMillis()) {
        if (container.settingsRepo.otherSettings.value.stateOfAutoUpdate) {
            val timeDelta = timeNow - container.settingsRepo.databaseState.value.updateTimestamp
            if (timeDelta > 172_800_000L) { // 48 hours in ms
                val sdf = SimpleDateFormat("d MMM yyyy - HH:mm:ss", Locale.getDefault())
                println("Started periodic data update on ${sdf.format(Date())}")
                container.databaseRepo.updateFromRemote()
            }
        }
    }

    /**
     * Automatic LoTW grid sync, mirroring [checkAutoUpdate]: checked on every
     * app start, but only pulls when due — LoTW credentials configured, the
     * LoTW auto-sync toggle on, at least one prior (manual) sync, and not
     * already synced today (ARRL limits report pulls, ~once a day per account).
     * Incremental when the callsign is unchanged; a callsign change falls back
     * to a full replace so no stale grids from another account linger.
     * Failures are silent and simply retried on the next app start — the manual
     * sync button still surfaces the explicit cause to the user.
     */
    private suspend fun checkLoTWAutoSync(timeNow: Long = System.currentTimeMillis()) {
        val settingsRepo = container.settingsRepo
        if (settingsRepo.wavelogUploadSettings.value.isReady) return
        val account = settingsRepo.lotwSettings.value
        if (!shouldAutoSyncLoTW(
                isConfigured = account.isConfigured,
                autoLotwSyncEnabled = settingsRepo.otherSettings.value.stateOfAutoLotwSync,
                lastSyncDate = settingsRepo.getLastLotwSyncDate(),
                today = lotwSyncToday(timeNow)
            )) return
        // The service repeats the eligibility check inside its own job to
        // avoid a duplicate request after Activity navigation/recreation.
        if (LoTWForegroundSyncStatus.state.value.running) return
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, LoTWForegroundSyncService::class.java).apply {
                action = LoTWForegroundSyncService.ACTION_AUTO
            })
        }.onFailure { android.util.Log.w("LoTWForegroundSync", "Automatic sync could not start", it) }
    }

}
