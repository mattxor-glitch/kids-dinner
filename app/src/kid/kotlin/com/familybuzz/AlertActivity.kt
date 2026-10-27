package com.familybuzz

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** The full-screen "take over" alert. Keeps ringing until the kid taps "Got it". */
class AlertActivity : Activity() {

    private val pending = mutableListOf<Pair<String, Int>>() // message id, notification id
    private lateinit var fromView: TextView
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = vbox(28).apply { gravity = Gravity.CENTER }
        root.add(label("📣", 72f).apply { gravity = Gravity.CENTER })
        fromView = label("", 26f, true).apply { gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        textView = label("", 34f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(40))
        }
        root.add(fromView)
        root.add(textView)
        root.add(button("Got it") { gotIt() }.apply {
            textSize = 24f
            setPadding(0, dp(20), 0, dp(20))
        })
        setContentView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    private fun show(i: Intent) {
        val mid = i.getStringExtra("mid") ?: return
        if (pending.none { it.first == mid }) pending.add(mid to i.getIntExtra("notifId", 0))
        fromView.text = i.getStringExtra("from") ?: "Parent"
        textView.text = i.getStringExtra("text") ?: ""
    }

    private fun gotIt() {
        AlertPlayer.stop()
        val nm = getSystemService(NotificationManager::class.java)!!
        pending.forEach { (mid, notifId) ->
            nm.cancel(notifId)
            if (!mid.startsWith("test-")) Alerts.sendAck(this, mid)
        }
        pending.clear()
        finish()
    }

    @Deprecated("Back is disabled so the alert can't be dismissed without acknowledging it")
    override fun onBackPressed() {
        // Intentionally ignored.
    }
}
