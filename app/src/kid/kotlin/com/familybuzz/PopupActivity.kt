package com.familybuzz

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * The pop-up box for "Loud" messages. Sits on top of whatever app is open;
 * "Got it" stops the alarm and tells the parent it was seen.
 */
class PopupActivity : Activity() {

    private val pending = mutableListOf<Pair<String, Int>>() // message id, notification id
    private lateinit var fromView: TextView
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setFinishOnTouchOutside(false)

        val root = vbox(24)
        fromView = label("", 20f, true).apply { gravity = Gravity.CENTER }
        textView = label("", 24f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }
        root.add(label("📣", 40f).apply { gravity = Gravity.CENTER })
        root.add(fromView)
        root.add(textView)
        root.add(button("Got it") { gotIt() }.apply {
            textSize = 20f
            setPadding(0, dp(14), 0, dp(14))
        })
        setContentView(root)
        window.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)

        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
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

    @Deprecated("Back just closes the box and stops the sound, without telling the parent")
    override fun onBackPressed() {
        AlertPlayer.stop()
        finish()
    }

    companion object {
        private var current: WeakReference<PopupActivity>? = null

        /** Called when "Got it" was tapped on the notification instead. */
        fun closeIfShowing(mid: String) {
            val a = current?.get() ?: return
            a.runOnUiThread {
                a.pending.removeAll { it.first == mid }
                if (a.pending.isEmpty()) a.finish()
            }
        }
    }
}
