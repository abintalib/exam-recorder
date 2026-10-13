package com.examrecorder

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible screen. Android only lets the camera start while our app is on screen,
 * so this pops up for ~1s, starts recording, then hands the screen back to the exam app.
 */
class TriggerActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = intent.getStringExtra(RecordingService.EXTRA_SESSION)
        val student = intent.getStringExtra(RecordingService.EXTRA_STUDENT)
        if (session.isNullOrEmpty() || student.isNullOrEmpty()) return finish()

        RecordingService.start(this, session, student)
        startedAt = System.currentTimeMillis()
        handler.post(::waitThenLeave)
    }

    private fun waitThenLeave() {
        val waited = System.currentTimeMillis() - startedAt
        if (!RecordingService.isRecording && waited < MAX_WAIT_MS) {
            handler.postDelayed(::waitThenLeave, 200)
            return
        }
        val pkg = intent.getStringExtra(EXTRA_EXAM_PACKAGE).orEmpty()
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish() // our own task closes -> the previous app (exam) is back on screen
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_EXAM_PACKAGE = "exam_package"
        private const val MAX_WAIT_MS = 5_000L
    }
}
