package com.gwatch.childtracker.sos

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

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val hasPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return Result.failure()

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
            type = "sos",
            lat = location.latitude,
            lon = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            battery = battery,
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
        const val WORK_NAME = "sos"
    }
}
