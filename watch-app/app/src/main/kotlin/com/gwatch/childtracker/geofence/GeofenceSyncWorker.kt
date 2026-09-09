package com.gwatch.childtracker.geofence

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.gwatch.childtracker.network.BackendClient
import kotlinx.coroutines.tasks.await

/**
 * Sincronizza le geofence configurate dal genitore (GET
 * /api/device-config) con la Geofencing API di Android: le zone
 * vengono valutate a livello OS, non con polling continuo dell'app
 * (risparmio batteria, vedi CONTEXT.md). Schedulato periodicamente
 * (le zone cambiano raramente) e una volta subito dopo il boot/avvio.
 */
class GeofenceSyncWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @Suppress("MissingPermission") // permessi verificati da MainActivity prima di avviare la sync
    override suspend fun doWork(): Result {
        val zones = BackendClient().fetchDeviceConfig()
        val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(appContext)
        val pendingIntent = geofencePendingIntent()

        try {
            // Rimuove sempre le geofence precedenti prima di
            // ri-registrare: piu' semplice e robusto che calcolare un
            // diff, il costo (poche zone) e' trascurabile.
            geofencingClient.removeGeofences(pendingIntent).await()

            if (zones.isEmpty()) return Result.success()

            val geofences = zones.map { zone ->
                Geofence.Builder()
                    .setRequestId(zone.id)
                    .setCircularRegion(zone.lat, zone.lon, zone.radiusMeters)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    // Piccolo ritardo prima di notificare l'uscita, per
                    // evitare falsi allarmi ai bordi della zona (best
                    // practice, vedi CONTEXT.md).
                    .setLoiteringDelay(30_000)
                    .build()
            }

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(geofences)
                .build()

            geofencingClient.addGeofences(request, pendingIntent).await()
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }

    private fun geofencePendingIntent(): PendingIntent {
        val intent = Intent(appContext, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    companion object {
        const val PERIODIC_WORK_NAME = "geofence-sync-periodic"
        const val ONE_SHOT_WORK_NAME = "geofence-sync-oneshot"
    }
}
