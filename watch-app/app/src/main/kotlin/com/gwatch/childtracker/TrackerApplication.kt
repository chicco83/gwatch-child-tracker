package com.gwatch.childtracker

// Storico versioni
// v0.1.0/v0.2.0: canale "tracking" (silenzioso, foreground service) e
//   canale "messages" (IMPORTANCE_HIGH) per chat/SOS/notifiche varie.
// v0.6.1 (2026-09-10): bug segnalato — i messaggi arrivavano (visibili
//   aprendo il pannello notifiche) ma non svegliavano il watch, niente
//   vibrazione, niente card a schermo intero da fermo/home. Causa:
//   IMPORTANCE_HIGH da sola non basta, la vibrazione va abilitata
//   esplicitamente sul canale (enableVibration(false) e' il default
//   alla creazione, anche per canali HIGH) — senza, Wear OS tratta la
//   notifica come "silenziosa" e non la considera abbastanza
//   interruttiva da riattivare lo schermo. Aggiunto
//   enableVibration(true) + pattern esplicito sul canale "messages".
// v0.6.2 (2026-09-10): il fix sopra non aveva alcun effetto reale sul
//   watch — testato di nuovo dopo la ricompilazione, ancora nessuna
//   vibrazione/popup. Causa: su Android le impostazioni di un
//   NotificationChannel sono IMMUTABILI una volta creato — richiamare
//   createNotificationChannel() con lo stesso ID (qui "messages", gia'
//   esistente sul dispositivo da build precedenti) non aggiorna niente,
//   il sistema ignora silenziosamente i nuovi parametri. L'unico modo
//   per applicare le nuove impostazioni e' un ID canale nuovo, cosi'
//   Android ne crea uno da zero con enableVibration(true) di base.
//   ID cambiato da "messages" a "messages_v2".

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
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                enableLights(true)
            },
        )
    }

    companion object {
        const val TRACKING_CHANNEL_ID = "tracking"

        // v0.6.2: "messages" -> "messages_v2" per forzare Android a
        // creare un canale nuovo con la vibrazione abilitata (vedi
        // storico versioni sopra) — le impostazioni di un canale
        // esistente non si possono aggiornare via codice.
        const val MESSAGES_CHANNEL_ID = "messages_v2"
    }
}
