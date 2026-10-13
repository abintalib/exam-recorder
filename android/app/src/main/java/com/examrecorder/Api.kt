package com.examrecorder

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the control backend. Today: the local test server.
 * Later: swap these calls for Firebase / AWS without touching the rest of the app.
 */
object Api {
    private val base = BuildConfig.SERVER_URL.trimEnd('/')
    private val JSON = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val uploadHttp = http.newBuilder()
        .writeTimeout(0, TimeUnit.SECONDS) // large files on slow Wi-Fi
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Desired(val action: String, val sessionId: String, val studentId: String, val examPackage: String)

    /** Report status; the server answers with what this tablet should be doing. */
    fun heartbeat(deviceId: String, body: JSONObject): Desired {
        val j = post("/api/devices/$deviceId/heartbeat", body)
        return Desired(
            action = j.optString("action", "idle"),
            sessionId = j.optString("sessionId"),
            studentId = j.optString("studentId"),
            examPackage = j.optString("examPackage"),
        )
    }

    /** Returns a one-time upload URL for {student}/{session}/{file}. With S3 this is a presigned PUT URL. */
    fun uploadUrl(deviceId: String, studentId: String, sessionId: String, fileName: String): Pair<String, String> {
        val j = post("/api/devices/$deviceId/upload-url", JSONObject()
            .put("studentId", studentId).put("sessionId", sessionId).put("fileName", fileName))
        return j.getString("url") to j.getString("key")
    }

    fun putFile(url: String, file: File) {
        val req = Request.Builder().url(url).put(file.asRequestBody("video/mp4".toMediaType())).build()
        uploadHttp.newCall(req).execute().use { if (!it.isSuccessful) throw IOException("Upload HTTP ${it.code}") }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(base + path).post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} on $path")
            return JSONObject(it.body?.string() ?: "{}")
        }
    }
}
