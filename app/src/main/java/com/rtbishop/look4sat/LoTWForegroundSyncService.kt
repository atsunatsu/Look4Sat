/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.SystemClock
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.LoTWPhase
import com.rtbishop.look4sat.core.domain.repository.LoTWProgress
import com.rtbishop.look4sat.core.domain.repository.LoTWForegroundStatus
import com.rtbishop.look4sat.core.domain.repository.LoTWForegroundSyncStatus
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.applyLoTWGridResult
import com.rtbishop.look4sat.core.domain.repository.lotwIncrementalSince
import com.rtbishop.look4sat.core.domain.repository.lotwSyncToday
import com.rtbishop.look4sat.core.domain.repository.resolveLoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.shouldAutoSyncLoTW
import com.rtbishop.look4sat.core.domain.logbook.toConfirmedRecord
import com.rtbishop.look4sat.core.domain.logbook.mergeDownloadedLoTW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The OS-visible status-bar entry is backed by a data-sync foreground service.
 * It owns the LoTW network request so dismissing the Activity does not cancel it.
 * No callsign or password ever enters Intent extras or notification text.
 */
class LoTWForegroundSyncService : Service() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private var lastNotificationAt = 0L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var currentRun: Long? = null
    private var nextRun = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LoTW synchronization", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            syncJob?.cancel()
            if (currentRun == null) stopSelf()
            return START_NOT_STICKY
        }
        if (currentRun != null) return START_NOT_STICKY
        if (intent?.action !in setOf(ACTION_MANUAL, ACTION_AUTO)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val requested = if (intent?.action == ACTION_MANUAL)
            runCatching { LoTWSyncMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrNull()
        else null
        if (intent?.action == ACTION_MANUAL && requested == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val run = ++nextRun
        currentRun = run
        LoTWForegroundSyncStatus.publish(LoTWForegroundStatus(
            running = true, mode = requested, message = "Preparing LoTW synchronization"))

        // Android requires the initial foreground notification immediately,
        // before the first network or database operation.
        val starting = notification("Preparing LoTW synchronization", null, indeterminate = true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, starting)
        }
        syncJob = work.launch {
            try {
                synchronize(requested, run)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                report(run, "LoTW synchronization failed", null)
                android.util.Log.e("LoTWForegroundSync", "Synchronization failed", e)
            }
        }.also { job ->
            // Completion callbacks also run when cancellation happens before the
            // coroutine body is dispatched; a finally block alone cannot cover it.
            job.invokeOnCompletion { cause ->
                main.post {
                    finish(run, if (cause is kotlinx.coroutines.CancellationException)
                        "LoTW synchronization cancelled" else null)
                }
            }
        }
        // Do not restart an interrupted LoTW download without user intent:
        // a resumed request could violate the report rate limit.
        return START_NOT_STICKY
    }

    private suspend fun synchronize(requested: LoTWSyncMode?, run: Long) {
        val container = (application as IContainerProvider).getMainContainer()
        val settings = container.settingsRepo
        if (settings.wavelogUploadSettings.value.isReady) {
            report(run, "LoTW sync disabled while Wavelog is active", null)
            return
        }
        val account = settings.lotwSettings.value
        val now = System.currentTimeMillis()
        if (!account.isConfigured) {
            report(run, "LoTW credentials are not configured", null)
            return
        }
        if (requested == null && !shouldAutoSyncLoTW(
                account.isConfigured,
                settings.otherSettings.value.stateOfAutoLotwSync,
                settings.getLastLotwSyncDate(),
                lotwSyncToday(now)
            )) return
        val callsign = account.callsign.trim().uppercase()
        val mode = resolveLoTWSyncMode(settings.getLastLotwSyncCallsign(), callsign, requested)
        main.post {
            if (currentRun == run) LoTWForegroundSyncStatus.publish(
                LoTWForegroundSyncStatus.state.value.copy(mode = mode))
        }
        val since = if (mode == LoTWSyncMode.Incremental) lotwIncrementalSince(settings) else ""
        report(run, "Connecting to LoTW", null, indeterminate = true)
        val result = container.lotwRepo.fetchConfirmedGridQsos(
            callsign, account.password, since
        ) { progress ->
            // HTTP download callbacks can occur at high frequency.
            if (SystemClock.elapsedRealtime() - lastNotificationAt >= 500 ||
                progress.phase == LoTWPhase.Connecting) {
                lastNotificationAt = SystemClock.elapsedRealtime()
                report(run,
                    if (progress.phase == LoTWPhase.Downloading) "Downloading LoTW confirmations"
                    else "Connecting to LoTW", progress
                )
            }
        }
        if (result is LoTWResult.Success) {
            report(run, "Saving synchronized contacts", null)
            val confirmed = result.qsos.values.flatten().map { it.toConfirmedRecord(callsign) }
            // Dispatches to source-scoped atomic storage when PR #24 is present.
            // A storage failure escapes before any grid/cursor metadata advances.
            container.qsoRepository.mergeDownloadedLoTW(confirmed, callsign, mode == LoTWSyncMode.Full)
            applyLoTWGridResult(settings, result, mode, callsign, now)
            report(run, "LoTW synchronization complete", null)
        } else {
            report(run, "LoTW synchronization unavailable", null)
        }
    }

    private fun report(run: Long, message: String, progress: LoTWProgress?, indeterminate: Boolean = false) {
        main.post {
            if (currentRun != run) return@post
            LoTWForegroundSyncStatus.publish(
                LoTWForegroundSyncStatus.state.value.copy(progress = progress, message = message))
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(message, progress, indeterminate))
            }
        }
    }

    private fun notification(message: String, progress: LoTWProgress?, indeterminate: Boolean = false): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Look4Sat · LoTW")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                when {
                    progress?.phase == LoTWPhase.Downloading && progress.expectedBytes > 0 ->
                        setProgress(100, (progress.fraction * 100).toInt(), false)
                    indeterminate || progress != null ->
                        setProgress(0, 0, true)
                    // Download ended: parsing / database merge may still be in
                    // progress. Keep the foreground notification, but do not
                    // present that processing phase as an ongoing download.
                    else -> setProgress(0, 0, false)
                }
            }
            .build()
    }

    private fun finish(run: Long, message: String? = null) {
        if (currentRun != run) return
        currentRun = null
        syncJob = null
        LoTWForegroundSyncStatus.publish(LoTWForegroundSyncStatus.state.value.copy(
            running = false, progress = null,
            message = message ?: LoTWForegroundSyncStatus.state.value.message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        syncJob?.cancel()
        currentRun?.let { finish(it, "LoTW synchronization interrupted by Android") } ?: stopSelf()
    }

    override fun onDestroy() {
        val unfinished = currentRun != null
        currentRun = null
        work.cancel()
        if (unfinished) LoTWForegroundSyncStatus.publish(LoTWForegroundSyncStatus.state.value.copy(
            running = false, progress = null, message = "LoTW synchronization interrupted"))
        super.onDestroy()
    }

    companion object {
        const val ACTION_AUTO = "com.rtbishop.look4sat.action.LOTW_AUTO_SYNC"
        const val ACTION_MANUAL = "com.rtbishop.look4sat.action.LOTW_MANUAL_SYNC"
        const val ACTION_CANCEL = "com.rtbishop.look4sat.action.LOTW_CANCEL_SYNC"
        const val EXTRA_MODE = "mode"
        const val CHANNEL_ID = "lotw_background_sync"
        const val NOTIFICATION_ID = 7422
    }
}
