// SPDX-License-Identifier: GPL-3.0-or-later
package com.rtbishop.look4sat

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.R as CoreR
import kotlinx.coroutines.*

class RecordingForegroundService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val recording get()=(applicationContext as IContainerProvider).getMainContainer().recordingService
    private var job: Job?=null
    private var notificationJob: Job?=null
    private var wakeLock: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?): IBinder?=null
    @android.annotation.SuppressLint("MissingPermission") // UI requests notification consent; FGS still runs after denial.
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==STOP) {
            scope.launch {recording.stop();if(!recording.state.value.active) stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId)}
            return START_NOT_STICKY
        }
        val previous=job
        if(previous?.isCompleted==false) {
            if(recording.state.value.phase==RecordingPhase.PREPARING) scope.launch {
                previous.join();if(job===previous) job=null;onStartCommand(intent,flags,startId)
            }
            return START_NOT_STICKY
        }
        val id=intent?.getStringExtra("source") ?: run {stopSelf();return START_NOT_STICKY}
        val source=recording.sources().firstOrNull {it.id==id} ?: run {recording.failStart("Receive source is unavailable");stopSelf();return START_NOT_STICKY}
        if(Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,getString(CoreR.string.recording_title),NotificationManager.IMPORTANCE_LOW))
        try {
            val type=if(source.kind in listOf(RecordingSourceKind.MICROPHONE,RecordingSourceKind.DEVICE))
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            ServiceCompat.startForeground(this,ID,notification(),if(Build.VERSION.SDK_INT>=30) type else 0)
            acquireRecordingWakeLock()
        } catch(error:Exception) {
            recording.failStart(error.message ?: "Could not start background recording")
            scope.launch {recording.stop();stopSelf()};return START_NOT_STICKY
        }
        val request=RecordingRequest(id,intent.getStringExtra("satellite").orEmpty(),intent.getLongExtra("aos",0).takeIf {it!=0L},
            if(intent.hasExtra("maxElevation")) intent.getDoubleExtra("maxElevation",0.0) else null)
        notificationJob=scope.launch {
            while(isActive) {delay(1000);runCatching {getSystemService(NotificationManager::class.java).notify(ID,notification())}}
        }
        job=scope.launch {
            try {recording.runRecording(request)}
            finally {
                notificationJob?.cancel();releaseWakeLock()
                if(!recording.state.value.active) stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val state=recording.state.value
        val launch=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,RecordingForegroundService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(CoreR.drawable.ic_radio_tower)
            .setContentTitle(getString(CoreR.string.recording_title))
            .setContentText("${state.source?.name.orEmpty()} · ${state.durationMillis/60000}:${(state.durationMillis/1000%60).toString().padStart(2,'0')}")
            .setContentIntent(launch).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0,getString(CoreR.string.recording_stop),stop).build()
    }
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireRecordingWakeLock() {
        // Explicit user recording can span many segments. The service owns the
        // lock, releases it on all completion paths, and is START_NOT_STICKY.
        releaseWakeLock()
        wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Look4Sat:Recording").apply {
            setReferenceCounted(false);acquire()
        }
    }
    private fun releaseWakeLock() {wakeLock?.let {if(it.isHeld) it.release()};wakeLock=null}
    override fun onDestroy() {releaseWakeLock();scope.cancel();super.onDestroy()}
    companion object {
        private const val ID=4107
        private const val CHANNEL="receive-recording"
        private const val STOP="recording.stop"
        fun startIntent(context: Context,request: RecordingRequest)=Intent(context,RecordingForegroundService::class.java).apply {
            putExtra("source",request.sourceId);putExtra("satellite",request.satellite);putExtra("aos",request.passAos ?: 0)
            request.maxElevation?.let {putExtra("maxElevation",it)}
        }
    }
}
