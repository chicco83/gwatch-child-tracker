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
 * v0.14.0 (2026-09-23): il pulsante NON viene piu' disabilitato con
 * GPS assente (restava bloccato finche' il tracking automatico non
 * riceveva un fix, vedi ui/MainActivity.kt): questo stato cambia solo
 * l'etichetta ("GPS assente, tocca per riprovare").
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

    // v0.18.0 (2026-09-23): richiesta utente — durante il tentativo il
    // pulsante deve dire "Invio posizione in corso…" (prima c'era solo un
    // Toast e il testo non cambiava). true dal tocco (MainActivity) fino
    // all'esito di LocationRequestWorker.
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    // v0.24.0 (2026-09-23): istante di inizio dell'invio (per la barra di
    // progresso del pulsante) e istante dell'ultimo invio riuscito (per il
    // verde di conferma). Richiesta utente. Precedente:
    //     fun markSending(value: Boolean) { _sending.value = value }
    private val _sendingSince = MutableStateFlow<Long?>(null)
    val sendingSince: StateFlow<Long?> = _sendingSince.asStateFlow()

    private val _lastSentAt = MutableStateFlow<Long?>(null)
    val lastSentAt: StateFlow<Long?> = _lastSentAt.asStateFlow()

    fun markSending(value: Boolean) {
        // Il tocco e il worker chiamano entrambi markSending(true): si tiene
        // l'inizio del primo, cosi' la barra non riparte da zero.
        if (value && !_sending.value) _sendingSince.value = System.currentTimeMillis()
        if (!value) _sendingSince.value = null
        _sending.value = value
    }

    fun markSent() {
        _lastSentAt.value = System.currentTimeMillis()
    }
}
