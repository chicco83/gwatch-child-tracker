package com.gwatch.childtracker.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import java.io.File
import com.gwatch.childtracker.phone.util.Constants
import com.google.firebase.messaging.FirebaseMessaging
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
            // 2026-09-18: canale separato per l'allarme SOS, stesso
            // identico motivo di ALARM_CHANNEL_ID sopra — suono/vibrazione
            // gestiti a mano da SosAlarmService (AudioAttributes.
            // USAGE_ALARM, bypassa il volume suoneria/silenzioso), il
            // canale non deve suonare anche il proprio default sopra.
            manager.createNotificationChannel(
                NotificationChannel(SOS_ALARM_CHANNEL_ID, "Allarme SOS", NotificationManager.IMPORTANCE_HIGH).apply {
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

        // Bug (2026-09-18): questa chiamata era stata
        // messa DOPO la chiusura di onCreate() invece che al suo interno —
        // uno statement eseguibile piazzato direttamente nel corpo della
        // classe, non valido in Kotlin (la app non compilava piu'). Prima
        // qui si faceva l'iscrizione al topic FCM globale dei genitori
        // (Constants.FCM_PARENTS_TOPIC) — rimossa in Fase 2 (vedi
        // Constants.kt/AppViewModel.kt): nessuna iscrizione a un topic
        // per-bambino e' possibile qui, perche' a questo punto (avvio
        // dell'app, prima del login) non si sa ancora quale famiglia/
        // quali figli abbia l'utente.
        //
        // v0.9.0 (2026-09-23): disiscrizione una tantum dal vecchio topic
        // globale "parents" — installazioni esistenti restano iscritte
        // finche' non lo fanno esplicitamente, continuando a ricevere le
        // push cross-famiglia che la Fase 2 elimina lato backend/nuove
        // iscrizioni. unsubscribeFromTopic e' idempotente (nessun errore
        // se gia' non iscritti), quindi va bene richiamarla ad ogni avvio
        // invece di tenere un flag "gia' fatto" in SharedPreferences.
        runCatching { FirebaseMessaging.getInstance().unsubscribeFromTopic(Constants.LEGACY_PARENTS_TOPIC) }
    }

    companion object {
        const val CHANNEL_ID = "alerts"
        const val ALARM_CHANNEL_ID = "exit_alarm"
        const val SOS_ALARM_CHANNEL_ID = "sos_alarm"
    }
}
