package com.friendlamp.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One connection to one other person. Only the active profile sends and receives. */
data class Profile(val key: String, val label: String)

/**
 * Everything the app remembers on this phone.
 *
 * Settings (colors, timing) are shared. The connection itself (name, code,
 * device id, lamp state) is kept per profile; a Store reads and writes the
 * active profile unless another one is named.
 */
class Store(context: Context, profileKey: String? = null) {
    private val prefs = context.applicationContext.getSharedPreferences("friendlamp", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    /** The profile this Store works on. */
    val profile: String = profileKey ?: (prefs.getString("active", null) ?: "p1")

    // ---------- Plain access ----------

    private fun string(key: String): String? = prefs.getString(key, null)
    private fun put(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    private fun long(key: String): Long = prefs.getLong(key, 0L)
    private fun put(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    private fun int(key: String, fallback: Int): Int = prefs.getInt(key, fallback)
    private fun put(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    /** The name under which a per-profile value is stored. */
    private fun mine(key: String): String = "$profile.$key"

    // ---------- Profiles ----------

    val profiles: List<Profile>
        get() = try {
            val array = JSONArray(string("profiles") ?: "[]")
            (0 until array.length()).map { array.getJSONObject(it) }.map { Profile(it.getString("key"), it.getString("label")) }
        } catch (e: Exception) {
            emptyList()
        }

    private fun saveProfiles(list: List<Profile>) {
        val array = JSONArray()
        for (p in list) array.put(JSONObject().put("key", p.key).put("label", p.label))
        put("profiles", array.toString())
    }

    /** The profile in use. Changing it is done through Lamp.switchTo. */
    var active: String
        get() = string("active") ?: "p1"
        set(v) = put("active", v)

    val label: String
        get() = profiles.firstOrNull { it.key == profile }?.label ?: ""

    fun addProfile(label: String): String {
        val key = "p" + System.currentTimeMillis()
        saveProfiles(profiles + Profile(key, label))
        return key
    }

    fun renameProfile(key: String, label: String) {
        saveProfiles(profiles.map { if (it.key == key) Profile(key, label) else it })
    }

    /** Deletes a profile and what it stored. There is always at least one profile left. */
    fun removeProfile(key: String) {
        val edit = prefs.edit()
        for (name in prefs.all.keys) {
            if (name.startsWith("$key.")) edit.remove(name)
        }
        edit.apply()
        var remaining = profiles.filter { it.key != key }
        if (remaining.isEmpty()) remaining = listOf(Profile("p" + System.currentTimeMillis(), "Profile 1"))
        saveProfiles(remaining)
        if (active == key) active = remaining.first().key
    }

    /** The profile whose device id is `deviceId`, if any. */
    fun profileForDevice(deviceId: String): String? =
        profiles.firstOrNull { prefs.getString("${it.key}.id", null) == deviceId }?.key

    // ---------- This profile's connection ----------

    /** A random id for this profile on this phone; it identifies the device to the server. */
    val id: String
        get() = string(mine("id")) ?: UUID.randomUUID().toString().also { put(mine("id"), it) }

    var name: String?
        get() = string(mine("name"))
        set(v) = put(mine("name"), v)
    var code: String?
        get() = string(mine("code"))
        set(v) = put(mine("code"), v)
    val configured: Boolean
        get() = !name.isNullOrBlank() && !code.isNullOrBlank()

    var otherName: String?
        get() = string(mine("otherName"))
        set(v) = put(mine("otherName"), v)
    var otherLastPing: Long
        get() = long(mine("otherLastPing"))
        set(v) = put(mine("otherLastPing"), v)
    var myLastPing: Long
        get() = long(mine("myLastPing"))
        set(v) = put(mine("myLastPing"), v)

    /** Times (newest first) when the other person lit the lamp, as a JSON array. */
    var history: String
        get() = string(mine("history")) ?: "[]"
        set(v) = put(mine("history"), v)

    /** Times (newest first) when I sent, as a JSON array. */
    var myHistory: String
        get() = string(mine("myHistory")) ?: "[]"
        set(v) = put(mine("myHistory"), v)

    /** Who lit the lamp last: "me", "other", or "" for nobody. */
    var litBy: String
        get() = string(mine("litBy")) ?: ""
        set(v) = put(mine("litBy"), v)

    /** Phone-clock time of the tap that lit the lamp. */
    var litAt: Long
        get() = long(mine("litAt"))
        set(v) = put(mine("litAt"), v)
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

    /** Which push registration the server has for this profile. */
    var fcmRegistered: String?
        get() = string(mine("fcmRegistered"))
        set(v) = put(mine("fcmRegistered"), v)

    /** Forgets this profile's connection (name, code, lamp state) but keeps the profile. */
    fun clearConnection() {
        val keepId = id
        val edit = prefs.edit()
        for (name in prefs.all.keys) {
            if (name.startsWith("$profile.")) edit.remove(name)
        }
        edit.putString(mine("id"), keepId).apply()
    }

    // ---------- Settings (shared by all profiles) ----------

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

    // ---------- App state ----------

    /** This phone's clock minus the server's clock, so "ago" is right to the second. */
    var clockSkew: Long
        get() = long("clockSkew")
        set(v) = put("clockSkew", v)

    var pushStatus: String
        get() = string("pushStatus") ?: ""
        set(v) = put("pushStatus", v)
    var fcmConfig: String?
        get() = string("fcmConfig")
        set(v) = put("fcmConfig", v)

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

    /** Versions before profiles kept one connection at the top level: move it into "Profile 1". */
    private fun migrate() {
        if (prefs.contains("profiles")) return
        val edit = prefs.edit()
        for (key in listOf("id", "name", "code", "otherName", "history", "litBy", "fcmRegistered")) {
            val value = prefs.getString(key, null)
            if (value != null) edit.putString("p1.$key", value)
            edit.remove(key)
        }
        for (key in listOf("otherLastPing", "myLastPing", "litAt")) {
            if (prefs.contains(key)) edit.putLong("p1.$key", prefs.getLong(key, 0L))
            edit.remove(key)
        }
        val first = JSONArray().put(JSONObject().put("key", "p1").put("label", "Profile 1"))
        edit.putString("profiles", first.toString()).putString("active", "p1").commit()
    }
}
