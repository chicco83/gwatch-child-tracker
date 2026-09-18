package com.gwatch.childtracker.phone.util

// Storico versioni
// v0.1.0: prima versione, prefisso "Aggiornato" (es. "Aggiornato 5 min fa").
// v0.2.0 (2026-09-11): richiesta utente — rinominato in "Ultima posizione
//   ricevuta", piu' esplicito su cosa indica davvero questo timestamp
//   (l'ultimo fix GPS arrivato dal watch, non un generico "aggiornamento"
//   della schermata).
// v0.3.0 (2026-09-18): StatusCard (MapScreen.kt) ora mostra un'etichetta
//   propria "Ultima posizione:" su una riga dedicata (vedi InfoLine) —
//   il prefisso "Ultima posizione ricevuta" qui dentro sarebbe stato
//   duplicato. Tolto: la funzione ora ritorna solo la parte relativa
//   ("5 min fa", "adesso", ecc.), e' compito del chiamante mettere
//   l'etichetta davanti.

import java.util.concurrent.TimeUnit

fun formatRelativeTime(millis: Long): String {
    val diffMs = System.currentTimeMillis() - millis
    if (diffMs < 0) return "adesso"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    return when {
        minutes < 1 -> "adesso"
        minutes < 60 -> "$minutes min fa"
        minutes < 24 * 60 -> "${minutes / 60} h fa"
        else -> "${minutes / (24 * 60)} giorni fa"
    }
}
