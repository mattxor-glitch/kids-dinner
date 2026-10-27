package com.familybuzz

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps a long-lived connection to ntfy.sh open and reacts to each message the
 * moment it arrives. Runs as a foreground service so Android doesn't kill it.
 */
class ListenerService : Service() {

    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Alerts.ensureChannels(this)
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (worker?.isAlive != true) {
            running = true
            worker = Thread({ loop() }, "buzz-listener").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        try { conn?.disconnect() } catch (_: Exception) {}
        setConnected(false)
        super.onDestroy()
    }

    private fun goForeground() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, KidActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, Alerts.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Listening for messages from parents")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(1, n)
        }
    }

    private fun setConnected(on: Boolean) {
        store().edit().putBoolean("connected", on).putLong("statusAt", System.currentTimeMillis()).apply()
    }

    private fun loop() {
        var backoff = 2_000L
        while (running) {
            val topic = Alerts.topic(this)
            try {
                val since = store().getString("lastId", null) ?: "1m"
                val c = URL("${Ntfy.BASE}/$topic/json?since=$since").openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 15_000
                c.readTimeout = 90_000 // ntfy sends a keepalive about every 45s
                c.inputStream.bufferedReader().use { reader ->
                    setConnected(true)
                    backoff = 2_000L
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val o = JSONObject(line)
                        if (o.optString("event") != "message") continue
                        store().edit().putString("lastId", o.getString("id")).apply()
                        val buzz = Buzz.fromJson(o.optString("message")) ?: continue
                        Alerts.handle(this, buzz, o.optLong("time") * 1000)
                    }
                }
            } catch (_: Exception) {
                // Dropped connection, no internet, etc. Fall through and retry.
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
            setConnected(false)
            if (!running) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }
}
