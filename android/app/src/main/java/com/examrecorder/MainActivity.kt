package com.examrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Setup screen for IT, used once per tablet: shows the pairing code and a permission checklist.
 * Students never need to open it.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var checklist: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Tablet code"; textSize = 18f; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply {
            text = Prefs.pairCode(this@MainActivity)
            textSize = 56f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; letterSpacing = 0.15f
        })
        root.addView(TextView(this).apply {
            text = "Link this code to a student in the admin dashboard.\nServer: ${BuildConfig.SERVER_URL}"
            textSize = 14f; gravity = Gravity.CENTER; setPadding(0, 0, 0, pad)
        })
        checklist = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, pad) }
        root.addView(checklist)
        root.addView(Button(this).apply { text = "Grant next permission"; setOnClickListener { grantNext() } })
        setContentView(root)

        ControlService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun steps(): List<Pair<String, Boolean>> = listOf(
        "Camera" to granted(Manifest.permission.CAMERA),
        "Microphone" to granted(Manifest.permission.RECORD_AUDIO),
        "Notifications" to (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)),
        "Display over other apps" to Settings.canDrawOverlays(this),
        "No battery optimization" to getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
    )

    private fun refresh() {
        checklist.text = steps().joinToString("\n") { (name, ok) -> (if (ok) "✅ " else "❌ ") + name }
    }

    private fun grantNext() {
        val missing = steps().firstOrNull { !it.second }?.first ?: return refresh()
        when (missing) {
            "Camera", "Microphone", "Notifications" -> {
                val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                requestPermissions(perms.toTypedArray(), 1)
            }
            "Display over other apps" -> startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            "No battery optimization" -> startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}
