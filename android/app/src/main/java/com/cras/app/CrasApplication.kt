package com.cras.app

import android.app.Application
import com.cras.app.notification.CrasNotifications

class CrasApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrasNotifications.createNotificationChannel(this)
        CrasNotifications.initializeFirebase(this)
    }
}
