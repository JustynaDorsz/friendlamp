package com.friendlamp.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Setup, and a larger view of the lamp with the list of recent times. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            // Every second, because recent times are shown with seconds.
            ui.postDelayed(this, 1000)
        }
    }

    /** What the profile chips currently show, so they are rebuilt only when it changes. */
    private var shownProfiles = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.start).setOnClickListener { join() }
        findViewById<TextView>(R.id.cancelSetup).setOnClickListener { cancelSetup() }
        findViewById<ImageView>(R.id.lamp).setOnClickListener { send() }
        findViewById<TextView>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        Lamp.onChange = { ui.post { render() } }
        Notifier.clear(this)
        ui.post(ticker)
        Updater.checkSoon(this, force = true)
        if (Store(this).configured) {
            askForNotifications()
            background {
                Lamp.refresh(this)
                syncPush()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Lamp.onChange = null
        ui.removeCallbacks(ticker)
    }

    // ---------- Drawing ----------

    private fun render() {
        val store = Store(this)
        renderProfiles(store)
        findViewById<View>(R.id.setup).visibility = if (store.configured) View.GONE else View.VISIBLE
        findViewById<View>(R.id.lampGroup).visibility = if (store.configured) View.VISIBLE else View.GONE
        if (!store.configured) {
            // A profile that is being added can be abandoned if there is another one to go back to.
            findViewById<View>(R.id.cancelSetup).visibility = if (store.profiles.size > 1) View.VISIBLE else View.GONE
            return
        }

        val other = store.otherName
        val color = store.lampColor
        val size = (240 * resources.displayMetrics.density).toInt()
        findViewById<ImageView>(R.id.lamp).setImageBitmap(LampArt.bitmap(color, size))

        findViewById<TextView>(R.id.headline).text = when {
            other == null -> "Waiting for the other phone"
            color == null -> "No updates"
            store.litBy == "me" -> "You sent a status check"
            else -> "Status check received"
        }
        val hint = findViewById<TextView>(R.id.hint)
        hint.text = if (other == null) "Enter the same code there, with a different name." else ""
        hint.visibility = if (other == null) View.VISIBLE else View.GONE

        renderTimeline(store)

        val problem = store.lastError
        val status = findViewById<TextView>(R.id.status)
        status.text = if (problem == null) "" else "Last problem: $problem"
        status.visibility = if (problem == null) View.GONE else View.VISIBLE
    }

    /**
     * Sent and received times in one list, newest first. Each row has a dot and
     * text in the color chosen for that direction.
     */
    private fun renderTimeline(store: Store) {
        val received = Lamp.times(store.history).ifEmpty { listOf(store.otherLastPing) }
        val sent = Lamp.times(store.myHistory).ifEmpty { listOf(store.myLastPing) }
        val entries = (received.filter { it > 0L }.map { it to false } + sent.filter { it > 0L }.map { it to true })
            .sortedByDescending { it.first }
            .take(20)

        val list = findViewById<LinearLayout>(R.id.timeline)
        while (list.childCount > entries.size) list.removeViewAt(list.childCount - 1)
        while (list.childCount < entries.size) list.addView(dotRow(16f))
        entries.forEachIndexed { index, (time, wasSent) ->
            val row = list.getChildAt(index) as LinearLayout
            val rowColor = if (wasSent) store.myColor else store.friendColor
            (row.getChildAt(0).background as GradientDrawable).setColor(rowColor)
            val text = row.getChildAt(1) as TextView
            text.setTextColor(rowColor)
            val label = Lamp.whenText(time, store.clockSkew)
            if (text.text.toString() != label) text.text = label
            row.contentDescription = (if (wasSent) "Sent " else "Received ") + label
        }

        // A small key for the two colors.
        val legend = findViewById<LinearLayout>(R.id.legend)
        if (legend.childCount == 0) {
            legend.addView(dotRow(13f))
            legend.addView(dotRow(13f))
            (legend.getChildAt(1) as LinearLayout).setPadding(Ui.dp(this, 18), 0, 0, 0)
        }
        legend.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        for ((index, pair) in listOf("sent" to store.myColor, "received" to store.friendColor).withIndex()) {
            val row = legend.getChildAt(index) as LinearLayout
            (row.getChildAt(0).background as GradientDrawable).setColor(pair.second)
            val text = row.getChildAt(1) as TextView
            text.text = pair.first
            text.setTextColor(getColor(R.color.muted))
        }
    }

    /** A row made of a small round dot and a text. */
    private fun dotRow(textSize: Float): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3))

        val dot = View(this)
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        dot.background = circle
        val size = Ui.dp(this, if (textSize >= 16f) 10 else 8)
        val dotParams = LinearLayout.LayoutParams(size, size)
        dotParams.marginEnd = Ui.dp(this, 10)
        row.addView(dot, dotParams)

        val text = TextView(this)
        text.textSize = textSize
        row.addView(text)
        return row
    }

    /** The row of profiles at the top; tapping one switches to it. */
    private fun renderProfiles(store: Store) {
        val row = findViewById<LinearLayout>(R.id.profiles)
        val list = store.profiles
        val signature = list.joinToString("|") { it.key + ":" + it.label } + ">" + store.profile
        if (signature == shownProfiles) return
        shownProfiles = signature
        row.removeAllViews()
        row.visibility = if (list.size > 1) View.VISIBLE else View.GONE
        if (list.size <= 1) return
        for (profile in list) {
            row.addView(Ui.chip(this, profile.label, profile.key == store.profile) {
                Lamp.switchTo(this, profile.key)
                render()
            })
        }
    }

    // ---------- Actions ----------

    private fun join() {
        val label = findViewById<EditText>(R.id.profileLabel).text.toString().trim()
        val name = findViewById<EditText>(R.id.name).text.toString().trim()
        val code = findViewById<EditText>(R.id.code).text.toString().trim()
        val error = findViewById<TextView>(R.id.setupError)
        if (name.isEmpty() || code.length < 6) {
            error.text = "Enter your name and a code of at least 6 characters."
            return
        }
        error.text = ""
        val button = findViewById<Button>(R.id.start)
        button.isEnabled = false
        val store = Store(this)
        Lamp.io.execute {
            try {
                val body = JSONObject().put("code", code).put("id", store.id).put("name", name)
                val state = Api.request("POST", "join", body)
                store.name = name
                store.code = code
                if (label.isNotEmpty()) store.renameProfile(store.profile, label)
                Lamp.applyState(this, state, store.profile)
                ui.post {
                    button.isEnabled = true
                    findViewById<EditText>(R.id.profileLabel).setText("")
                    findViewById<EditText>(R.id.name).setText("")
                    findViewById<EditText>(R.id.code).setText("")
                    render()
                    askForNotifications()
                }
                syncPush()
            } catch (e: Exception) {
                val message = if (e is ApiException) e.message else "No connection. Try again."
                ui.post {
                    button.isEnabled = true
                    error.text = message
                }
            }
        }
    }

    /** Drops the profile that was being added and goes back to another one. */
    private fun cancelSetup() {
        val store = Store(this)
        if (store.configured || store.profiles.size <= 1) return
        store.removeProfile(store.profile)
        Lamp.changed(this)
        Lamp.io.execute { Lamp.activate(this) }
        render()
    }

    private fun send() {
        background {
            val profile = Store(this).profile
            val state = Api.call(this, "ping", null, 8000, profile)
            try {
                Lamp.applyState(this, state, profile)
            } catch (e: Exception) {
                Lamp.remember(this, "after sending from the app", e)
            }
            val other = state.optJSONObject("other")
            val message = when {
                other == null -> "The other phone hasn’t joined yet"
                state.optBoolean("repeat") -> "Already sent a moment ago"
                state.optBoolean("sent") -> "Sent"
                !other.optBoolean("notifications") -> "Sent. The other phone has notifications off."
                else -> "Saved, but the notification didn’t go through"
            }
            toast(message)
        }
    }

    // ---------- Helpers ----------

    /** Runs network work in the background and shows a message if it fails. */
    private fun background(work: () -> Unit) {
        Lamp.io.execute {
            try {
                work()
            } catch (e: ApiException) {
                if (e.status == 403) {
                    // The server no longer knows this phone for this profile.
                    Store(this).clearConnection()
                    Lamp.changed(this)
                    ui.post { render() }
                }
                toast(e.message ?: "Something went wrong")
            } catch (e: Exception) {
                toast("No connection")
            }
        }
    }

    private fun syncPush() {
        Store(this).pushStatus = try {
            Push.sync(this)
        } catch (e: Exception) {
            "Couldn’t turn on: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun toast(message: String) = ui.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}
