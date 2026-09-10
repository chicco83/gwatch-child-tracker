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
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Avvisi SOS e geofence", NotificationManager.IMPORTANCE_HIGH),
            )
            // v0.20.0 (2026-09-10): canale separato per l'allarme uscita
            // zona (toggle per-zona in GeofenceScreen.kt). Suono e
            // vibrazione sono gestiti a mano da ExitAlarmService, in
            // loop finche' non lo si ferma: se il canale suonasse anche
            // il proprio default si sovrapporrebbe al loop.
            manager.createNotificationChannel(
                NotificationChannel(ALARM_CHANNEL_ID, "Allarme uscita zona", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
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
        const val ALARM_CHANNEL_ID = "exit_alarm"
    }
}
