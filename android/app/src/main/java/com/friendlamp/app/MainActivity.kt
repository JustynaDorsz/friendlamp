package com.friendlamp.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            ui.postDelayed(this, 5000)
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

        val times = try {
            val array = JSONArray(store.history)
            (0 until array.length()).map { array.optLong(it) }.filter { it > 0L }
        } catch (e: Exception) {
            emptyList()
        }.ifEmpty { if (store.otherLastPing > 0L) listOf(store.otherLastPing) else emptyList() }

        findViewById<TextView>(R.id.headline).text = when {
            other == null -> "Waiting for the other phone"
            color == null -> "Lamp is off"
            store.litBy == "me" -> "You sent a status check"
            else -> "Status check received"
        }
        val latest = findViewById<TextView>(R.id.latest)
        latest.setTextColor(if (color != null && store.litBy != "me") store.friendColor else getColor(R.color.muted))
        latest.text = when {
            other == null -> "Enter the same code there, with a different name."
            times.isNotEmpty() -> "Received: " + Lamp.whenText(times.first())
            else -> "Nothing received yet"
        }
        findViewById<TextView>(R.id.history).text =
            if (other == null) "" else times.drop(1).joinToString("\n") { Lamp.whenText(it) }
        findViewById<TextView>(R.id.mine).text =
            if (store.myLastPing > 0L) "Sent: " + Lamp.whenText(store.myLastPing) else ""

        val problem = store.lastError
        val status = findViewById<TextView>(R.id.status)
        status.text = if (problem == null) "" else "Last problem: $problem"
        status.visibility = if (problem == null) View.GONE else View.VISIBLE
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
