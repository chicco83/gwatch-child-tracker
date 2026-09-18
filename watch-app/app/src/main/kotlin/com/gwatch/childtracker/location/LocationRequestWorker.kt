package com.gwatch.childtracker.location

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

    // v0.7.0 (2026-09-18): bug segnalato sul primo test hardware reale —
    // ne' "Invia posizione" ne' SOS arrivavano mai al backend (confermato
    // dai log Vercel: zero chiamate a /api/trigger-event, mentre
    // register-watch-token/device-config/send-message funzionavano),
    // quindi il blocco era qui, prima della chiamata di rete: permesso
    // mancante o fix GPS mai arrivato. Prima nessuno dei due casi
    // lasciava traccia in Logcat (Result.failure()/retry() silenziosi).
    // Aggiunto Log.w su entrambi.
    // v0.8.0 (2026-09-18): Logcat reale ha confermato la causa esatta —
    // "fix GPS non disponibile (null)" ripetuto per oltre un'ora.
    // Aggiunto GpsAvailability.markUnavailable()/markAvailable() qui,
    // letto dalla UI (MainActivity.kt) per disabilitare il pulsante
    // "Invia posizione" con un messaggio esplicito invece di lasciarlo
    // ritentare in silenzio (vedi GpsAvailability.kt).
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val hasPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "doWork: permesso ACCESS_FINE_LOCATION non concesso, richiesta abbandonata")
            return Result.failure()
        }

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

        val ok = BackendClient().triggerEvent(
            type = "location_request",
            lat = location.latitude,
            lon = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            battery = batterySnapshot.percent,
            source = source,
            batteryTemp = batterySnapshot.temperatureC,
            charging = batterySnapshot.isCharging,
        )
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "LocationRequestWorker"
        const val WORK_NAME = "location-request"
        const val KEY_SOURCE = "source"
        const val SOURCE_CHILD = "child"
        const val SOURCE_PARENT = "parent"
    }
}
