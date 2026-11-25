package com.friendlamp.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Shared lamp logic for the screen, the widget and incoming pushes. */
object Lamp {
    /** One background thread for all network work. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    /** Set by the open screen so it redraws when something changes. */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** Stores what the server reported for a profile and redraws the widget. */
    fun applyState(context: Context, state: JSONObject, profile: String? = null) {
        val store = Store(context, profile)
        // Server times are converted to this phone's clock.
        val skew = System.currentTimeMillis() - state.optLong("now", System.currentTimeMillis())
        store.clockSkew = skew
        val me = state.optJSONObject("me")
        val mine = me?.optLong("lastPing", 0L) ?: 0L
        store.myLastPing = mine

        // My own times: the server's list when it sends one, otherwise what this phone has seen.
        val serverSent = me?.optJSONArray("history")
        val sent = if (serverSent != null) times(serverSent.toString()) else times(store.myHistory) + mine
        store.myHistory = JSONArray(sent.filter { it > 0L }.distinct().sortedDescending().take(20)).toString()

        val other = state.optJSONObject("other")
        val theirs = other?.optLong("lastPing", 0L) ?: 0L
        if (other == null) {
            store.otherName = null
            store.otherLastPing = 0L
            store.history = "[]"
        } else {
            store.otherName = other.optString("name")
            store.otherLastPing = theirs
            store.history = (other.optJSONArray("history") ?: JSONArray()).toString()
        }

        // The most recent tap decides the color: mine or the other person's.
        val latest = maxOf(mine, theirs)
        if (latest > 0L) {
            store.litBy = if (theirs >= mine) "other" else "me"
            store.litAt = latest + skew
        } else {
            store.litBy = ""
            store.litAt = 0L
        }
        changed(context)
    }

    /**
     * A push arrived. `to` is the device id it was sent to, which tells the
     * profile. Returns false when it is not for the profile in use; such a
     * push is ignored and that profile is unregistered on the server.
     */
    fun received(context: Context, name: String, timestamp: Long, sentAt: Long, to: String?): Boolean {
        val current = Store(context)
        val target = if (to.isNullOrEmpty()) current.profile else current.profileForDevice(to)
        if (target != current.profile) {
            if (target != null) {
                io.execute {
                    try {
                        Push.unregister(context, target)
                    } catch (e: Exception) {
                        // Tried again the next time one arrives.
                    }
                }
            }
            return false
        }

        val now = System.currentTimeMillis()
        current.otherName = name
        current.otherLastPing = timestamp
        current.litBy = "other"
        current.litAt = minOf(if (sentAt > 0L) sentAt else now, now)
        val old = try {
            JSONArray(current.history)
        } catch (e: Exception) {
            JSONArray()
        }
        val updated = JSONArray().put(timestamp)
        for (i in 0 until minOf(old.length(), 19)) {
            if (old.optLong(i) != timestamp) updated.put(old.optLong(i))
        }
        current.history = updated.toString()
        changed(context)
        return true
    }

    /** Reads a stored JSON array of times. */
    fun times(json: String): List<Long> = try {
        val array = JSONArray(json)
        (0 until array.length()).map { array.optLong(it) }.filter { it > 0L }
    } catch (e: Exception) {
        emptyList()
    }

    fun changed(context: Context) {
        try {
            LampWidget.render(context)
        } catch (e: Exception) {
            remember(context, "widget", e)
        }
        onChange?.invoke()
    }

    /**
     * Makes another profile the one in use: the old one stops receiving, the
     * new one starts. Works offline too; a push for the old profile that still
     * arrives is ignored.
     */
    fun switchTo(context: Context, key: String) {
        val app = context.applicationContext
        val old = Store(app).profile
        if (old == key) return
        Store(app).active = key
        changed(app)
        io.execute {
            try {
                Push.unregister(app, old)
            } catch (e: Exception) {
                // Its pushes are ignored anyway while it is not in use.
            }
            activate(app)
        }
    }

    /** Brings the profile in use up to date and registers it for pushes. Background thread. */
    fun activate(context: Context) {
        try {
            refresh(context)
            Store(context).pushStatus = Push.sync(context)
        } catch (e: Exception) {
            remember(context, "switching profile", e)
        }
        changed(context)
    }

    /**
     * Sends "thinking of you" and returns the short label for the widget.
     * Tries twice. Call from a background thread.
     */
    fun send(context: Context, timeoutMs: Int = 8000): String {
        val profile = Store(context).profile
        var problem: Exception? = null
        for (attempt in 1..2) {
            try {
                val state = Api.call(context, "ping", null, timeoutMs, profile)
                // The tap has been delivered. A problem while redrawing must not hide that.
                try {
                    applyState(context, state, profile)
                    Store(context).lastError = null
                } catch (e: Exception) {
                    remember(context, "after sending", e)
                }
                return if (state.optBoolean("repeat")) "Already sent" else "Sent"
            } catch (e: ApiException) {
                return if (e.status == 403) "Open app to set up" else "Failed (${e.status})"
            } catch (e: Exception) {
                problem = e
                if (attempt == 1) Thread.sleep(500)
            }
        }
        val failure = problem ?: return "Failed"
        remember(context, "sending", failure)
        return when (failure) {
            is UnknownHostException -> "No internet"
            is SocketTimeoutException -> "Timed out"
            else -> failure.javaClass.simpleName
        }
    }

    /** Keeps a description of a problem so it can be read on the app screen. */
    fun remember(context: Context, where: String, e: Exception) {
        val place = e.stackTrace.firstOrNull { it.className.startsWith("com.friendlamp") }
        val at = if (place != null) " at ${place.fileName}:${place.lineNumber}" else ""
        Store(context).lastError =
            clock(System.currentTimeMillis()) + " while $where: ${e.javaClass.simpleName}: ${e.message}$at"
    }

    /** Asks the server for the current state of the profile in use. Background thread. */
    fun refresh(context: Context) {
        val store = Store(context)
        if (!store.configured) return
        applyState(context, Api.call(context, "state", null, 8000, store.profile), store.profile)
    }

    /**
     * How long ago a server time was. With seconds for the first four minutes
     * ("45 s ago", "3 min 45 s ago"), then "5 min ago", then the clock time or date.
     * `skew` is this phone's clock minus the server's.
     */
    fun whenText(timestamp: Long, skew: Long = 0L): String {
        val seconds = maxOf(0L, (System.currentTimeMillis() - skew - timestamp) / 1000L)
        if (seconds < 5) return "just now"
        if (seconds < 60) return "$seconds s ago"
        if (seconds < 240) return "${seconds / 60} min ${seconds % 60} s ago"
        val minutes = (seconds + 30) / 60
        if (minutes < 60) return "$minutes min ago"
        val date = Date(timestamp)
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        val days = daysAgo(timestamp)
        return when {
            days <= 0 -> {
                val hours = (minutes + 30) / 60
                "$hours " + (if (hours == 1L) "hour" else "hours") + " ago · $clock"
            }
            days == 1 -> "Yesterday · $clock"
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(date) + " · $clock"
        }
    }

    fun clock(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    private fun daysAgo(timestamp: Long): Int {
        fun midnight(time: Long): Long = Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return ((midnight(System.currentTimeMillis()) - midnight(timestamp)) / 86_400_000L).toInt()
    }
}
