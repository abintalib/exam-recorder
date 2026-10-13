package com.examrecorder

import android.content.Context
import java.util.UUID

/** Identity of this tablet. The admin links the pairing code to a student once. */
object Prefs {
    private const val FILE = "exam_recorder"
    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I

    fun deviceId(ctx: Context): String = getOrCreate(ctx, "device_id") { UUID.randomUUID().toString() }

    fun pairCode(ctx: Context): String = getOrCreate(ctx, "pair_code") {
        (1..6).map { CODE_CHARS.random() }.joinToString("")
    }

    private fun getOrCreate(ctx: Context, key: String, make: () -> String): String {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return p.getString(key, null) ?: make().also { p.edit().putString(key, it).apply() }
    }
}

/** Live status shown on the admin dashboard. */
object Status {
    @Volatile var lastError: String? = null
    @Volatile var lastUpload: String? = null
}
