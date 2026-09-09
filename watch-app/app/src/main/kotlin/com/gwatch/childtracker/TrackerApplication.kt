package com.gwatch.childtracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class TrackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            TRACKING_CHANNEL_ID,
            "Tracking posizione",
            NotificationManager.IMPORTANCE_MIN, // silenziosa: e' un foreground service, non un alert
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val TRACKING_CHANNEL_ID = "tracking"
    }
}
