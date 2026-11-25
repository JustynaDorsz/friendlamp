package com.friendlamp.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Sends "thinking of you" after a widget tap. It runs as a short foreground
 * service for a second or two, so it can reach the network from the background.
 */
class SendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = Notifier.sending(this)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.SENDING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else {
                startForeground(Notifier.SENDING_ID, notification)
            }
        } catch (e: Exception) {
            Lamp.remember(this, "showing the sending notice", e)
        }
        val app = applicationContext
        Lamp.io.execute {
            try {
                LampWidget.finishSend(app, Lamp.send(app))
            } catch (e: Exception) {
                Lamp.remember(app, "the send service", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            // A tap is also a good moment to finish setting up notifications
            // (if the server got its Firebase settings after the app was last opened)
            // and to look for a newer build.
            if (Store(app).fcmRegistered == null) {
                try {
                    Store(app).pushStatus = Push.sync(app)
                } catch (e: Exception) {
                    Lamp.remember(app, "turning on notifications", e)
                }
            }
            Updater.checkSoon(app)
        }
        return START_NOT_STICKY
    }

    /** Android stops short services that run too long; ours never should. */
    override fun onTimeout(startId: Int) {
        stopSelf(startId)
    }
}
