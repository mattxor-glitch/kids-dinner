package com.familybuzz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

object Alerts {
    const val CH_SERVICE = "listener"
    const val CH_GENTLE = "gentle_popup" // new id: Android won't raise an existing channel's importance
    const val CH_LOUD = "loud"
    const val CH_TAKEOVER = "takeover"

    /** Messages older than this (e.g. phone was off) arrive as gentle notifications instead. */
    private const val STALE_MS = 15 * 60_000L

    private val GENTLE_BUZZ = longArrayOf(0, 300, 150, 300)

    fun kidName(ctx: Context) = ctx.store().getString("kidName", "") ?: ""

    fun topic(ctx: Context): String {
        val p = ctx.store()
        return p.getString("topic", null) ?: Ids.newTopic().also { p.edit().putString("topic", it).apply() }
    }

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the app listening for messages from parents"
                setShowBadge(false)
            })
        nm.deleteNotificationChannel("gentle") // old quiet channel from the first version
        nm.createNotificationChannel(
            NotificationChannel(CH_GENTLE, "Gentle messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pops up on screen with a short vibration"
                enableVibration(true)
                vibrationPattern = GENTLE_BUZZ
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        nm.createNotificationChannel(
            NotificationChannel(CH_LOUD, "Loud messages", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the app plays its own alarm sound
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        nm.createNotificationChannel(
            NotificationChannel(CH_TAKEOVER, "Take-over messages", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
    }

    fun handle(ctx: Context, buzz: Buzz, receivedFromServerAt: Long) {
        val p = ctx.store()
        val seen = (p.getString("seenIds", "") ?: "").split(",").filter { it.isNotEmpty() }
        if (buzz.id in seen) return
        p.edit().putString("seenIds", (seen.takeLast(49) + buzz.id).joinToString(",")).apply()

        val pm = ctx.getSystemService(PowerManager::class.java)!!
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "familybuzz:alert")
        wl.acquire(15_000)

        val age = System.currentTimeMillis() - minOf(buzz.sentAt, receivedFromServerAt)
        val level = if (age > STALE_MS) Buzz.GENTLE else buzz.level
        ensureChannels(ctx)
        when (level) {
            Buzz.TAKEOVER -> takeover(ctx, buzz)
            Buzz.LOUD -> loud(ctx, buzz)
            else -> notify(ctx, buzz, CH_GENTLE).also { vibrateOnce(ctx) }
        }
    }

    /** Short double buzz, even if the phone's notification vibration is turned off. */
    private fun vibrateOnce(ctx: Context) {
        try {
            @Suppress("DEPRECATION")
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(GENTLE_BUZZ, -1))
        } catch (_: Exception) {}
    }

    private fun notifId(buzz: Buzz) = buzz.id.hashCode()

    private fun ackIntent(ctx: Context, buzz: Buzz): PendingIntent {
        val i = Intent(ctx, AckReceiver::class.java)
            .putExtra("mid", buzz.id)
            .putExtra("notifId", notifId(buzz))
        return PendingIntent.getBroadcast(ctx, notifId(buzz), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun title(buzz: Buzz): String {
        val late = System.currentTimeMillis() - buzz.sentAt > STALE_MS
        val t = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(buzz.sentAt))
        return if (late) "${buzz.from} (sent at $t)" else buzz.from
    }

    private fun notify(ctx: Context, buzz: Buzz, channel: String) {
        val ack = ackIntent(ctx, buzz)
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title(buzz))
            .setContentText(buzz.text)
            .setStyle(Notification.BigTextStyle().bigText(buzz.text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(ack)
            .addAction(Notification.Action.Builder(null, "Got it", ack).build())
            .setDeleteIntent(stopSoundIntent(ctx))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)!!.notify(notifId(buzz), n)
    }

    private fun stopSoundIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, AckReceiver::class.java).putExtra("stopOnly", true)
        return PendingIntent.getBroadcast(ctx, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun takeover(ctx: Context, buzz: Buzz) {
        val i = Intent(ctx, AlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("mid", buzz.id)
            .putExtra("from", title(buzz))
            .putExtra("text", buzz.text)
            .putExtra("notifId", notifId(buzz))
        val full = PendingIntent.getActivity(ctx, notifId(buzz), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CH_TAKEOVER)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title(buzz))
            .setContentText(buzz.text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .setOngoing(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)!!.notify(notifId(buzz), n)
        AlertPlayer.play(ctx, 0)
        // Android only lets this jump in front of games/videos when "Display over other apps"
        // is allowed (or this app is open). Otherwise the full-screen intent above takes over.
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    /** Alarm for 10 seconds plus a pop-up box with a "Got it" button that stops it. */
    private fun loud(ctx: Context, buzz: Buzz) {
        val popup = Intent(ctx, PopupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("mid", buzz.id)
            .putExtra("from", title(buzz))
            .putExtra("text", buzz.text)
            .putExtra("notifId", notifId(buzz))
        val popupPi = PendingIntent.getActivity(ctx, notifId(buzz), popup,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ack = ackIntent(ctx, buzz)
        val n = Notification.Builder(ctx, CH_LOUD)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title(buzz))
            .setContentText(buzz.text)
            .setStyle(Notification.BigTextStyle().bigText(buzz.text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(popupPi)
            .setFullScreenIntent(popupPi, true) // shows the pop-up over the lock screen
            .addAction(Notification.Action.Builder(null, "Got it", ack).build())
            .setDeleteIntent(stopSoundIntent(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java)!!.notify(notifId(buzz), n)
        AlertPlayer.play(ctx, 10_000)
        try { ctx.startActivity(popup) } catch (_: Exception) {}
    }

    fun sendAck(ctx: Context, mid: String) {
        val body = JSONObject()
            .put("ack", mid)
            .put("kid", kidName(ctx))
            .put("at", System.currentTimeMillis())
            .toString()
        val topic = topic(ctx)
        Thread { Ntfy.publish(Ntfy.ackTopic(topic), body) }.start()
    }

    fun test(ctx: Context, level: String) {
        handle(ctx, Buzz("test-" + Ids.random(8), "Test", "This is what a ${level} message looks like.", level,
            System.currentTimeMillis()), System.currentTimeMillis())
    }
}

/** Plays the phone's alarm sound at full alarm volume and vibrates. */
object AlertPlayer {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var savedVolume = -1
    private var appCtx: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stop() }

    /** durationMs = 0 means keep going until stop() is called. */
    @Synchronized
    fun play(ctx: Context, durationMs: Long) {
        stop()
        val c = ctx.applicationContext
        appCtx = c
        val am = c.getSystemService(AudioManager::class.java)!!
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) { savedVolume = -1 }

        val candidates = listOfNotNull<Uri>(
            RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    setDataSource(c, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                break
            } catch (_: Exception) {
                player?.release()
                player = null
            }
        }

        try {
            @Suppress("DEPRECATION")
            val v = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))
            vibrator = v
        } catch (_: Exception) {}

        if (durationMs > 0) handler.postDelayed(stopper, durationMs)
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(stopper)
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
        val c = appCtx
        if (c != null && savedVolume >= 0) {
            try {
                c.getSystemService(AudioManager::class.java)!!
                    .setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: Exception) {}
        }
        savedVolume = -1
    }
}

/** Handles the "Got it" button on notifications. */
class AckReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlertPlayer.stop()
        if (intent.getBooleanExtra("stopOnly", false)) return
        val mid = intent.getStringExtra("mid") ?: return
        ctx.getSystemService(NotificationManager::class.java)!!.cancel(intent.getIntExtra("notifId", 0))
        PopupActivity.closeIfShowing(mid)
        if (!mid.startsWith("test-")) Alerts.sendAck(ctx, mid)
    }
}

/** Restarts the listener after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (ctx.store().getString("topic", null) == null) return
        try {
            ctx.startForegroundService(Intent(ctx, ListenerService::class.java))
        } catch (_: Exception) {}
    }
}
