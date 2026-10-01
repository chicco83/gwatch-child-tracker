package com.gwatch.childtracker.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Riceve le transizioni di attivita' (fermo/in movimento) rilevate dal
 * sistema e le inoltra a LocationTrackingService, che decide il nuovo
 * intervallo di campionamento GPS. Componente volutamente sottile:
 * nessuna logica di sampling qui, solo smistamento.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        // 2026-10-01: conta solo l'ULTIMA transizione. Con il watch in
        // sospensione il sistema consegna gli eventi a gruppi: bastava un
        // "cammina" in un gruppo che finiva con "fermo" per passare in
        // "movimento" (GPS ogni minuto) e, non arrivando piu' nessun cambio,
        // restarci per ore da fermi. Precedente:
        //     val moving = result.transitionEvents.any { event ->
        //         event.activityType != DetectedActivity.STILL
        //     }
        val last = result.transitionEvents.maxByOrNull { it.elapsedRealTimeNanos } ?: return
        val moving = last.activityType != DetectedActivity.STILL

        val serviceIntent = Intent(context, LocationTrackingService::class.java)
            .putExtra(LocationTrackingService.EXTRA_MOVING, moving)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
