package com.gwatch.childtracker.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.gwatch.childtracker.network.BackendClient
import kotlinx.coroutines.tasks.await

/**
 * Invio manuale/su richiesta remota della posizione attuale — pulsante
 * "Invia posizione" in MainActivity (il bambino) oppure push
 * "location_request" da FcmService (il genitore preme "Aggiorna
 * posizione" sulla phone-app) — a differenza del tracking periodico
 * automatico (LocationUploadWorker). Stessa logica di SosWorker (stesso
 * evento "prioritario" lato backend, vedi trigger-event.js type
 * "location_request"), ma senza setExpedited: non e' un'emergenza, puo'
 * aspettare la coda normale di WorkManager.
 *
 * v0.2.0 (2026-09-10): aggiunto KEY_SOURCE. Prima le due chiamate
 * (bambino/genitore) mandavano lo stesso identico evento al backend,
 * che quindi non poteva distinguerle: la notifica al genitore diceva
 * sempre "il bambino ha inviato la posizione", anche quando l'aveva
 * chiesta lui stesso da remoto. Default SOURCE_CHILD se non impostato,
 * cosi' un eventuale enqueue senza input data (non dovrebbe succedere,
 * entrambi i chiamanti lo passano sempre) resta sul comportamento
 * precedente invece di fallire silenziosamente.
 */
class LocationRequestWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val hasPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return Result.failure()

        val source = inputData.getString(KEY_SOURCE) ?: SOURCE_CHILD

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
            null
        } ?: return Result.retry()

        val battery = currentBatteryPercent()

        val ok = BackendClient().triggerEvent(
            type = "location_request",
            lat = location.latitude,
            lon = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            battery = battery,
            source = source,
        )
        return if (ok) Result.success() else Result.retry()
    }

    private fun currentBatteryPercent(): Int? {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else null
    }

    companion object {
        const val WORK_NAME = "location-request"
        const val KEY_SOURCE = "source"
        const val SOURCE_CHILD = "child"
        const val SOURCE_PARENT = "parent"
    }
}
