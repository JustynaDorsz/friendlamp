package com.friendlamp.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
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

/** Colors, how long the lamp stays lit, profiles and updates. */
class SettingsActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())

    /** The profile whose "Remove" was tapped once, and until when a second tap confirms it. */
    private var removeArmed = ""
    private var removeArmedUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.done).setOnClickListener { finish() }
        findViewById<TextView>(R.id.addProfile).setOnClickListener { addProfile() }
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

    private fun dp(value: Int): Int = Ui.dp(this, value)

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
            durations.addView(Ui.chip(this, label, minutes == store.litMinutes) {
                store.litMinutes = minutes
                changed()
            })
        }
        findViewById<TextView>(R.id.durationHint).text =
            "The lamp goes grey " + (if (store.litMinutes < 60) "${store.litMinutes} minutes" else "${store.litMinutes / 60} h") +
                " after the last tap."

        renderProfiles(store)
        findViewById<TextView>(R.id.who).text = when {
            !store.configured -> "This profile is not set up yet."
            store.otherName != null -> "Connected to the other phone."
            else -> "Waiting for the other phone."
        }
        findViewById<TextView>(R.id.push).text =
            "Notifications: " + store.pushStatus.ifEmpty { "open the app once to turn them on." }

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

    /** One row per profile: tap the name to use it, "Remove" to delete it. */
    private fun renderProfiles(store: Store) {
        val list = findViewById<LinearLayout>(R.id.profilesList)
        list.removeAllViews()
        for (profile in store.profiles) {
            val selected = profile.key == store.profile
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            val dot = View(this)
            val circle = GradientDrawable()
            circle.shape = GradientDrawable.OVAL
            if (selected) {
                circle.setColor(getColor(R.color.warm))
            } else {
                circle.setColor(0)
                circle.setStroke(dp(2), getColor(R.color.muted))
            }
            dot.background = circle
            row.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)))

            val name = TextView(this)
            name.text = if (selected) profile.label + "  (in use)" else profile.label
            name.textSize = 16f
            name.setTextColor(getColor(if (selected) R.color.ink else R.color.muted))
            if (selected) name.setTypeface(name.typeface, Typeface.BOLD)
            name.setPadding(dp(12), dp(12), dp(8), dp(12))
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val remove = TextView(this)
            val armed = removeArmed == profile.key && System.currentTimeMillis() < removeArmedUntil
            remove.text = if (armed) "Tap again to remove" else "Remove"
            remove.textSize = 13f
            remove.setTextColor(getColor(R.color.error))
            remove.setPadding(dp(10), dp(12), 0, dp(12))
            remove.setOnClickListener { removeTapped(profile.key) }
            row.addView(remove)

            row.setOnClickListener {
                Lamp.switchTo(this, profile.key)
                render()
            }
            list.addView(row)
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

    /** A setting changed: redraw this screen and the widget. */
    private fun changed() {
        Lamp.changed(this)
        render()
    }

    // ---------- Actions ----------

    /** Creates an empty profile, switches to it, and returns to the main screen to set it up. */
    private fun addProfile() {
        val store = Store(this)
        if (!store.configured) {
            // The profile in use is still empty: set that one up first.
            finish()
            return
        }
        val key = store.addProfile("Profile " + (store.profiles.size + 1))
        Lamp.switchTo(this, key)
        finish()
    }

    private fun removeTapped(key: String) {
        if (removeArmed != key || System.currentTimeMillis() > removeArmedUntil) {
            removeArmed = key
            removeArmedUntil = System.currentTimeMillis() + 4000
            render()
            ui.postDelayed({ render() }, 4100)
            return
        }
        removeArmed = ""
        val wasInUse = Store(this).profile == key
        Lamp.io.execute {
            try {
                if (Store(this, key).configured) Api.call(this, "leave", null, 8000, key)
            } catch (e: Exception) {
                // Removing it on this phone is enough.
            }
            Store(this).removeProfile(key)
            if (wasInUse) Lamp.activate(this) else Lamp.changed(this)
            ui.post { render() }
        }
    }

    private fun updateTapped() {
        if (Updater.allowed(this)) {
            Updater.checkSoon(this, force = true)
            Toast.makeText(this, "Checking for updates", Toast.LENGTH_SHORT).show()
        } else {
            // Opens Android's "Install unknown apps" switch for FriendLamp.
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
    }
}
