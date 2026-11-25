package com.friendlamp.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Colors, how long the lamp stays lit, and everything about this phone. */
class SettingsActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var resetArmedUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.done).setOnClickListener { finish() }
        findViewById<TextView>(R.id.reset).setOnClickListener { reset() }
        findViewById<TextView>(R.id.update).setOnClickListener { updateTapped() }
    }

    override fun onResume() {
        super.onResume()
        Lamp.onChange = { ui.post { render() } }
        render()
    }

    override fun onPause() {
        super.onPause()
        Lamp.onChange = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------- Drawing ----------

    private fun render() {
        val store = Store(this)

        swatches(findViewById(R.id.myColors), store.myColor) {
            store.myColor = it
            changed()
        }
        swatches(findViewById(R.id.friendColors), store.friendColor) {
            store.friendColor = it
            changed()
        }
        val preview = dp(72)
        findViewById<ImageView>(R.id.myPreview).setImageBitmap(LampArt.bitmap(store.myColor, preview))
        findViewById<ImageView>(R.id.friendPreview).setImageBitmap(LampArt.bitmap(store.friendColor, preview))
        findViewById<ImageView>(R.id.offPreview).setImageBitmap(LampArt.bitmap(null, preview))

        val durations = findViewById<LinearLayout>(R.id.durations)
        durations.removeAllViews()
        for (minutes in Config.LIT_CHOICES) {
            val label = if (minutes < 60) "$minutes min" else "${minutes / 60} h"
            durations.addView(chip(label, minutes == store.litMinutes) {
                store.litMinutes = minutes
                changed()
            })
        }
        findViewById<TextView>(R.id.durationHint).text =
            "The lamp goes grey " + (if (store.litMinutes < 60) "${store.litMinutes} minutes" else "${store.litMinutes / 60} h") +
                " after the last tap."

        findViewById<TextView>(R.id.who).text =
            when {
                !store.configured -> "Not set up yet."
                store.otherName != null -> "Connected to the other phone."
                else -> "Waiting for the other phone."
            }
        findViewById<TextView>(R.id.push).text =
            "Notifications: " + store.pushStatus.ifEmpty { "open the app once to turn them on." }
        findViewById<View>(R.id.reset).visibility = if (store.configured) View.VISIBLE else View.GONE

        val update = findViewById<TextView>(R.id.update)
        val status = store.updateStatus ?: ("Build " + Updater.installedBuild(this))
        if (Updater.allowed(this)) {
            update.text = "$status Updates install automatically. Tap to check now."
            update.setTextColor(getColor(R.color.muted))
        } else {
            update.text = "$status\nTap here to allow automatic updates."
            update.setTextColor(getColor(R.color.warm))
        }
    }

    /** A row of round color buttons with a ring around the chosen one. */
    private fun swatches(row: LinearLayout, selected: Int, onPick: (Int) -> Unit) {
        row.removeAllViews()
        val ring = getColor(R.color.ink)
        val ringWidth = dp(3)
        for (color in Config.PALETTE) {
            val dot = View(this)
            val circle = GradientDrawable()
            circle.shape = GradientDrawable.OVAL
            circle.setColor(color)
            if (color == selected) circle.setStroke(ringWidth, ring)
            dot.background = circle
            dot.contentDescription = "Color"
            val size = if (color == selected) dp(34) else dp(28)
            val cell = FrameLayout(this)
            cell.addView(dot, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            cell.setOnClickListener { onPick(color) }
            row.addView(cell, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
    }

    /** A rounded choice button. */
    private fun chip(label: String, selected: Boolean, onPick: () -> Unit): View {
        val view = TextView(this)
        view.text = label
        view.textSize = 14f
        view.gravity = Gravity.CENTER
        view.setTextColor(if (selected) 0xFF2A1A05.toInt() else getColor(R.color.ink))
        val shape = GradientDrawable()
        shape.cornerRadius = dp(18).toFloat()
        if (selected) {
            shape.setColor(getColor(R.color.warm))
        } else {
            shape.setColor(getColor(R.color.bg))
            shape.setStroke(dp(1), getColor(R.color.line))
        }
        view.background = shape
        view.setOnClickListener { onPick() }
        val params = LinearLayout.LayoutParams(0, dp(38), 1f)
        params.marginEnd = dp(6)
        view.layoutParams = params
        return view
    }

    /** A setting changed: redraw this screen and the widget. */
    private fun changed() {
        Lamp.changed(this)
        render()
    }

    // ---------- Actions ----------

    private fun updateTapped() {
        if (Updater.allowed(this)) {
            Updater.checkSoon(this, force = true)
            Toast.makeText(this, "Checking for updates", Toast.LENGTH_SHORT).show()
        } else {
            // Opens Android's "Install unknown apps" switch for FriendLamp.
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
    }

    private fun reset() {
        val label = findViewById<TextView>(R.id.reset)
        if (System.currentTimeMillis() > resetArmedUntil) {
            resetArmedUntil = System.currentTimeMillis() + 4000
            label.text = "Tap again to disconnect this phone"
            ui.postDelayed({ label.text = "Disconnect this phone" }, 4000)
            return
        }
        resetArmedUntil = 0L
        label.text = "Disconnect this phone"
        Lamp.io.execute {
            try {
                Api.call(this, "leave")
            } catch (e: Exception) {
                // Leaving locally is enough.
            }
            Store(this).reset()
            Lamp.changed(this)
            ui.post { finish() }
        }
    }
}
