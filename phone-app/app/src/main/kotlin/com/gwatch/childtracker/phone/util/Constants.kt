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

    // v0.9.0 (2026-09-23): Fase 2 di qwen_plan.md (individuato da
    // qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — FCM_PARENTS_TOPIC
    // globale rimosso: qualunque telefono di qualunque famiglia iscritto
    // riceveva/sentiva suonare l'allarme di un bambino non proprio (vedi
    // backend/api/trigger-event.js/send-message.js/_lib/batteryAlerts.js
    // v0.18.0/0.6.0/0.2.0). Un topic per bambino, sottoscritto solo per i
    // propri figli dopo il login (vedi AppViewModel.kt/
    // TrackerApplication.kt). LEGACY_PARENTS_TOPIC resta solo per
    // disiscrivere le installazioni esistenti dal vecchio topic globale
    // (migrazione one-time, vedi TrackerApplication.kt).
    fun fcmChildTopic(childId: String) = "child-$childId"
    const val LEGACY_PARENTS_TOPIC = "parents"
}
