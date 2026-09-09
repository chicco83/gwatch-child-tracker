package com.gwatch.childtracker.location

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
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
 */
class LocationTrackingService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var pendingStore: PendingLocationStore
    private var currentIntervalMillis: Long = INTERVAL_STILL_MS
    private var updatesActive = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val point = LocationPoint(
                lat = location.latitude,
                lon = location.longitude,
                accuracy = if (location.hasAccuracy()) location.accuracy else null,
                battery = currentBatteryPercent(),
                activity = if (currentIntervalMillis == INTERVAL_MOVING_MS) "moving" else "still",
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
        startForeground(NOTIFICATION_ID, buildNotification())
        registerActivityTransitions()
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
        super.onDestroy()
        fusedClient.removeLocationUpdates(locationCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("MissingPermission") // permessi verificati da MainActivity prima di avviare il service
    private fun startLocationUpdates(intervalMillis: Long) {
        fusedClient.removeLocationUpdates(locationCallback)

        val priority = if (intervalMillis == INTERVAL_MOVING_MS) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val request = LocationRequest.Builder(priority, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .build()

        fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        updatesActive = true
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

    private fun currentBatteryPercent(): Int? {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else null
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
