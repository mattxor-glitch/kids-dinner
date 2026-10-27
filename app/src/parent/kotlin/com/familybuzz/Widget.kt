package com.familybuzz

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Sending shared by the app and the home-screen widgets. */
object Sender {
    data class Kid(val name: String, val topic: String)

    fun kids(ctx: Context): List<Kid> {
        val arr = JSONArray(ctx.store().getString("kids", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { Kid(it.getString("name"), it.getString("topic")) }
    }

    /** Blocking. Sends to every kid, records it in Recent, returns how many failed. */
    @Synchronized
    fun sendToAll(ctx: Context, text: String, level: String): Pair<Int, Int> {
        val targets = kids(ctx)
        val from = (ctx.store().getString("myName", "") ?: "").ifBlank { "Parent" }
        val now = System.currentTimeMillis()
        val fresh = JSONArray()
        var failed = 0
        targets.forEach { kid ->
            val b = Buzz(Ids.random(12), from, text, level, now)
            val ok = Ntfy.publish(kid.topic, b.toJson())
            if (!ok) failed++
            fresh.put(JSONObject().put("mid", b.id).put("kid", kid.name).put("topic", kid.topic)
                .put("text", text).put("at", now).put("ok", ok))
        }
        val old = JSONArray(ctx.store().getString("history", "[]"))
        for (i in 0 until minOf(old.length(), 30)) fresh.put(old.getJSONObject(i))
        ctx.store().edit().putString("history", fresh.toString()).apply()
        return targets.size to failed
    }
}

/** Home-screen button: one tap sends its message to all kids. */
class BuzzWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it, null) }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val e = ctx.store().edit()
        ids.forEach { e.remove("w_${it}_text").remove("w_${it}_level") }
        e.apply()
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION_SEND) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val text = ctx.store().getString("w_${id}_text", null) ?: return
        val level = ctx.store().getString("w_${id}_level", Buzz.LOUD) ?: Buzz.LOUD
        val mgr = AppWidgetManager.getInstance(ctx)
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())

        if (Sender.kids(ctx).isEmpty()) {
            Toast.makeText(ctx, "Add a kid in Family Buzz first", Toast.LENGTH_SHORT).show()
            return
        }
        render(ctx, mgr, id, "Sending…")
        val done = goAsync()
        Thread {
            val (count, failed) = Sender.sendToAll(app, text, level)
            val msg = if (failed == 0) "✓ Sent to $count" else "✗ Failed – no internet?"
            main.post {
                render(app, mgr, id, msg)
                Toast.makeText(app, if (failed == 0) "Sent \"$text\" to all kids" else "Couldn't reach $failed phone(s)",
                    Toast.LENGTH_SHORT).show()
            }
            main.postDelayed({ render(app, mgr, id, null) }, 4_000)
            done.finish()
        }.start()
    }

    companion object {
        const val ACTION_SEND = "com.familybuzz.WIDGET_SEND"

        fun levelIcon(level: String) = when (level) {
            Buzz.GENTLE -> "🔔"
            Buzz.TAKEOVER -> "🚨"
            else -> "📣"
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int, status: String?) {
            val text = ctx.store().getString("w_${id}_text", null) ?: "Tap to set up"
            val level = ctx.store().getString("w_${id}_level", Buzz.LOUD) ?: Buzz.LOUD
            val v = RemoteViews(ctx.packageName, R.layout.widget_buzz)
            v.setTextViewText(R.id.widget_icon, levelIcon(level))
            v.setTextViewText(R.id.widget_text, status ?: text)
            val send = Intent(ctx, BuzzWidget::class.java)
                .setAction(ACTION_SEND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getBroadcast(ctx, id, send,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            mgr.updateAppWidget(id, v)
        }

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.getAppWidgetIds(ComponentName(ctx, BuzzWidget::class.java)).forEach { render(ctx, mgr, it, null) }
        }
    }
}

/** Shown when a widget is placed (or long-pressed → reconfigure): pick its message and level. */
class WidgetSetupActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        title = "Set up a Buzz button"

        val root = vbox()
        root.add(label("This button will send to all kids with one tap.", 15f).apply { alpha = 0.75f })

        root.add(section("Message"))
        val input = EditText(this).apply {
            hint = "Type a message…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(store().getString("w_${widgetId}_text", "") ?: "")
        }
        listOf("Dinner's ready!", "Come here please", "Call me now", "Time for bed", "We're leaving in 5").forEach { p ->
            root.add(button(p) { input.setText(p); input.setSelection(p.length) })
        }
        root.add(input)

        root.add(section("How loud"))
        val group = RadioGroup(this)
        listOf(1 to "🔔 Gentle — pop-up and vibration", 2 to "📣 Loud — alarm with a Got it box",
            3 to "🚨 Take over the screen").forEach { (id, t) ->
            group.addView(RadioButton(this).apply { this.id = id; text = t; textSize = 15f; setPadding(dp(8), dp(6), 0, dp(6)) })
        }
        group.check(when (store().getString("w_${widgetId}_level", Buzz.LOUD)) {
            Buzz.GENTLE -> 1; Buzz.TAKEOVER -> 3; else -> 2
        })
        root.add(group)

        root.add(button("Add button") {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Type a message or pick one", Toast.LENGTH_SHORT).show()
                return@button
            }
            val level = when (group.checkedRadioButtonId) { 1 -> Buzz.GENTLE; 3 -> Buzz.TAKEOVER; else -> Buzz.LOUD }
            store().edit().putString("w_${widgetId}_text", text).putString("w_${widgetId}_level", level).apply()
            BuzzWidget.render(this, AppWidgetManager.getInstance(this), widgetId, null)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }.apply { textSize = 18f })

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
