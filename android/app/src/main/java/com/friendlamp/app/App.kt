package com.friendlamp.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
        // Firebase must be running before a push can be delivered to PushService.
        Push.init(this)
    }
}
