package com.familybuzz

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class ParentActivity : Activity() {

    private data class Kid(val name: String, val topic: String)

    private lateinit var kidsBox: LinearLayout
    private lateinit var msgInput: EditText
    private lateinit var levelGroup: RadioGroup
    private lateinit var historyBox: LinearLayout
    private lateinit var fromLabel: android.widget.TextView

    private val selected = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var pollUntil = 0L

    private val quickPhrases = listOf(
        "Dinner's ready!",
        "Come here please",
        "Call me now",
        "Time to put it away",
        "Time for bed",
        "We're leaving in 5",
        "Check your messages",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        kids().forEach { selected.add(it.topic) }
        renderKids()
        renderHistory()
        if (myName().isBlank()) editName()
    }

    override fun onResume() {
        super.onResume()
        refreshAcks()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val root = vbox()

        fromLabel = label("", 15f)
        root.add(hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            add(fromLabel, 1f)
            add(button("Change") { editName() })
        })
        updateFromLabel()

        root.add(section("Send to"))
        kidsBox = vbox(0)
        root.add(kidsBox)
        root.add(button("+ Add a kid's phone or PC") { addKid() })

        root.add(section("Message"))
        val chips = hbox()
        quickPhrases.forEach { p -> chips.add(button(p) { msgInput.setText(p); msgInput.setSelection(p.length) }) }
        root.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        msgInput = EditText(this).apply {
            hint = "Type a message…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        root.add(msgInput)

        root.add(section("How loud"))
        levelGroup = RadioGroup(this)
        val levels = listOf(
            Triple(1, "Gentle", "A normal notification"),
            Triple(2, "Loud", "Alarm sound for 10 seconds, even on silent"),
            Triple(3, "Take over the screen", "Full-screen alarm until they tap \"Got it\""),
        )
        levels.forEach { (id, title, desc) ->
            levelGroup.addView(RadioButton(this).apply {
                this.id = id
                text = "$title — $desc"
                textSize = 15f
                setPadding(dp(8), dp(6), 0, dp(6))
            })
        }
        levelGroup.check(store().getInt("lastLevel", 2))
        root.add(levelGroup)

        root.add(button("Send") { send() }.apply {
            textSize = 18f
            setPadding(0, dp(14), 0, dp(14))
        })

        root.add(section("Recent"))
        historyBox = vbox(0)
        root.add(historyBox)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun updateFromLabel() {
        val n = myName()
        fromLabel.text = if (n.isBlank()) "Sending as: (set your name)" else "Sending as: $n"
    }

    private fun renderKids() {
        kidsBox.removeAllViews()
        val list = kids()
        if (list.isEmpty()) {
            kidsBox.add(label("Nothing added yet. Install the Buzz app on a kid's phone (or Family Buzz on a PC), then add the code it shows.", 14f).apply { alpha = 0.7f })
            return
        }
        list.forEach { kid ->
            kidsBox.add(CheckBox(this).apply {
                text = kid.name
                textSize = 17f
                isChecked = kid.topic in selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(kid.topic) else selected.remove(kid.topic)
                }
                setOnLongClickListener { removeKid(kid); true }
            })
        }
        kidsBox.add(label("Long-press a name to remove it.", 12f).apply { alpha = 0.5f })
    }

    private fun renderHistory() {
        historyBox.removeAllViews()
        val h = history()
        if (h.length() == 0) {
            historyBox.add(label("Nothing sent yet.", 14f).apply { alpha = 0.6f })
            return
        }
        val tf = DateFormat.getTimeInstance(DateFormat.SHORT)
        for (i in 0 until h.length()) {
            val e = h.getJSONObject(i)
            val ackAt = e.optLong("ackAt", 0)
            val status = when {
                ackAt > 0 -> "✓ Seen at ${tf.format(Date(ackAt))}"
                !e.optBoolean("ok", true) -> "✗ Didn't send — check your internet"
                else -> "… Waiting for them to tap \"Got it\""
            }
            historyBox.add(vbox(0).apply {
                setPadding(0, dp(6), 0, dp(6))
                add(label("${e.optString("kid")} · ${tf.format(Date(e.optLong("at")))}", 13f, true))
                add(label(e.optString("text"), 15f))
                add(label(status, 13f).apply { alpha = 0.75f })
            })
        }
    }

    // ---------------- Actions ----------------

    private fun send() {
        val text = msgInput.text.toString().trim()
        val targets = kids().filter { it.topic in selected }
        if (targets.isEmpty()) { toast("Pick at least one kid"); return }
        if (text.isEmpty()) { toast("Type a message or tap a quick phrase"); return }
        val from = myName().ifBlank { "Parent" }
        val level = when (levelGroup.checkedRadioButtonId) {
            1 -> Buzz.GENTLE
            3 -> Buzz.TAKEOVER
            else -> Buzz.LOUD
        }
        store().edit().putInt("lastLevel", levelGroup.checkedRadioButtonId).apply()

        val now = System.currentTimeMillis()
        val sends = targets.map { it to Buzz(Ids.random(12), from, text, level, now) }
        Thread {
            val results = sends.map { (kid, buzz) -> Triple(kid, buzz, Ntfy.publish(kid.topic, buzz.toJson())) }
            runOnUiThread {
                val h = history()
                val fresh = JSONArray()
                results.forEach { (kid, buzz, ok) ->
                    fresh.put(JSONObject().put("mid", buzz.id).put("kid", kid.name).put("topic", kid.topic)
                        .put("text", buzz.text).put("at", buzz.sentAt).put("ok", ok))
                }
                for (i in 0 until minOf(h.length(), 30)) fresh.put(h.getJSONObject(i))
                saveHistory(fresh)
                renderHistory()
                val failed = results.count { !it.third }
                if (failed == 0) {
                    toast("Sent")
                    msgInput.setText("")
                } else toast("Couldn't reach $failed phone(s). Check your internet.")
                pollUntil = System.currentTimeMillis() + 120_000
                scheduleAckPoll()
            }
        }.start()
    }

    private fun scheduleAckPoll() {
        handler.removeCallbacksAndMessages(null)
        if (System.currentTimeMillis() > pollUntil) return
        handler.postDelayed({ refreshAcks(); scheduleAckPoll() }, 8_000)
    }

    /** Looks for "Got it" replies from kids' phones and marks messages as seen. */
    private fun refreshAcks() {
        val h = history()
        val topics = (0 until h.length()).map { h.getJSONObject(it) }
            .filter { it.optLong("ackAt", 0) == 0L && it.optBoolean("ok", true) }
            .map { it.optString("topic") }.toSet()
        if (topics.isEmpty()) return
        Thread {
            val acks = mutableMapOf<String, Long>()
            topics.forEach { t ->
                Ntfy.poll(Ntfy.ackTopic(t), "12h").forEach { m ->
                    try {
                        val o = JSONObject(m.optString("message"))
                        acks[o.getString("ack")] = o.optLong("at", m.optLong("time") * 1000)
                    } catch (_: Exception) {}
                }
            }
            if (acks.isEmpty()) return@Thread
            runOnUiThread {
                val cur = history()
                var changed = false
                for (i in 0 until cur.length()) {
                    val e = cur.getJSONObject(i)
                    val at = acks[e.optString("mid")]
                    if (at != null && e.optLong("ackAt", 0) == 0L) { e.put("ackAt", at); changed = true }
                }
                if (changed) { saveHistory(cur); renderHistory() }
            }
        }.start()
    }

    private fun addKid() {
        val box = vbox(20)
        val name = EditText(this).apply { hint = "Name (e.g. Emma or Emma's PC)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        val code = EditText(this).apply { hint = "Code from their Buzz app or PC (fam-…)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        clipboardCode()?.let { code.setText(it) }
        box.add(name); box.add(code)
        box.add(label("Tip: on the kid's phone, tap Share code and send it to yourself, then copy it here.", 13f).apply { alpha = 0.7f })
        AlertDialog.Builder(this)
            .setTitle("Add a phone or PC")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val n = name.text.toString().trim()
                val c = Ids.CODE_REGEX.find(code.text.toString().trim().lowercase())?.value
                when {
                    n.isEmpty() -> toast("Enter a name")
                    c == null -> toast("That code doesn't look right. It starts with fam-")
                    else -> {
                        val list = kids().filter { it.topic != c } + Kid(n, c)
                        saveKids(list)
                        selected.add(c)
                        renderKids()
                        toast("Added $n")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeKid(kid: Kid) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${kid.name}?")
            .setMessage("You can add them back later with the code from their phone.")
            .setPositiveButton("Remove") { _, _ ->
                saveKids(kids().filter { it.topic != kid.topic })
                selected.remove(kid.topic)
                renderKids()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editName() {
        val input = EditText(this).apply {
            hint = "e.g. Dad"
            setText(myName())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this)
            .setTitle("Your name")
            .setMessage("The kids will see this on each message.")
            .setView(vbox(20).apply { add(input) })
            .setPositiveButton("Save") { _, _ ->
                store().edit().putString("myName", input.text.toString().trim()).apply()
                updateFromLabel()
            }
            .show()
    }

    // ---------------- Storage ----------------

    private fun myName() = store().getString("myName", "") ?: ""

    private fun kids(): List<Kid> {
        val arr = JSONArray(store().getString("kids", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { Kid(it.getString("name"), it.getString("topic")) }
    }

    private fun saveKids(list: List<Kid>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("topic", it.topic)) }
        store().edit().putString("kids", arr.toString()).apply()
    }

    private fun history() = JSONArray(store().getString("history", "[]"))
    private fun saveHistory(a: JSONArray) = store().edit().putString("history", a.toString()).apply()

    private fun clipboardCode(): String? = try {
        val cm = getSystemService(ClipboardManager::class.java)!!
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.lowercase() ?: ""
        Ids.CODE_REGEX.find(t)?.value
    } catch (e: Exception) { null }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
