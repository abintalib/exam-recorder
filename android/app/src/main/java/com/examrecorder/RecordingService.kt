package com.examrecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File

/** Records front camera + mic to a local file. On stop, hands the file to the Uploader. */
class RecordingService : LifecycleService() {
    private var recording: Recording? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground()
        val session = intent?.getStringExtra(EXTRA_SESSION)
        val student = intent?.getStringExtra(EXTRA_STUDENT)
        if (recording == null && session != null && student != null) begin(session, student)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startInForeground() {
        val n = App.notification(this, "Exam recording active")
        if (Build.VERSION.SDK_INT >= 30) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasMic()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun begin(session: String, student: String) {
        val main = ContextCompat.getMainExecutor(this)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                    .setTargetVideoEncodingBitRate(1_000_000) // ~1 Mbps: about 0.5 GB per hour
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                val camera = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                    CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(this, camera, capture)

                val file = File(Uploader.dir(this), "${System.currentTimeMillis()}.mp4")
                var pending = recorder.prepareRecording(this, FileOutputOptions.Builder(file).build())
                if (hasMic()) pending = pending.withAudioEnabled()

                acquireWakeLock()
                recording = pending.start(main) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                            Status.lastError = null
                            Status.lastUpload = null
                        }
                        is VideoRecordEvent.Finalize -> finish(event, file, session, student, provider)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not start recording", e)
                Status.lastError = "Camera error: ${e.message}"
                shutdown()
            }
        }, main)
    }

    private fun finish(e: VideoRecordEvent.Finalize, file: File, session: String, student: String, provider: ProcessCameraProvider) {
        if (e.hasError()) {
            Log.w(TAG, "Finalize error ${e.error}", e.cause)
            Status.lastError = "Recording error ${e.error}"
        }
        // Some errors (e.g. camera taken away) still leave a usable file: upload whatever we have.
        if (file.exists() && file.length() > 0) Uploader.markReady(file, student, session) else file.delete()
        provider.unbindAll()
        shutdown()
    }

    private fun shutdown() {
        isRecording = false
        recording = null
        wakeLock?.takeIf { it.isHeld }?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "examrecorder:rec")
            .apply { acquire(6 * 60 * 60 * 1000L) } // safety cap: 6h
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIF_ID = 2
        const val EXTRA_SESSION = "session"
        const val EXTRA_STUDENT = "student"

        @Volatile var isRecording = false
            private set
        @Volatile private var instance: RecordingService? = null

        /** Must be called while our app is on screen (from TriggerActivity). */
        fun start(ctx: Context, session: String, student: String) {
            ctx.startForegroundService(Intent(ctx, RecordingService::class.java)
                .putExtra(EXTRA_SESSION, session).putExtra(EXTRA_STUDENT, student))
        }

        fun stop() {
            instance?.recording?.stop()
        }
    }
}
