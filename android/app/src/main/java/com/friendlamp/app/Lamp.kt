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

    /** Stores what the server reported and redraws the widget. */
    fun applyState(context: Context, state: JSONObject) {
        val store = Store(context)
        // Server times are converted to this phone's clock.
        val skew = System.currentTimeMillis() - state.optLong("now", System.currentTimeMillis())
        val mine = state.optJSONObject("me")?.optLong("lastPing", 0L) ?: 0L
        store.myLastPing = mine

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

    /** The other person just thought of you (a push arrived). */
    fun received(context: Context, name: String, timestamp: Long, sentAt: Long) {
        val store = Store(context)
        val now = System.currentTimeMillis()
        store.otherName = name
        store.otherLastPing = timestamp
        store.litBy = "other"
        store.litAt = minOf(if (sentAt > 0L) sentAt else now, now)
        val old = try {
            JSONArray(store.history)
        } catch (e: Exception) {
            JSONArray()
        }
        val updated = JSONArray().put(timestamp)
        for (i in 0 until minOf(old.length(), 19)) {
            if (old.optLong(i) != timestamp) updated.put(old.optLong(i))
        }
        store.history = updated.toString()
        changed(context)
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
     * Sends "thinking of you" and returns the short label for the widget.
     * Tries twice. Call from a background thread.
     */
    fun send(context: Context, timeoutMs: Int = 8000): String {
        var problem: Exception? = null
        for (attempt in 1..2) {
            try {
                val state = Api.call(context, "ping", null, timeoutMs)
                // The tap has been delivered. A problem while redrawing must not hide that.
                try {
                    applyState(context, state)
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

    /** Asks the server for the current state. Call from a background thread. */
    fun refresh(context: Context) {
        if (!Store(context).configured) return
        applyState(context, Api.call(context, "state"))
    }

    /** "5 min ago", or the clock time / date for older entries. */
    fun whenText(timestamp: Long): String {
        val seconds = maxOf(0L, (System.currentTimeMillis() - timestamp) / 1000L)
        if (seconds < 45) return "just now"
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
