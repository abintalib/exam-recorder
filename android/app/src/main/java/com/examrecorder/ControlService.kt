package com.examrecorder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Always-on background service. Every 2s it reports status to the admin and
 * makes the tablet match what the admin wants (record / idle).
 */
class ControlService : Service() {
    private val loop = Executors.newSingleThreadScheduledExecutor()
    private var lastStartAttempt = 0L

    override fun onCreate() {
        super.onCreate()
        val n = App.notification(this, "Ready")
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        loop.scheduleWithFixedDelay({ tick() }, 0, 2, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        loop.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tick() {
        Uploader.kick(this)
        val desired = try {
            Api.heartbeat(Prefs.deviceId(this), statusJson())
        } catch (e: Exception) {
            Log.w(TAG, "Server unreachable: ${e.message}")
            return // offline: keep doing whatever we're doing
        }

        when {
            desired.action == "record" && !RecordingService.isRecording -> requestStart(desired)
            desired.action == "idle" && RecordingService.isRecording -> RecordingService.stop()
        }
    }

    /** Bring our invisible TriggerActivity to the front so Android allows the camera to start. */
    private fun requestStart(d: Api.Desired) {
        val now = System.currentTimeMillis()
        if (now - lastStartAttempt < RETRY_MS) return
        lastStartAttempt = now

        if (!Settings.canDrawOverlays(this)) {
            Status.lastError = "Missing 'Display over other apps' permission"
            return
        }
        startActivity(Intent(this, TriggerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RecordingService.EXTRA_SESSION, d.sessionId)
            putExtra(RecordingService.EXTRA_STUDENT, d.studentId)
            putExtra(TriggerActivity.EXTRA_EXAM_PACKAGE, d.examPackage)
        })
    }

    private fun statusJson(): JSONObject {
        val pending = Uploader.pending(this).size

        val status = when {
            RecordingService.isRecording -> "recording"
            pending > 0 -> "uploading"
            Status.lastError != null -> "error"
            Status.lastUpload != null -> "done"
            else -> "idle"
        }
        return JSONObject()
            .put("code", Prefs.pairCode(this))
            .put("status", status)
            .put("detail", Status.lastError ?: "")
            .put("pendingUploads", pending)
            .put("lastUpload", Status.lastUpload)
    }

    companion object {
        private const val TAG = "ControlService"
        private const val NOTIF_ID = 1
        private const val RETRY_MS = 15_000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ControlService::class.java))
        }
    }
}
