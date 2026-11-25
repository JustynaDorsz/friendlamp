package com.friendlamp.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Receives "thinking of you" from the server through Firebase. */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        try {
            Push.register(applicationContext, token)
        } catch (e: Exception) {
            // Sent again the next time the app is opened.
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "ping") return
        val name = data["name"] ?: "Your friend"
        val timestamp = data["ts"]?.toLongOrNull() ?: System.currentTimeMillis()
        Lamp.received(applicationContext, name, timestamp, message.sentTime)
        Notifier.show(applicationContext, name)
    }
}

object Push {
    /** Starts Firebase from the settings the server gave us. Safe to call often. */
    fun init(context: Context): Boolean {
        val app = context.applicationContext
        if (FirebaseApp.getApps(app).isNotEmpty()) return true
        val raw = Store(app).fcmConfig ?: return false
        return try {
            val config = JSONObject(raw)
            val options = FirebaseOptions.Builder()
                .setProjectId(config.getString("projectId"))
                .setApplicationId(config.getString("appId"))
                .setApiKey(config.getString("apiKey"))
                .setGcmSenderId(config.getString("senderId"))
                .build()
            FirebaseApp.initializeApp(app, options)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Makes sure this phone is registered for pushes. Call from a background
     * thread. Returns a short status line for the screen.
     */
    fun sync(context: Context): String {
        val store = Store(context)
        if (!store.configured) return ""
        if (store.fcmConfig == null) {
            val config = Api.request("GET", "fcm-config", null)
            if (!config.optBoolean("configured")) {
                return "Instant notifications are not set up on the server yet. The widget still sends, and checks for news about every 30 minutes."
            }
            store.fcmConfig = config.toString()
        }
        if (!init(context)) return "Could not start notifications."
        val token = Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
        register(context, token)
        return "Notifications are on for this device."
    }

    fun register(context: Context, token: String) {
        val store = Store(context)
        if (!store.configured) return
        val marker = token + "|" + store.code + "|" + store.name
        if (store.fcmRegistered == marker) return
        Api.call(context, "subscribe", JSONObject().put("subscription", JSONObject().put("fcm", token)))
        store.fcmRegistered = marker
    }
}

object Notifier {
    private const val CHANNEL = "lamp"
    private const val ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Thinking of you", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "When your friend lights your lamp"
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Shows the notification; it removes itself when the lamp goes dark. */
    @SuppressLint("MissingPermission")
    fun show(context: Context, name: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val remaining = maxOf(5_000L, Store(context).litUntil - System.currentTimeMillis())
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lamp)
            .setColor(0xFFFFB347.toInt())
            .setContentTitle("FriendLamp")
            .setContentText("$name is thinking of you")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(remaining)
            .setContentIntent(open)
            .build()
        manager.notify(ID, notification)
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(ID)
}
