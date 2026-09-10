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

        val data = Data.Builder()
            .putString(GeofenceEventWorker.KEY_TYPE, type)
            .putString(GeofenceEventWorker.KEY_ZONE_ID, zoneId)
            .putDouble(GeofenceEventWorker.KEY_LAT, location?.latitude ?: 0.0)
            .putDouble(GeofenceEventWorker.KEY_LON, location?.longitude ?: 0.0)
            .build()

        val work = OneTimeWorkRequestBuilder<GeofenceEventWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }
}
