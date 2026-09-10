package com.gwatch.childtracker.phone.util

object Constants {
    // Deve corrispondere al DEVICE_ID hardcoded nel backend (vedi
    // backend/api/ingest-location.js): MVP a singolo figlio/dispositivo.
    const val DEVICE_ID = "figlio"

    // Finestra di storico mostrata sulla mappa: il backend conserva 12
    // mesi (vedi CONTEXT.md), ma qui mostriamo solo le ultime ore per
    // restare leggibili e veloci da caricare.
    const val HISTORY_WINDOW_HOURS = 48L

    // v0.18.0 (chat): stesso default di watch-app/config/BackendConfig.kt
    // — non e' un segreto (nessun token qui, l'auth verso
    // /api/send-message-to-child usa l'ID token Firebase del genitore
    // preso a runtime), quindi nessun local.properties necessario.
    const val BACKEND_BASE_URL = "https://gwatch-child-tracker.vercel.app"
}
