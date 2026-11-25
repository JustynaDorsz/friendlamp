package com.friendlamp.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
        // Firebase must be running before a push can be delivered to PushService.
        Push.init(this)
        try {
            UpdateJob.schedule(this)
        } catch (e: Exception) {
            // Updates are still checked when the app is opened or the widget is tapped.
        }
    }
}
