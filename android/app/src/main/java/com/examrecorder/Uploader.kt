package com.examrecorder

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Upload queue that survives reboots: a finished recording is "video.mp4" + "video.mp4.json" (student, session).
 * Uploads one file at a time, deletes it on success, backs off on failure.
 */
object Uploader {
    private const val TAG = "Uploader"
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    @Volatile private var nextTryAt = 0L
    @Volatile private var failures = 0

    fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "recordings").apply { mkdirs() }

    fun markReady(video: File, student: String, session: String) {
        meta(video).writeText(JSONObject().put("student", student).put("session", session).toString())
    }

    fun pending(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.name.endsWith(".mp4") && meta(f).exists() }?.sortedBy { it.name } ?: emptyList()

    /** Called every tick. Starts the next upload if idle. */
    fun kick(ctx: Context) {
        if (busy || System.currentTimeMillis() < nextTryAt) return
        val next = pending(ctx).firstOrNull() ?: return
        busy = true
        worker.execute {
            try {
                upload(ctx, next)
                failures = 0
            } catch (e: Exception) {
                Log.w(TAG, "Upload failed: ${e.message}")
                Status.lastError = "Upload failed, will retry: ${e.message}"
                failures++
                nextTryAt = System.currentTimeMillis() + minOf(30_000L shl minOf(failures - 1, 4), 300_000L)
            } finally {
                busy = false
            }
        }
    }

    private fun upload(ctx: Context, video: File) {
        val m = JSONObject(meta(video).readText())
        val (url, key) = Api.uploadUrl(Prefs.deviceId(ctx), m.getString("student"), m.getString("session"), video.name)
        Api.putFile(url, video)
        video.delete()
        meta(video).delete()
        Status.lastUpload = key
        Status.lastError = null
        Log.i(TAG, "Uploaded $key")
    }

    private fun meta(video: File) = File(video.path + ".json")
}
