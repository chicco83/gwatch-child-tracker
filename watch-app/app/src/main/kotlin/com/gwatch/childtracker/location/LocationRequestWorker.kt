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

        // v0.18.0 (2026-09-23): stato "invio in corso" per il pulsante,
        // chiuso in ogni caso dal finally (vedi GpsAvailability.sending).
        GpsAvailability.markSending(true)
        try {
            return sendCurrentLocation(source)
        } finally {
            GpsAvailability.markSending(false)
        }
    }

    // v0.18.0 (2026-09-23): test su Watch4 reale — con 10-14 satelliti
    // usati il GPS di sistema non produceva nessuna posizione finche' non
    // si apriva Google Maps. Due correzioni: (1) GpsAssist inietta ora ed
    // effemeridi nel chip prima della richiesta, come fa Maps via servizi
    // Google; (2) durata esplicita FIX_TIMEOUT_MS invece di lasciare al
    // fused provider il suo timeout interno, breve per un GPS a freddo.
    // Precedente (2026-09-18):
    //     CurrentLocationRequest.Builder()
    //         .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
    //         .build(),
    @SuppressLint("MissingPermission")
    private suspend fun sendCurrentLocation(source: String): Result {
        GpsAssist.injectAssistance(appContext)
        // 2026-09-23: satelliti visti/agganciati durante il tentativo, inviati
        // alla phone-app (richiesta utente). Nessun consumo in piu': ascolta
        // solo mentre il GPS e' gia' acceso per questa richiesta.
        val gnss = GnssCounter(appContext).also { it.start() }
        val location = try {
            LocationServices.getFusedLocationProviderClient(appContext)
                .getCurrentLocation(
                    CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setDurationMillis(FIX_TIMEOUT_MS)
                        .build(),
                    null,
                )
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "doWork: fix GPS fallito", e)
            null
        } finally {
            gnss.stop()
        }
        val satsVisible = gnss.maxVisible.takeIf { gnss.received }
        val satsUsed = gnss.maxUsed.takeIf { gnss.received }
        // 2026-09-23: false = nessun dato satelliti, cioe' posizione arrivata
        // da Wi-Fi/rete senza accendere il GPS (mostrato sulla phone-app).
        val gnssActive = gnss.received
        Log.i(TAG, "doWork: satelliti visti=$satsVisible agganciati=$satsUsed")
        if (location == null) {
            Log.w(TAG, "doWork: fix GPS non disponibile (null), ritento piu' tardi")
            GpsAvailability.markUnavailable()
            // 2026-09-23: senza posizione manda comunque batteria/
            // temperatura/carica (richiesta utente: prima la phone-app non
            // le aggiornava piu' se il GPS falliva). Best effort: l'esito
            // non cambia il retry della posizione.
            val snapshot = BatteryInfo.read(appContext)
            val statusSent = BackendClient().sendStatus(
                battery = snapshot.percent,
                batteryTemp = snapshot.temperatureC,
                charging = snapshot.isCharging,
                batteryHoursRemaining = snapshot.hoursRemaining,
                satsVisible = satsVisible,
                satsUsed = satsUsed,
                gnssActive = gnssActive,
            )
            Log.i(TAG, "doWork: stato batteria senza posizione inviato=$statusSent")
            return Result.retry()
        }
        GpsAvailability.markAvailable()

        // 2026-09-18: BatteryInfo.kt centralizza percentuale+temperatura+
        // stato di carica, prima solo la percentuale duplicata qui.
        val batterySnapshot = BatteryInfo.read(appContext)

        // v0.9.0 (2026-09-18): triggerEvent() ritorna ora TriggerEventResult
        // invece di Boolean (vedi BackendClient.kt, DND automatico per
        // zona) — qui interessa solo "ok", "dnd" e' sempre null per un
        // location_request (non e' una transizione geofence).
        val result = BackendClient().triggerEvent(
            type = "location_request",
            lat = location.latitude,
            lon = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            battery = batterySnapshot.percent,
            source = source,
            batteryTemp = batterySnapshot.temperatureC,
            charging = batterySnapshot.isCharging,
            speedMps = if (location.hasSpeed()) location.speed else null,
            batteryHoursRemaining = batterySnapshot.hoursRemaining,
            satsVisible = satsVisible,
            satsUsed = satsUsed,
            gnssActive = gnssActive,
        )
        // 2026-09-23: conferma verde sul pulsante del watch (GpsAvailability).
        if (result.ok) GpsAvailability.markSent()
        return if (result.ok) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "LocationRequestWorker"
        // v0.18.0: tempo massimo per un fix (GPS a freddo), ben sotto i
        // 10 minuti concessi da WorkManager a un worker.
        // 2026-09-23: pubblica, la usa anche la barra di progresso in MainActivity.
        const val FIX_TIMEOUT_MS = 90_000L
        const val WORK_NAME = "location-request"
        const val KEY_SOURCE = "source"
        const val SOURCE_CHILD = "child"
        const val SOURCE_PARENT = "parent"
    }
}
