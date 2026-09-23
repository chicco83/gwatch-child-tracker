package com.gwatch.childtracker.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Riceve le transizioni di geofence dal sistema. Non fa lavoro di rete
 * qui direttamente (un BroadcastReceiver non e' adatto a operazioni
 * lunghe/non garantite): delega a GeofenceEventWorker, che ha retry
 * automatico se il backend non risponde.
 *
 * v0.4.0 (2026-09-23): bug trovato diagnosticando una segnalazione
 * dell'utente (evento "uscito da zona casa" mai arrivato una mattina,
 * nessun modo di sapere se il rilevamento fosse avvenuto o no).
 * L'orario della
 * transizione non veniva mai passato al worker: se il primo invio
 * falliva (rete assente) e WorkManager ritentava piu' tardi,
 * GeofenceEventWorker.doWork() finiva per mandare
 * System.currentTimeMillis() letto AL MOMENTO DEL RETRY, non
 * dell'evento reale — un evento arrivato ore dopo il passaggio di
 * confine sembrava avvenuto all'orario del retry riuscito, fuorviante
 * per capire cosa fosse successo davvero. Catturato qui, al momento
 * del rilevamento, e passato al worker (vedi KEY_TIMESTAMP).
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val type = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "geofence_enter"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "geofence_exit"
            else -> return
        }

        val zoneId = event.triggeringGeofences?.firstOrNull()?.requestId ?: return
        val location = event.triggeringLocation
        // L'orario vero della transizione, non quello (eventualmente
        // molto piu' tardo) di un retry del worker — vedi Storico
        // versioni sopra.
        val detectedAtMillis = System.currentTimeMillis()

        val data = Data.Builder()
            .putString(GeofenceEventWorker.KEY_TYPE, type)
            .putString(GeofenceEventWorker.KEY_ZONE_ID, zoneId)
            .putDouble(GeofenceEventWorker.KEY_LAT, location?.latitude ?: 0.0)
            .putDouble(GeofenceEventWorker.KEY_LON, location?.longitude ?: 0.0)
            .putLong(GeofenceEventWorker.KEY_TIMESTAMP, detectedAtMillis)
            .build()

        val work = OneTimeWorkRequestBuilder<GeofenceEventWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }
}
