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
}
