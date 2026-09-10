package com.gwatch.childtracker.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.gwatch.childtracker.R
import com.gwatch.childtracker.TrackerApplication
import com.gwatch.childtracker.network.BackendClient
import com.gwatch.childtracker.network.SosHeartbeatResult
import com.gwatch.childtracker.sos.SosState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Foreground service dedicato: mentre un SOS e' attivo, invia la
 * posizione ogni 30 secondi (backend/api/sos-heartbeat.js) finche' il
 * genitore non lo disattiva dalla phone-app — push "sos_cancel",
 * gestita in messaging/FcmService.kt, che ferma questo service — o
 * finche' il backend non risponde 409 (l'SOS non e' piu' attivo lato
 * server: rete di sicurezza se la push di cancellazione va persa). Un
 * tetto massimo di durata (SAFETY_CAP_MS) ferma comunque il service
 * anche se entrambi i segnali di stop dovessero mancare, per non
 * scaricare la batteria all'infinito per un bug o una perdita di rete
 * permanente.
 *
 * Separato da LocationTrackingService (il foreground service del
 * tracking periodico normale) invece di riusarlo: qui la cadenza (30")
 * e' molto piu' frequente del minimo "in movimento" (1 minuto) di
 * quello, ed ogni punto va inviato subito uno per uno (mai bufferizzato
 * in PendingLocationStore) — logica abbastanza diversa da giustificare
 * un service a parte piuttosto che un altro "modo" dentro a quello
 * esistente.
 */
class SosLocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        // v0.5.0 (2026-09-10): prima l'attivazione/disattivazione
        // dell'SOS non aveva alcun riscontro sulla UI del watch — ne'
        // dopo la conferma ne' quando il genitore lo disattiva da
        // remoto. MainActivity osserva questo stato per mostrare/
        // nascondere il banner "SOS ATTIVO" sulla schermata principale.
        SosState.setActive(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { heartbeatLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        loopJob?.cancel()
        SosState.setActive(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission") // permesso verificato sotto, prima di ogni fix
    private suspend fun heartbeatLoop() {
        val backendClient = BackendClient()
        val startedAt = System.currentTimeMillis()

        while (System.currentTimeMillis() - startedAt < SAFETY_CAP_MS) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) break

            val location = try {
                LocationServices.getFusedLocationProviderClient(this)
                    .getCurrentLocation(
                        CurrentLocationRequest.Builder()
                            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                            .build(),
                        null,
                    )
                    .await()
            } catch (e: Exception) {
                null
            }

            if (location != null) {
                val result = backendClient.sosHeartbeat(
                    lat = location.latitude,
                    lon = location.longitude,
                    accuracy = if (location.hasAccuracy()) location.accuracy else null,
                    battery = currentBatteryPercent(),
                )
                if (result == SosHeartbeatResult.STOP) break
            }

            delay(HEARTBEAT_INTERVAL_MS)
        }

        stopSelf()
    }

    private fun currentBatteryPercent(): Int? {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else null
    }

    // Canale "messages" (visibile/sonoro, IMPORTANCE_HIGH) e non
    // "tracking" (silenzioso, IMPORTANCE_MIN): a differenza del
    // tracking periodico normale, che l'utente non deve notare, un SOS
    // attivo e' importante che resti visibile finche' non viene
    // disattivato.
    private fun buildNotification() =
        NotificationCompat.Builder(this, TrackerApplication.MESSAGES_CHANNEL_ID)
            .setContentTitle(getString(R.string.sos_tracking_notification_title))
            .setContentText(getString(R.string.sos_tracking_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val SAFETY_CAP_MS = 3 * 60 * 60 * 1000L // 3 ore
    }
}
