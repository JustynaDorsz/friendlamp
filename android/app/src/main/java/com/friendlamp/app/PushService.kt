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
        // Only the profile in use lights the lamp and shows a notification.
        if (Lamp.received(applicationContext, name, timestamp, message.sentTime, data["to"])) {
            Notifier.show(applicationContext)
        }
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
                return "Not set up on the server yet."
            }
            store.fcmConfig = config.toString()
        }
        if (!init(context)) return "Could not start notifications."
        val token = Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
        register(context, token)
        return "On for this device."
    }

    /** Registers this phone for pushes of the profile in use. */
    fun register(context: Context, token: String) {
        val store = Store(context)
        if (!store.configured) return
        val marker = token + "|" + store.code + "|" + store.name
        if (store.fcmRegistered == marker) return
        val body = JSONObject().put("subscription", JSONObject().put("fcm", token))
        Api.call(context, "subscribe", body, 8000, store.profile)
        store.fcmRegistered = marker
    }

    /** Tells the server to stop sending pushes for a profile that is not in use. */
    fun unregister(context: Context, profile: String) {
        val store = Store(context, profile)
        if (!store.configured) return
        Api.call(context, "subscribe", JSONObject().put("subscription", JSONObject.NULL), 8000, profile)
        store.fcmRegistered = null
    }
}

object Notifier {
    private const val CHANNEL = "lamp"
    private const val CHANNEL_SENDING = "sending"
    private const val CHANNEL_UPDATES = "updates"
    private const val ID = 1
    const val SENDING_ID = 2
    private const val UPDATE_ID = 3

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL, "Thinking of you", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "When your friend lights your lamp"
        manager.createNotificationChannel(channel)
        val sending = NotificationChannel(CHANNEL_SENDING, "Sending", NotificationManager.IMPORTANCE_MIN)
        sending.description = "Shown for a moment while a widget tap is being sent"
        sending.setShowBadge(false)
        manager.createNotificationChannel(sending)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** Shown only when Android insists on a confirmation before installing an update. */
    @SuppressLint("MissingPermission")
    fun updateReady(context: Context, confirm: Intent) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 4, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_lamp)
            .setContentTitle("FriendLamp update")
            .setContentText("Tap to install the new version")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(UPDATE_ID, notification)
    }

    /** The brief notice Android requires while the widget tap is being sent. */
    fun sending(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_SENDING)
            .setSmallIcon(R.drawable.ic_stat_lamp)
            .setContentTitle("FriendLamp")
            .setContentText("Sending\u2026")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()

    /** Shows the notification; it removes itself when the lamp goes dark. */
    @SuppressLint("MissingPermission")
    fun show(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val remaining = maxOf(5_000L, Store(context).litUntil - System.currentTimeMillis())
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lamp)
            .setColor(Store(context).friendColor)
            .setContentTitle("FriendLamp")
            .setContentText("Status check")
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
