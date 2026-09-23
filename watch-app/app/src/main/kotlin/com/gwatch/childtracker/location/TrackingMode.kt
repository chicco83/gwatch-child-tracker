package com.gwatch.childtracker.location

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Versione: 0.1.0 (2026-09-24)
 *
 * Impostazione "alta precisione anche da fermo", scelta dal genitore
 * dalla phone-app (backend parent-command.js "set_tracking_mode"). Di
 * default spenta: da fermo il tracking usa la priorita' bilanciata (Wi-Fi/
 * celle, poca batteria), come prima della v0.20.0; con "Migliora
 * precisione" attiva sul watch e' sufficiente. Accesa: GPS acceso quando
 * serve ogni 10', piu' robusto ma consuma di piu'.
 *
 * Arriva dalla push "tracking_mode" (FcmService) e a ogni sync da
 * device-config (GeofenceSyncWorker), salvata in SharedPreferences; se
 * cambia, il servizio di tracking riapplica subito la richiesta.
 */
object TrackingMode {
    private const val TAG = "TrackingMode"
    private const val PREFS = "tracking_mode"
    private const val KEY_HIGH = "high_accuracy"

    fun isHighAccuracy(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HIGH, false)

    fun set(context: Context, highAccuracy: Boolean) {
        if (isHighAccuracy(context) == highAccuracy) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIGH, highAccuracy).apply()
        Log.i(TAG, "alta precisione da fermo: $highAccuracy")
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
