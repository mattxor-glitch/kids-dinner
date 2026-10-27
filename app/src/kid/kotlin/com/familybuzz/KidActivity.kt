package com.familybuzz

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup screen on the kid's phone: shows the pairing code and fixes permissions. */
class KidActivity : Activity() {

    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var checklist: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { renderStatus(); handler.postDelayed(this, 2_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Alerts.ensureChannels(this)
        val code = Alerts.topic(this)

        val root = vbox()
        nameView = label("", 18f, true)
        root.add(hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            add(nameView, 1f)
            add(button("Rename") { editName() })
        })

        root.add(section("Pairing code"))
        root.add(label(code, 22f, true).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        root.add(label("Add this code in the Family Buzz app on each parent's phone.", 14f).apply { alpha = 0.7f })
        root.add(hbox().apply {
            add(button("Share code") { shareCode(code) }, 1f)
            add(button("Copy") { copyCode(code) }, 1f)
        })

        root.add(section("Status"))
        statusView = label("", 16f)
        root.add(statusView)

        root.add(section("Setup checklist"))
        checklist = vbox(0)
        root.add(checklist)

        root.add(section("Try it"))
        root.add(hbox().apply {
            add(button("Gentle") { Alerts.test(this@KidActivity, Buzz.GENTLE) }, 1f)
            add(button("Loud") { Alerts.test(this@KidActivity, Buzz.LOUD) }, 1f)
            add(button("Take over") { Alerts.test(this@KidActivity, Buzz.TAKEOVER) }, 1f)
        })

        setContentView(ScrollView(this).apply { addView(root) })

        startForegroundService(Intent(this, ListenerService::class.java))
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Alerts.kidName(this).isBlank()) editName()
        renderName()
    }

    override fun onResume() {
        super.onResume()
        renderChecklist()
        handler.post(ticker)
        // Restart the listener in case it was stopped.
        startForegroundService(Intent(this, ListenerService::class.java))
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderChecklist()
        // The listener's notification needs this permission to show; restart it.
        startForegroundService(Intent(this, ListenerService::class.java))
    }

    private fun renderName() {
        val n = Alerts.kidName(this)
        nameView.text = if (n.isBlank()) "This phone (no name yet)" else "$n's phone"
    }

    private fun renderStatus() {
        val connected = store().getBoolean("connected", false)
        statusView.text = if (connected) "● Connected — ready for messages" else "○ Connecting… (check Wi-Fi or data)"
        statusView.setTextColor(if (connected) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
    }

    private fun renderChecklist() {
        checklist.removeAllViews()
        val pkg = Uri.parse("package:$packageName")

        val notifOk = getSystemService(NotificationManager::class.java)!!.areNotificationsEnabled()
        row("Notifications allowed", notifOk) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }

        row("Display over other apps (lets alerts interrupt games and videos)", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
        }

        if (Build.VERSION.SDK_INT >= 34) {
            val fsiOk = getSystemService(NotificationManager::class.java)!!.canUseFullScreenIntent()
            row("Full-screen alerts (shows over the lock screen)", fsiOk) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            }
        }

        val batteryOk = getSystemService(PowerManager::class.java)!!.isIgnoringBatteryOptimizations(packageName)
        row("Battery: unrestricted (keeps it connected while asleep)", batteryOk) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun row(text: String, ok: Boolean, fix: () -> Unit) {
        checklist.add(hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            add(label(if (ok) "✅" else "⚠️", 18f).apply { setPadding(0, 0, dp(10), 0) })
            add(label(text, 15f), 1f)
            if (!ok) add(button("Fix") { fix() })
        })
    }

    private fun editName() {
        val input = EditText(this).apply {
            hint = "Kid's name"
            setText(Alerts.kidName(this@KidActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this)
            .setTitle("Whose phone is this?")
            .setView(vbox(20).apply { add(input) })
            .setPositiveButton("Save") { _, _ ->
                store().edit().putString("kidName", input.text.toString().trim()).apply()
                renderName()
            }
            .show()
    }

    private fun shareText(code: String): String {
        val n = Alerts.kidName(this).ifBlank { "this phone" }
        return "Family Buzz code for $n: $code"
    }

    private fun shareCode(code: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(code))
        startActivity(Intent.createChooser(send, "Send the code to a parent"))
    }

    private fun copyCode(code: String) {
        getSystemService(ClipboardManager::class.java)!!.setPrimaryClip(ClipData.newPlainText("Family Buzz code", code))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
