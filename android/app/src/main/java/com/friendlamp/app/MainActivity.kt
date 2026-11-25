package com.friendlamp.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
    private var pushStatus = ""
    private var resetArmedUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.start).setOnClickListener { join() }
        findViewById<ImageView>(R.id.lamp).setOnClickListener { send() }
        findViewById<TextView>(R.id.reset).setOnClickListener { reset() }
        findViewById<TextView>(R.id.update).setOnClickListener { updateTapped() }
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
        findViewById<View>(R.id.setup).visibility = if (store.configured) View.GONE else View.VISIBLE
        findViewById<View>(R.id.lampGroup).visibility = if (store.configured) View.VISIBLE else View.GONE
        renderUpdate()
        if (!store.configured) return

        val other = store.otherName
        val last = store.otherLastPing
        val lit = store.lit
        findViewById<ImageView>(R.id.lamp).setImageResource(if (lit) R.drawable.lamp_lit else R.drawable.lamp_unlit)

        val times = try {
            val array = JSONArray(store.history)
            (0 until array.length()).map { array.optLong(it) }.filter { it > 0L }
        } catch (e: Exception) {
            emptyList()
        }.ifEmpty { if (last > 0L) listOf(last) else emptyList() }

        findViewById<TextView>(R.id.headline).text = when {
            other == null -> "Waiting for your friend"
            last == 0L -> "$other hasn’t lit your lamp yet"
            lit -> "$other is thinking of you"
            else -> "$other was thinking of you"
        }
        findViewById<TextView>(R.id.latest).text = when {
            other == null -> "Ask them to enter the same code, with their own name."
            times.isNotEmpty() -> Lamp.whenText(times.first())
            else -> ""
        }
        findViewById<TextView>(R.id.history).text =
            if (other == null) "" else times.drop(1).joinToString("\n") { Lamp.whenText(it) }
        findViewById<TextView>(R.id.mine).text =
            if (other != null && store.myLastPing > 0L) "You lit $other’s lamp " + Lamp.whenText(store.myLastPing) else ""
        val problem = store.lastError
        findViewById<TextView>(R.id.status).text =
            if (problem == null) pushStatus else "$pushStatus\n\nLast problem: $problem"
    }

    private fun renderUpdate() {
        val view = findViewById<TextView>(R.id.update)
        val status = Store(this).updateStatus ?: ("Build " + Updater.installedBuild(this))
        if (Updater.allowed(this)) {
            view.text = "$status Updates install automatically."
            view.setTextColor(getColor(R.color.muted))
        } else {
            view.text = "$status\nTap here to allow automatic updates."
            view.setTextColor(getColor(R.color.warm))
        }
    }

    private fun updateTapped() {
        if (Updater.allowed(this)) {
            Updater.checkSoon(this, force = true)
            toast("Checking for updates")
        } else {
            // Opens Android's "Install unknown apps" switch for FriendLamp.
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
    }

    // ---------- Actions ----------

    private fun join() {
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
                Lamp.applyState(this, state)
                ui.post {
                    button.isEnabled = true
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

    private fun send() {
        background {
            val state = Api.call(this, "ping")
            try {
                Lamp.applyState(this, state)
            } catch (e: Exception) {
                Lamp.remember(this, "after sending from the app", e)
            }
            val other = state.optJSONObject("other")
            val message = when {
                other == null -> "Your friend hasn’t joined yet"
                state.optBoolean("repeat") -> "Already sent a moment ago"
                state.optBoolean("sent") -> "Sent to " + other.optString("name")
                !other.optBoolean("notifications") -> other.optString("name") + " has notifications off"
                else -> "Saved, but the notification didn’t go through"
            }
            toast(message)
        }
    }

    private fun reset() {
        val label = findViewById<TextView>(R.id.reset)
        if (System.currentTimeMillis() > resetArmedUntil) {
            resetArmedUntil = System.currentTimeMillis() + 4000
            label.text = "Tap again to disconnect this phone"
            ui.postDelayed({ label.text = "Reset this phone" }, 4000)
            return
        }
        resetArmedUntil = 0L
        label.text = "Reset this phone"
        Lamp.io.execute {
            try {
                Api.call(this, "leave")
            } catch (e: Exception) {
                // Leaving locally is enough.
            }
            Store(this).reset()
            pushStatus = ""
            Lamp.changed(this)
            ui.post { render() }
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
                    // The server no longer knows this phone.
                    Store(this).reset()
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
        pushStatus = try {
            Push.sync(this)
        } catch (e: Exception) {
            "Couldn’t turn on notifications: " + (e.message ?: e.javaClass.simpleName)
        }
        ui.post { render() }
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
