package com.friendlamp.app

import android.content.Context
import java.util.UUID

/** Everything the app remembers on this phone. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("friendlamp", Context.MODE_PRIVATE)

    private fun string(key: String): String? = prefs.getString(key, null)
    private fun put(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    private fun long(key: String): Long = prefs.getLong(key, 0L)
    private fun put(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    private fun int(key: String, fallback: Int): Int = prefs.getInt(key, fallback)
    private fun put(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    /** A random id for this installation; it identifies the device to the server. */
    val id: String
        get() = string("id") ?: UUID.randomUUID().toString().also { put("id", it) }

    var name: String?
        get() = string("name")
        set(v) = put("name", v)
    var code: String?
        get() = string("code")
        set(v) = put("code", v)
    val configured: Boolean
        get() = !name.isNullOrBlank() && !code.isNullOrBlank()

    var otherName: String?
        get() = string("otherName")
        set(v) = put("otherName", v)
    var otherLastPing: Long
        get() = long("otherLastPing")
        set(v) = put("otherLastPing", v)
    var myLastPing: Long
        get() = long("myLastPing")
        set(v) = put("myLastPing", v)

    /** Times (newest first) when the other person lit the lamp, as a JSON array. */
    var history: String
        get() = string("history") ?: "[]"
        set(v) = put("history", v)

    // ---------- Settings ----------

    /** The lamp color after my own tap. */
    var myColor: Int
        get() = int("myColor", Config.DEFAULT_MY_COLOR)
        set(v) = put("myColor", v)

    /** The lamp color after the other person's tap. */
    var friendColor: Int
        get() = int("friendColor", Config.DEFAULT_FRIEND_COLOR)
        set(v) = put("friendColor", v)

    /** How long the lamp stays lit, in minutes. */
    var litMinutes: Int
        get() = int("litMinutes", Config.DEFAULT_LIT_MINUTES)
        set(v) = put("litMinutes", v)
    val litMs: Long
        get() = litMinutes * 60_000L

    // ---------- Lamp state ----------

    /** Who lit the lamp last: "me", "other", or "" for nobody. */
    var litBy: String
        get() = string("litBy") ?: ""
        set(v) = put("litBy", v)

    /** Phone-clock time of the tap that lit the lamp. */
    var litAt: Long
        get() = long("litAt")
        set(v) = put("litAt", v)
    val litUntil: Long
        get() = litAt + litMs
    val lit: Boolean
        get() = litBy.isNotEmpty() && litAt > 0L && System.currentTimeMillis() < litUntil

    /** The color to show now, or null when the lamp is off. */
    val lampColor: Int?
        get() = when {
            !configured || !lit -> null
            litBy == "me" -> myColor
            else -> friendColor
        }

    var pushStatus: String
        get() = string("pushStatus") ?: ""
        set(v) = put("pushStatus", v)

    var fcmConfig: String?
        get() = string("fcmConfig")
        set(v) = put("fcmConfig", v)
    var fcmRegistered: String?
        get() = string("fcmRegistered")
        set(v) = put("fcmRegistered", v)

    /** The last thing that went wrong, shown on the app screen to help with fixing it. */
    var lastError: String?
        get() = string("lastError")
        set(v) = put("lastError", v)

    var updateStatus: String?
        get() = string("updateStatus")
        set(v) = put("updateStatus", v)
    var lastUpdateCheck: Long
        get() = long("lastUpdateCheck")
        set(v) = put("lastUpdateCheck", v)

    /** Forget the pairing but keep the device id and the settings. */
    fun reset() {
        val keepId = id
        val mine = myColor
        val friend = friendColor
        val minutes = litMinutes
        prefs.edit().clear()
            .putString("id", keepId)
            .putInt("myColor", mine)
            .putInt("friendColor", friend)
            .putInt("litMinutes", minutes)
            .apply()
    }
}
