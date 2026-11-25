package com.friendlamp.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

/**
 * The home-screen lamp. Tapping it sends "thinking of you" without opening the
 * app; it is lit while the other person's last tap is recent.
 */
class LampWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context)
        // Also ask the server, in case a push was missed.
        val pending = goAsync()
        Lamp.io.execute {
            try {
                Lamp.refresh(context)
            } catch (e: Exception) {
                // No connection: keep showing what we have.
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SEND -> send(context)
            ACTION_UNLIT -> render(context)
            else -> super.onReceive(context, intent)
        }
    }

    private fun send(context: Context) {
        if (!Store(context).configured) {
            render(context)
            return
        }
        render(context, "Sending\u2026")
        // Preferred: a short foreground service. The system lets it use the network
        // even when the app is in the background with battery or data saver on.
        try {
            context.startForegroundService(Intent(context, SendService::class.java))
            return
        } catch (e: Exception) {
            Lamp.remember(context, "starting the send service", e)
        }
        // Fallback: send from here, within the few seconds a receiver is allowed.
        val pending = goAsync()
        Lamp.io.execute {
            try {
                finishSend(context, Lamp.send(context, 3000))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SEND = "com.friendlamp.app.SEND"
        const val ACTION_UNLIT = "com.friendlamp.app.UNLIT"

        /** Shows the result on the widget for a moment, then the normal label again. */
        fun finishSend(context: Context, label: String) {
            render(context, label)
            Thread.sleep(1500)
            render(context)
        }

        /** Redraws every FriendLamp widget. `flash` temporarily replaces the label. */
        fun render(context: Context, flash: String? = null) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, LampWidget::class.java))
            if (ids.isEmpty()) return

            val store = Store(app)
            val lit = store.configured && store.lit
            val label = flash ?: when {
                !store.configured -> "Tap to set up"
                lit -> (store.otherName ?: "") + " · " + Lamp.clock(store.otherLastPing)
                store.otherLastPing > 0L -> Lamp.whenText(store.otherLastPing).substringBefore(" · ")
                else -> "FriendLamp"
            }

            val views = RemoteViews(app.packageName, R.layout.widget_lamp)
            views.setImageViewResource(R.id.widget_lamp, if (lit) R.drawable.lamp_lit else R.drawable.lamp_unlit)
            views.setTextViewText(R.id.widget_label, label)
            views.setOnClickPendingIntent(R.id.widget_root, clickIntent(app, store.configured))
            manager.updateAppWidget(ids, views)

            if (lit) scheduleUnlit(app, store.litUntil)
        }

        private fun clickIntent(context: Context, configured: Boolean): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (configured) {
                val send = Intent(context, LampWidget::class.java).setAction(ACTION_SEND)
                PendingIntent.getBroadcast(context, 0, send, flags)
            } else {
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
            }
        }

        /** Wakes the widget up again when the lamp should go dark. */
        private fun scheduleUnlit(context: Context, at: Long) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val unlit = Intent(context, LampWidget::class.java).setAction(ACTION_UNLIT)
            val pending = PendingIntent.getBroadcast(
                context, 1, unlit, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, at + 500L, pending)
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC, at + 500L, pending)
                }
            } catch (e: SecurityException) {
                alarms.set(AlarmManager.RTC, at + 500L, pending)
            }
        }
    }
}
