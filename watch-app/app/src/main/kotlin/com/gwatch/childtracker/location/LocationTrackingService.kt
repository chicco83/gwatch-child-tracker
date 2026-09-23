package com.gwatch.childtracker.location

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.gwatch.childtracker.R
import com.gwatch.childtracker.TrackerApplication
import com.gwatch.childtracker.data.PendingLocationStore
import com.gwatch.childtracker.network.model.LocationPoint
import com.gwatch.childtracker.upload.LocationUploadWorker

/**
 * Foreground service: unico proprietario del FusedLocationProviderClient.
 * Sampling adattivo (vedi CONTEXT.md): intervallo lungo da fermo,
 * breve in movimento, deciso da ActivityTransitionReceiver che invia
 * un Intent a questo service (vedi onStartCommand). Ogni punto ricevuto
 * finisce nel buffer locale (PendingLocationStore); l'upload vero e
 * proprio e' un lavoro separato (LocationUploadWorker), periodico +
 * one-shot quando il buffer cresce abbastanza.
 *
 * 2026-09-23: aggiunti log (tag "LocationTrackingService") e
 * TrackingStatus su avvio, richiesta, punti ricevuti ed errori — prima
 * il servizio non scriveva nulla, e dal 20/9 i punti automatici sono
 * crollati senza modo di capire perche'. startForeground/
 * requestLocationUpdates ora in try/catch: un errore (es. avvio in
 * background dopo un riavvio del watch) viene registrato invece di far
 * morire il servizio in silenzio.
 */
