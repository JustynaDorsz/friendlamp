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

    /** Phone-clock time until which the lamp is lit. */
    var litUntil: Long
        get() = long("litUntil")
        set(v) = put("litUntil", v)
    val lit: Boolean
        get() = System.currentTimeMillis() < litUntil

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

    /** Forget the pairing but keep the device id. */
    fun reset() {
        val keep = id
        prefs.edit().clear().putString("id", keep).apply()
    }
}
