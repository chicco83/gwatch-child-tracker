package com.gwatch.childtracker.phone.util

object Constants {
    // Deve corrispondere al DEVICE_ID hardcoded nel backend (vedi
    // backend/api/ingest-location.js): MVP a singolo figlio/dispositivo.
    const val DEVICE_ID = "figlio"

    // Finestra di storico mostrata sulla mappa: il backend conserva 12
    // mesi (vedi CONTEXT.md), ma qui mostriamo solo le ultime ore per
    // restare leggibili e veloci da caricare.
    const val HISTORY_WINDOW_HOURS = 48L
}
