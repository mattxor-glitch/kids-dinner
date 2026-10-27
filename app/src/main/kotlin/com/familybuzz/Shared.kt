package com.familybuzz

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/** Talks to the free ntfy.sh relay. Each kid phone listens on its own private topic. */
object Ntfy {
    const val BASE = "https://ntfy.sh"

    fun ackTopic(topic: String) = "$topic-ack"

    /** Blocking. Call from a background thread. */
    fun publish(topic: String, body: String): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = URL("$BASE/$topic").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            c.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            c?.disconnect()
        }
    }

    /** Blocking. Returns cached messages on a topic (ntfy.sh keeps them about 12 hours). */
    fun poll(topic: String, since: String): List<JSONObject> {
        var c: HttpURLConnection? = null
        val out = mutableListOf<JSONObject>()
        try {
            val s = URLEncoder.encode(since, "UTF-8")
            c = URL("$BASE/$topic/json?poll=1&since=$s").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.inputStream.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val o = JSONObject(line)
                    if (o.optString("event") == "message") out.add(o)
                }
            }
        } catch (e: Exception) {
            // Network trouble: return whatever we have.
        } finally {
            c?.disconnect()
        }
        return out
    }
}

/** One message from a parent to a kid. Travels as JSON in the ntfy message body. */
data class Buzz(
    val id: String,
    val from: String,
    val text: String,
    val level: String,
    val sentAt: Long,
) {
    fun toJson(): String = JSONObject()
        .put("app", "familybuzz")
        .put("id", id)
        .put("from", from)
        .put("text", text)
        .put("level", level)
        .put("sentAt", sentAt)
        .toString()

    companion object {
        const val GENTLE = "gentle"
        const val LOUD = "loud"
        const val TAKEOVER = "takeover"

        fun fromJson(s: String): Buzz? = try {
            val o = JSONObject(s)
            if (o.optString("app") != "familybuzz") null
            else Buzz(
                id = o.getString("id"),
                from = o.optString("from", "Parent"),
                text = o.optString("text", ""),
                level = o.optString("level", LOUD),
                sentAt = o.optLong("sentAt", System.currentTimeMillis()),
            )
        } catch (e: Exception) {
            null
        }
    }
}

object Ids {
    private val rnd = SecureRandom()
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    fun random(len: Int): String = buildString {
        repeat(len) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
    }

    fun newTopic() = "fam-" + random(18)

    val CODE_REGEX = Regex("fam-[a-z0-9]{8,40}")
}

fun Context.store(): SharedPreferences = getSharedPreferences("familybuzz", Context.MODE_PRIVATE)

// ---- Tiny view helpers so the apps need no extra libraries ----

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.vbox(padding: Int = 16): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
}

fun Context.label(text: String, size: Float = 16f, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.section(text: String): TextView = label(text, 13f, true).apply {
    setPadding(0, dp(20), 0, dp(6))
    isAllCaps = true
    alpha = 0.7f
}

fun Context.button(text: String, onClick: (View) -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener(onClick)
}

fun LinearLayout.add(v: View, weight: Float = 0f): LinearLayout {
    val lp = if (orientation == LinearLayout.VERTICAL)
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    else
        LinearLayout.LayoutParams(
            if (weight > 0) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            weight,
        )
    addView(v, lp)
    return this
}
