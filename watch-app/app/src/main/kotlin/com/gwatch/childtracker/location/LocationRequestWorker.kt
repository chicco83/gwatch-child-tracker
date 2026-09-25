package com.gwatch.childtracker.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.gwatch.childtracker.network.BackendClient
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Invio manuale/su richiesta remota della posizione attuale — pulsante
 * "Invia posizione" in MainActivity (il bambino) oppure push
 * "location_request" da FcmService (il genitore preme "Aggiorna
 * posizione" sulla phone-app) — a differenza del tracking periodico
 * automatico (LocationUploadWorker). Stessa logica di SosWorker (stesso
 * evento "prioritario" lato backend, vedi trigger-event.js type
 * "location_request"). 2026-09-23: la richiesta dal telefono (FcmService) ora
 * e' accodata come lavoro espedito, per partire subito anche ad app chiusa;
 * il pulsante sul watch resta un lavoro normale (app gia' in primo piano).
 * Testo precedente: "ma senza setExpedited: non e' un'emergenza, puo'
 * aspettare la coda normale di WorkManager."
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
    // 2026-09-24: segnalato dall'utente — le posizioni su richiesta erano
    // "da rete, non GPS" (12:11 del 24/9: 73 m, nessun satellite durante il
    // tentativo). getCurrentLocation() restituiva una posizione gia' in
    // cache (quella Wi-Fi del tracking da fermo) senza nemmeno accendere il
    // GPS: HIGH_ACCURACY vuol dire "la migliore disponibile", non "GPS".
    // Ora (acquireBestLocation) aggiornamenti continui ad alta precisione
    // con maxUpdateAge 0 (niente cache, GPS acceso davvero), e dopo il primo
    // punto si aspetta fino a IMPROVE_WINDOW_MS un fix migliore, fermandosi
    // subito sotto GOOD_ACCURACY_M. Al chiuso il GPS puo' non agganciare: in
    // quel caso si invia comunque il migliore ottenuto (anche Wi-Fi).
    // Precedente (2026-09-23), dentro sendCurrentLocation:
    //     LocationServices.getFusedLocationProviderClient(appContext)
    //         .getCurrentLocation(
    //             CurrentLocationRequest.Builder()
    //                 .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
    //                 .setDurationMillis(FIX_TIMEOUT_MS)
    //                 .build(),
    //             null,
    //         )
    //         .await()
    // 2026-09-25: segnalato dall'utente — dopo la v0.34 la phone-app diceva
    // ancora sempre "GPS non usato" e il numero di satelliti non e' MAI
    // comparso. Il conteggio dipendeva solo da GnssStatus.Callback
    // (GnssCounter), che Android consegna solo alle app considerate in
    // primo piano: da un worker in background non arriva nulla, e il
    // fused provider puo' comunque rispondere col Wi-Fi senza che si sappia
    // se il GPS e' stato acceso. Ora durante la richiesta si chiede il fix
    // ANCHE direttamente al GPS di sistema (LocationManager.GPS_PROVIDER):
    // (1) il GPS si accende di sicuro; (2) un fix da questa fonte e' GPS
    // per definizione; (3) il fix GPS riporta nei suoi extras
    // "satellites" = satelliti usati, disponibile anche senza GnssStatus.
    // I fix delle due fonti finiscono nello stesso canale, vince il piu'
    // preciso come prima.
    private var gpsFixReceived = false
    private var gpsSatellitesInFix: Int? = null

    @SuppressLint("MissingPermission")
    private suspend fun acquireBestLocation(): Location? {
        val client = LocationServices.getFusedLocationProviderClient(appContext)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS / 2)
            .setMaxUpdateAgeMillis(0)
            .build()
        val fixes = Channel<Location>(Channel.UNLIMITED)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { fixes.trySend(it) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper()).await()
        // 2026-09-25: fonte GPS diretta, vedi commento sopra.
        val locationManager = appContext.getSystemService(LocationManager::class.java)
        val gpsListener = LocationListener { fix ->
            gpsFixReceived = true
            fix.extras?.getInt("satellites", -1)?.takeIf { it >= 0 }?.let { gpsSatellitesInFix = it }
            Log.i(TAG, "fix GPS diretto: ${fix.accuracy} m, satelliti=${gpsSatellitesInFix}")
            fixes.trySend(fix)
        }
        runCatching {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MS,
                0f,
                ContextCompat.getMainExecutor(appContext),
                gpsListener,
            )
        }.onFailure { Log.w(TAG, "richiesta GPS diretta fallita", it) }
        var best: Location? = null
        try {
            withTimeoutOrNull(FIX_TIMEOUT_MS) {
                // Primo punto: si aspetta quanto serve (entro FIX_TIMEOUT_MS).
                best = fixes.receive()
                Log.i(TAG, "primo punto: ${best?.accuracy} m (${best?.provider})")
                if ((best?.accuracy ?: Float.MAX_VALUE) <= GOOD_ACCURACY_M) return@withTimeoutOrNull
                // Poi fino a IMPROVE_WINDOW_MS per un fix migliore (GPS).
                withTimeoutOrNull(IMPROVE_WINDOW_MS) {
                    while (true) {
                        val fix = fixes.receive()
                        if (fix.accuracy < (best?.accuracy ?: Float.MAX_VALUE)) best = fix
                        if (fix.accuracy <= GOOD_ACCURACY_M) break
                    }
                }
            }
        } finally {
            client.removeLocationUpdates(callback)
            runCatching { locationManager?.removeUpdates(gpsListener) }
            fixes.close()
        }
        Log.i(TAG, "punto scelto: ${best?.accuracy} m (${best?.provider})")
        return best
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendCurrentLocation(source: String): Result {
        GpsAssist.injectAssistance(appContext)
        // 2026-09-23: satelliti visti/agganciati durante il tentativo, inviati
        // alla phone-app (richiesta utente). Nessun consumo in piu': ascolta
        // solo mentre il GPS e' gia' acceso per questa richiesta.
        val gnss = GnssCounter(appContext).also { it.start() }
        // 2026-09-24: acquireBestLocation() al posto di getCurrentLocation
        // (vedi commento sopra acquireBestLocation).
        val location = try {
            acquireBestLocation()
        } catch (e: Exception) {
            Log.w(TAG, "doWork: fix GPS fallito", e)
            null
        } finally {
            gnss.stop()
        }
        // 2026-09-25: se GnssStatus non arriva (app in background, vedi
        // acquireBestLocation) si usano i satelliti riportati dal fix GPS
        // diretto: solo "agganciati", i "visti" restano sconosciuti (null).
        // gnssActive = il GPS ha dato almeno un segnale (stato o fix).
        // Precedente (2026-09-23):
        // val satsVisible = gnss.maxVisible.takeIf { gnss.received }
        // val satsUsed = gnss.maxUsed.takeIf { gnss.received }
        // val gnssActive = gnss.received
        val satsVisible = gnss.maxVisible.takeIf { gnss.received }
        val satsUsed = if (gnss.received) gnss.maxUsed else gpsSatellitesInFix
        val gnssActive = gnss.received || gpsFixReceived
        Log.i(TAG, "doWork: GnssStatus=${gnss.received} fixGPS=$gpsFixReceived")
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

    // 2026-09-23: un lavoro espedito su Android 11 (minSdk 30) richiede
    // getForegroundInfo(); il Watch4 ha Android 12+ e su quelle versioni
    // non viene chiamato, come gia' per SosWorker.

    companion object {
        private const val TAG = "LocationRequestWorker"
        // v0.18.0: tempo massimo per un fix (GPS a freddo), ben sotto i
        // 10 minuti concessi da WorkManager a un worker.
        // 2026-09-23: pubblica, la usa anche la barra di progresso in MainActivity.
        const val FIX_TIMEOUT_MS = 90_000L
        // 2026-09-24: vedi acquireBestLocation(). Dopo il primo punto si
        // aspetta al massimo 30 s un fix GPS migliore; sotto i 20 m ci si
        // ferma subito. Il totale resta entro FIX_TIMEOUT_MS (la barra
        // di progresso sul watch non cambia).
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val IMPROVE_WINDOW_MS = 30_000L
        private const val GOOD_ACCURACY_M = 20f
        const val WORK_NAME = "location-request"
        const val KEY_SOURCE = "source"
        const val SOURCE_CHILD = "child"
        const val SOURCE_PARENT = "parent"
    }
}
