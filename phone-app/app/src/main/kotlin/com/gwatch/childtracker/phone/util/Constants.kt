package com.gwatch.childtracker.phone.util

object Constants {
    // v0.5.0 (2026-09-11): rimosso DEVICE_ID — supportava un solo
    // figlio/dispositivo fisso (MVP), sostituito da N bambini dinamici
    // (query live su "devices", vedi AppViewModel.children) in fase 3/4
    // (vedi CONTEXT.md).

    // Finestra di storico mostrata sulla mappa: il backend conserva 12
    // mesi (vedi CONTEXT.md), ma qui mostriamo solo le ultime ore per
    // restare leggibili e veloci da caricare.
    const val HISTORY_WINDOW_HOURS = 48L

    // v0.18.0 (chat): stesso default di watch-app/config/BackendConfig.kt
    // — non e' un segreto (nessun token qui, l'auth verso
    // /api/send-message-to-child usa l'ID token Firebase del genitore
    // preso a runtime), quindi nessun local.properties necessario.
    const val BACKEND_BASE_URL = "https://gwatch-child-tracker.vercel.app"

    // Topic FCM dei genitori — il backend invia le push (chat, SOS,
    // geofence) sul topic invece che sui token array letti da Firestore. La
    // subscription e fatta in TrackerApplication.onCreate e ri-fatta in
    // FcmService.onNewToken (idempotente). Vedi send-message.js/trigger-event.js.
    const val FCM_PARENTS_TOPIC = "parents"
}
