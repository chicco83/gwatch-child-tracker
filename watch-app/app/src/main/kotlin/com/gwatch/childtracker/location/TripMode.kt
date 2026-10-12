package com.gwatch.childtracker.location

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Versione: 0.1.0 (2026-10-12)
 *
 * "Viaggio previsto", scelto dal genitore sulla phone-app (backend
 * parent-command.js "set_trip_expected"): fino alla scadenza il tracking in
 * movimento non usa il punto ogni 2 minuti con GPS ma la cadenza di quando il
 * watch e' fermo (ogni 10 minuti, priorita' bilanciata o alta se il genitore
 * ha attivato "Alta precisione da fermo"). Serve a non scaricare il watch in
 * un viaggio lungo gia' noto (analisi consumi del 12/10/2026: 5 ore di GPS a
 * un punto al minuto in auto).
 *
 * Arriva dalla push "trip_mode" (FcmService) e a ogni sync da device-config
 * (GeofenceSyncWorker); salvata in SharedPreferences come scadenza in
 * millisecondi (0 = nessun viaggio previsto). Se cambia, il servizio di
 * tracking riapplica subito la richiesta. Alla scadenza il servizio se ne
 * accorge al punto successivo (LocationTrackingService).
 */
object TripMode {
    private const val TAG = "TripMode"
    private const val PREFS = "trip_mode"
    private const val KEY_UNTIL = "until_millis"

    fun untilMillis(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UNTIL, 0L)

    fun isExpected(context: Context): Boolean = untilMillis(context) > System.currentTimeMillis()

    /** expiresAtMillis <= 0 (o gia' passato) = nessun viaggio previsto. */
    fun set(context: Context, expiresAtMillis: Long) {
        val newUntil = if (expiresAtMillis > System.currentTimeMillis()) expiresAtMillis else 0L
        val wasExpected = isExpected(context)
        if (untilMillis(context) == newUntil) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_UNTIL, newUntil).apply()
        Log.i(TAG, "viaggio previsto fino a: ${if (newUntil == 0L) "nessuno" else newUntil}")
        // Il servizio riapplica la richiesta solo se cambia lo stato attivo/non attivo.
        if (wasExpected == (newUntil != 0L)) return
        if (!TrackingStatus.serviceRunning) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java)
                    .putExtra(LocationTrackingService.EXTRA_REAPPLY, true),
            )
        }.onFailure { Log.w(TAG, "riapplicazione al servizio fallita", it) }
    }
}