class LocationTrackingService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var pendingStore: PendingLocationStore
    private var currentIntervalMillis: Long = INTERVAL_STILL_MS
    private var updatesActive = false
    // 2026-09-23: modalita' aereo/spegnimento (WatchStateReporter.kt).
    private val watchStateReceiver = WatchStateReporter.createReceiver()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            // 2026-09-23: diagnostica, vedi TrackingStatus.kt.
            TrackingStatus.onFix()
            Log.i(TAG, "punto ricevuto: precisione=${location.accuracy}m provider=${location.provider}")
            // v0.8.0 (2026-09-18): segnala a GpsAvailability che il GPS
            // risponde — riusa questo callback gia' esistente (tracking
            // periodico automatico), nessun polling aggiuntivo. Vedi
            // GpsAvailability.kt per il contesto completo.
            GpsAvailability.markAvailable()
            // 2026-09-18: BatteryInfo centralizza qui quello che prima
            // era solo currentBatteryPercent() duplicato in 4 file (vedi
            // BatteryInfo.kt) — aggiunge temperatura/stato di carica
            // richiesti dall'utente, stesso costo (nessuno: legge solo lo
            // stato gia' mantenuto dal sistema, nessun sensore avviato).
            val batterySnapshot = BatteryInfo.read(this@LocationTrackingService)
            val point = LocationPoint(
                lat = location.latitude,
                lon = location.longitude,
                accuracy = if (location.hasAccuracy()) location.accuracy else null,
                battery = batterySnapshot.percent,
                activity = if (currentIntervalMillis == INTERVAL_MOVING_MS) "moving" else "still",
                batteryTemp = batterySnapshot.temperatureC,
                charging = batterySnapshot.isCharging,
                speedMps = if (location.hasSpeed()) location.speed else null,
                batteryHoursRemaining = batterySnapshot.hoursRemaining,
                timestampMillis = location.time,
            )
            pendingStore.addPoint(point)

            if (pendingStore.size() >= UPLOAD_TRIGGER_THRESHOLD) {
                enqueueImmediateUpload()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        pendingStore = PendingLocationStore(this)
        // 2026-09-23: try/catch + log. Precedente:
        //     startForeground(NOTIFICATION_ID, buildNotification())
        //     registerActivityTransitions()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            TrackingStatus.serviceRunning = true
            Log.i(TAG, "onCreate: servizio in primo piano avviato")
        } catch (e: Exception) {
            TrackingStatus.lastError = "startForeground: ${e.javaClass.simpleName}"
            Log.e(TAG, "onCreate: startForeground fallito", e)
        }
        runCatching { registerActivityTransitions() }
            .onFailure { Log.w(TAG, "registerActivityTransitions fallito", it) }
        // 2026-09-23: avvisi modalita' aereo/spegnimento (richiesta utente).
        WatchStateReporter.register(this, watchStateReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val moving = intent?.getBooleanExtra(EXTRA_MOVING, false) ?: false
        val newInterval = if (moving) INTERVAL_MOVING_MS else INTERVAL_STILL_MS

        if (!updatesActive || newInterval != currentIntervalMillis) {
            currentIntervalMillis = newInterval
            startLocationUpdates(newInterval)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        TrackingStatus.serviceRunning = false
        Log.i(TAG, "onDestroy")
        runCatching { unregisterReceiver(watchStateReceiver) }
        super.onDestroy()
        fusedClient.removeLocationUpdates(locationCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("MissingPermission") // permessi verificati da MainActivity prima di avviare il service
    private fun startLocationUpdates(intervalMillis: Long) {
        fusedClient.removeLocationUpdates(locationCallback)

        // 2026-09-23: alta precisione anche da fermo. Lo storico Firestore
        // mostra i punti automatici crollati dal 20/9 (giorno della scarica
        // completa del watch) mentre il servizio restava vivo e Maps
        // otteneva la posizione: la priorita' "bilanciata" lascia ad
        // Android la scelta della fonte (Wi-Fi, celle, telefono associato)
        // e puo' non accendere mai il GPS; se quelle fonti mancano, nessun
        // punto. Con HIGH_ACCURACY ogni 10' il GPS viene acceso quando
        // serve (con i dati di aiuto di GpsAssist). Costo: piu' batteria
        // da fermo. Precedente (2026-09-09):
        //     val priority = if (intervalMillis == INTERVAL_MOVING_MS) {
        //         Priority.PRIORITY_HIGH_ACCURACY
        //     } else {
        //         Priority.PRIORITY_BALANCED_POWER_ACCURACY
        //     }
        val priority = Priority.PRIORITY_HIGH_ACCURACY
        GpsAssist.injectAssistance(this)

        val request = LocationRequest.Builder(priority, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .build()

        // 2026-09-23: try/catch + log. Precedente:
        //     fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        //     updatesActive = true
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
            updatesActive = true
            TrackingStatus.priorityLabel = "alta, ogni ${intervalMillis / 60000} min"
            Log.i(TAG, "richiesta posizioni: ${TrackingStatus.priorityLabel}")
        } catch (e: Exception) {
            TrackingStatus.lastError = "requestLocationUpdates: ${e.javaClass.simpleName}"
            Log.e(TAG, "requestLocationUpdates fallito", e)
        }
    }

    @Suppress("MissingPermission")
    private fun registerActivityTransitions() {
        val transitions = listOf(
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE,
        ).map { type ->
            ActivityTransition.Builder()
                .setActivityType(type)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        }

        val request = ActivityTransitionRequest(transitions)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, ActivityTransitionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        ActivityRecognition.getClient(this)
            .requestActivityTransitionUpdates(request, pendingIntent)
    }

    private fun enqueueImmediateUpload() {
        val work = OneTimeWorkRequestBuilder<LocationUploadWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            LocationUploadWorker.ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            work,
        )
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, TrackerApplication.TRACKING_CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.tracking_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_MOVING = "moving"

        // Sampling adattivo: 10 min da fermi, 1 min in movimento
        // (vedi CONTEXT.md — risparmio batteria).
        private const val INTERVAL_STILL_MS = 10 * 60 * 1000L
        private const val INTERVAL_MOVING_MS = 60 * 1000L

        // Oltre questa soglia di punti bufferizzati, forza un upload
        // immediato invece di aspettare il prossimo giro periodico.
        private const val UPLOAD_TRIGGER_THRESHOLD = 15
    }
}
