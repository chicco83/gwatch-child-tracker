package com.gwatch.childtracker.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.gwatch.childtracker.location.BatteryInfo
import com.gwatch.childtracker.location.GpsAvailability
import com.gwatch.childtracker.network.BackendClient
import kotlinx.coroutines.tasks.await

/**
 * Funzione di sicurezza piu' critica dell'app: prende un fix di
 * posizione ad alta precisione e lo invia come evento "sos". Va
 * accodato come lavoro espedito (vedi ui/MainActivity.kt,
 * setExpedited) per bypassare Doze/App Standby e partire il prima
 * possibile, senza vincoli di rete/batteria che ne ritardino
 * l'esecuzione — a differenza degli altri worker dell'app.
 */
class SosWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    // v0.5.0 (2026-09-18): stesso bug/fix di LocationRequestWorker.kt —
    // vedi lo storico versioni li' per il contesto completo (segnalato
    // dal primo test hardware reale, confermato dai log Vercel: zero
    // chiamate a /api/trigger-event). Aggiunto Log.w sui due casi che
    // bloccano l'invio prima ancora della chiamata di rete.
    // v0.6.0 (2026-09-18): stesso fix di LocationRequestWorker.kt v0.8.0
    // — GpsAvailability.markUnavailable()/markAvailable(), letto dalla UI
    // per lo stato del pulsante "Invia posizione" (SOS resta sempre
    // abilitato, non va mai bloccato).
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val hasPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "doWork: permesso ACCESS_FINE_LOCATION non concesso, SOS abbandonato")
            return Result.failure()
        }

        val location = try {
            LocationServices.getFusedLocationProviderClient(appContext)
                .getCurrentLocation(
                    CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .build(),
                    null,
                )
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "doWork: fix GPS fallito", e)
            null
        }
        if (location == null) {
            Log.w(TAG, "doWork: fix GPS non disponibile (null), ritento piu' tardi")
            GpsAvailability.markUnavailable()
            return Result.retry()
        }
        GpsAvailability.markAvailable()

        // 2026-09-18: BatteryInfo.kt centralizza percentuale+temperatura+
        // stato di carica, prima solo la percentuale duplicata qui.
        val batterySnapshot = BatteryInfo.read(appContext)

        // v0.9.0 (2026-09-18): triggerEvent() ritorna ora TriggerEventResult
        // invece di Boolean (vedi BackendClient.kt, DND automatico per
        // zona) — qui interessa solo "ok", "dnd" e' sempre null per un
        // sos (non e' una transizione geofence).
        val result = BackendClient().triggerEvent(
            type = "sos",
            lat = location.latitude,
            lon = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            battery = batterySnapshot.percent,
            batteryTemp = batterySnapshot.temperatureC,
            charging = batterySnapshot.isCharging,
            speedMps = if (location.hasSpeed()) location.speed else null,
            batteryHoursRemaining = batterySnapshot.hoursRemaining,
        )
        return if (result.ok) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "SosWorker"
        const val WORK_NAME = "sos"
    }
}
