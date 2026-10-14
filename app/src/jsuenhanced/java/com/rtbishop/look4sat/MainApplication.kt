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
import android.os.Bundle
import com.rtbishop.look4sat.core.data.injection.MainContainer
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import com.rtbishop.look4sat.core.domain.repository.applyLoTWGridResult
import com.rtbishop.look4sat.core.domain.repository.lotwCursorApi
import com.rtbishop.look4sat.core.domain.repository.lotwSyncToday
import com.rtbishop.look4sat.core.domain.repository.resolveLoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.shouldAutoSyncLoTW
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainApplication : Application(), IContainerProvider {

    private lateinit var container: IMainContainer

    override fun getMainContainer(): IMainContainer = container

    override fun onCreate() {
        super.onCreate()
        container = MainContainer(this)
        clearAmSatCacheOnAppBackground()
        // trigger automatic update every 48 hours
        container.appScope.launch { checkAutoUpdate() }
        // automatic LoTW grid sync on every app start (gated, see checkLoTWAutoSync)
        container.appScope.launch { checkLoTWAutoSync() }
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
        // One-of-two rule: while Wavelog is configured the app uploads/syncs through
        // Wavelog only — the automatic LoTW sync stays off.
        if (settingsRepo.wavelogUploadSettings.value.isReady) return
        val lotwSettings = settingsRepo.lotwSettings.value
        if (!shouldAutoSyncLoTW(
                isConfigured = lotwSettings.isConfigured,
                autoLotwSyncEnabled = settingsRepo.otherSettings.value.stateOfAutoLotwSync,
                lastSyncDate = settingsRepo.getLastLotwSyncDate(),
                today = lotwSyncToday(timeNow)
            )
        ) return
        val callsign = lotwSettings.callsign.trim().uppercase()
        val mode = resolveLoTWSyncMode(settingsRepo.getLastLotwSyncCallsign(), callsign, requested = null)
        val since = if (mode == LoTWSyncMode.Incremental) lotwCursorApi(settingsRepo.getLastLotwSyncDate()) else ""
        println("Started periodic LoTW sync (${mode.name.lowercase()})")
        val result = container.lotwRepo.fetchQsos(callsign, lotwSettings.password, mode, since)
        if (result is LoTWResult.Success) {
            val logbook = container.qsoRepository.mergeLoTW(result.records)
            val count = applyLoTWGridResult(settingsRepo, result, mode, callsign, timeNow)
            println(
                "Periodic LoTW sync finished: $count worked grids, " +
                    "${logbook.imported} logs added, ${logbook.updated} updated"
            )
        } else {
            println("Periodic LoTW sync skipped (${result::class.simpleName})")
        }
    }
}
