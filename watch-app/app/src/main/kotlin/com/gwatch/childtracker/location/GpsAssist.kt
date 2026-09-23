package com.gwatch.childtracker.location

import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log

/**
 * Versione: 0.1.0 (2026-09-23)
 *
 * "Aiuto" al chip GPS prima di una ricerca: chiede al sistema di
 * iniettare l'ora esatta e le effemeridi/almanacco scaricati dalla rete
 * (A-GPS: PSDS, ex XTRA), come facevano i vecchi navigatori scaricando
 * le effemeridi per un fix piu' rapido.
 *
 * Perche' (test su Watch4 reale, 2026-09-23): con 10-14 satelliti
 * "usati" il GPS di sistema non produceva nessuna posizione (ultima
 * posizione di sistema: nessuna) finche' non e' stato aperto Google
 * Maps — a quel punto il fix e' arrivato subito anche alla nostra app.
 * Maps (via servizi Google) fornisce questi dati di aiuto, le nostre
 * richieste no. Qui li chiediamo esplicitamente.
 *
 * - sendExtraCommand e' un'API di sistema standard; i comandi sono
 *   stringhe note al driver GNSS di Android. Un comando non supportato
 *   viene semplicemente ignorato (nessun errore), per questo si mandano
 *   sia il nome nuovo (Android 11+, "force_psds_injection") sia il
 *   vecchio ("force_xtra_injection").
 * - Richiede ACCESS_LOCATION_EXTRA_COMMANDS (permesso "normale",
 *   concesso in automatico all'installazione, nessuna richiesta
 *   all'utente) — vedi AndroidManifest.xml.
 * - Limitato a una volta ogni MIN_INTERVAL_MS (il download delle
 *   effemeridi usa dati LTE), tranne quando force=true (azione esplicita
 *   dell'utente dalla schermata Ricerca GPS).
 */
object GpsAssist {
    private const val TAG = "GpsAssist"
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L

    @Volatile
    private var lastInjectionElapsed = 0L

    fun injectAssistance(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastInjectionElapsed != 0L && now - lastInjectionElapsed < MIN_INTERVAL_MS) return
        lastInjectionElapsed = now

        val locationManager = context.getSystemService(LocationManager::class.java) ?: return
        listOf("force_time_injection", "force_psds_injection", "force_xtra_injection").forEach { command ->
            val accepted = runCatching {
                locationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, command, null)
            }.getOrElse {
                Log.w(TAG, "injectAssistance: $command fallito", it)
                false
            }
            Log.i(TAG, "injectAssistance: $command -> $accepted")
        }
    }
}
