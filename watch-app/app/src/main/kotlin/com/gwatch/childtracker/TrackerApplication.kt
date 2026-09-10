package com.gwatch.childtracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class TrackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                TRACKING_CHANNEL_ID,
                "Tracking posizione",
                NotificationManager.IMPORTANCE_MIN, // silenziosa: e' un foreground service, non un alert
            ),
        )
        // v0.2.0: canale separato per la chat, visibile/sonoro a
        // differenza del tracking — un messaggio del genitore deve
        // farsi notare.
        manager.createNotificationChannel(
            NotificationChannel(
                MESSAGES_CHANNEL_ID,
                "Messaggi",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    companion object {
        const val TRACKING_CHANNEL_ID = "tracking"
        const val MESSAGES_CHANNEL_ID = "messages"
    }
}
