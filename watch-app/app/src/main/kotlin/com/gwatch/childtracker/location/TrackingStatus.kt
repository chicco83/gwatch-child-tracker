package com.gwatch.childtracker.location

import android.os.SystemClock

/**
 * Versione: 0.1.0 (2026-09-23)
 *
 * Stato in memoria del tracking automatico (LocationTrackingService),
 * letto dalla diagnostica della Ricerca GPS (ui/GpsSearchScreen.kt).
 * Introdotto dopo che lo storico su Firestore ha mostrato il crollo dei
 * punti automatici dal 20/9 (181-278/giorno prima, 0-26 dopo) mentre
 * Google Maps sul watch ottiene la posizione: il servizio non scriveva
 * nessun log, impossibile capire se fosse vivo e se ricevesse posizioni.
 * Non persistito: dopo un riavvio del processo riparte da "mai".
 */
object TrackingStatus {
    @Volatile var serviceRunning = false
    @Volatile var priorityLabel: String? = null
    @Volatile var lastFixElapsed = 0L
    @Volatile var fixCount = 0
    @Volatile var lastError: String? = null
    // 2026-09-23: "accensione watch", "aggiornamento app" o "apertura app".
    @Volatile var startedBy: String? = null

    fun onFix() {
        lastFixElapsed = SystemClock.elapsedRealtime()
        fixCount++
    }

    // Secondi dall'ultimo punto ricevuto dal servizio, null se mai.
    fun lastFixAgeS(): Long? =
        lastFixElapsed.takeIf { it != 0L }?.let { (SystemClock.elapsedRealtime() - it) / 1000 }
}
