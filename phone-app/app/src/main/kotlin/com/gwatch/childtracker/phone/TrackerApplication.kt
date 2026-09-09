package com.gwatch.childtracker.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import java.io.File
import org.osmdroid.config.Configuration

class TrackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Avvisi SOS e geofence",
                NotificationManager.IMPORTANCE_HIGH,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        // osmdroid: user agent obbligatorio (policy dei tile server
        // OpenStreetMap, altrimenti le richieste vengono rifiutate) +
        // cache nella cartella privata dell'app, per non richiedere
        // permessi di storage esterno.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
    }

    companion object {
        const val CHANNEL_ID = "alerts"
    }
}
