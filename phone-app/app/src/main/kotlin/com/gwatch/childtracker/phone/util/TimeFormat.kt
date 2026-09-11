package com.gwatch.childtracker.phone.util

// Storico versioni
// v0.1.0: prima versione, prefisso "Aggiornato" (es. "Aggiornato 5 min fa").
// v0.2.0 (2026-09-11): richiesta utente — rinominato in "Ultima posizione
//   ricevuta", piu' esplicito su cosa indica davvero questo timestamp
//   (l'ultimo fix GPS arrivato dal watch, non un generico "aggiornamento"
//   della schermata).

import java.util.concurrent.TimeUnit

fun formatRelativeTime(millis: Long): String {
    val diffMs = System.currentTimeMillis() - millis
    if (diffMs < 0) return "Ultima posizione ricevuta adesso"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    return when {
        minutes < 1 -> "Ultima posizione ricevuta adesso"
        minutes < 60 -> "Ultima posizione ricevuta $minutes min fa"
        minutes < 24 * 60 -> "Ultima posizione ricevuta ${minutes / 60} h fa"
        else -> "Ultima posizione ricevuta ${minutes / (24 * 60)} giorni fa"
    }
}
