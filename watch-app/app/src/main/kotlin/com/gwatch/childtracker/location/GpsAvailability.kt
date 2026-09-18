package com.gwatch.childtracker.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stato condiviso "il GPS del watch sta attualmente rispondendo?" —
 * stesso pattern di sos/SosState.kt (singleton in-process, non
 * persistito: non serve sopravvivere al riavvio del processo).
 *
 * v0.8.0 (2026-09-18): introdotto dopo la diagnosi via Logcat reale che
 * ha confermato "fix GPS non disponibile (null)" come causa di
 * "Invia posizione"/SOS che sembravano non fare nulla (WorkManager
 * ritenta in silenzio, vedi Storico versioni di LocationRequestWorker.kt/
 * SosWorker.kt). Richiesta utente: disabilitare "Invia posizione"
 * quando non c'e' un fix, con un testo esplicito, cosi' l'assenza di
 * segnale e' visibile subito invece di sembrare un pulsante rotto — SOS
 * resta SEMPRE abilitato (non va mai bloccato: e' spesso proprio in
 * mancanza di segnale, es. al chiuso, che serve di piu').
 *
 * `null` = non ancora determinato (nessun tentativo di fix da quando
 * l'app e' partita): il pulsante "Invia posizione" resta abilitato
 * finche' non sappiamo per certo che il GPS non risponde, per non
 * bloccarlo inutilmente al primo avvio.
 *
 * Aggiornato da due fonti, entrambe gia' esistenti (nessun nuovo
 * polling GPS aggiunto solo per questo stato):
 * - LocationTrackingService (tracking periodico automatico, ogni
 *   10'/1'): true ad ogni fix ricevuto.
 * - LocationRequestWorker/SosWorker (invio manuale/SOS): true se il
 *   fix live arriva, false se doWork() lo trova assente.
 */
object GpsAvailability {
    private val _available = MutableStateFlow<Boolean?>(null)
    val available: StateFlow<Boolean?> = _available.asStateFlow()

    fun markAvailable() {
        _available.value = true
    }

    fun markUnavailable() {
        _available.value = false
    }
}
