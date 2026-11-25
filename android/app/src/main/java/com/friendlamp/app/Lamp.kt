package com.friendlamp.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
        store.myLastPing = state.optJSONObject("me")?.optLong("lastPing", 0L) ?: 0L

        val other = state.optJSONObject("other")
        if (other == null) {
            store.otherName = null
            store.otherLastPing = 0L
            store.history = "[]"
            store.litUntil = 0L
        } else {
            val last = other.optLong("lastPing", 0L)
            store.otherName = other.optString("name")
            store.otherLastPing = last
            store.history = (other.optJSONArray("history") ?: JSONArray()).toString()
            store.litUntil = if (last > 0L) last + skew + Config.LIT_MS else 0L
        }
        changed(context)
    }

    /** The other person just thought of you (a push arrived). */
    fun received(context: Context, name: String, timestamp: Long, sentAt: Long) {
        val store = Store(context)
        val now = System.currentTimeMillis()
        store.otherName = name
        store.otherLastPing = timestamp
        store.litUntil = minOf(if (sentAt > 0L) sentAt else now, now) + Config.LIT_MS
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
        LampWidget.render(context)
        onChange?.invoke()
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
