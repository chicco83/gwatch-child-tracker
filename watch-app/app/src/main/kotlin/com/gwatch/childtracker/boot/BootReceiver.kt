package com.gwatch.childtracker.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.gwatch.childtracker.geofence.GeofenceSyncWorker
import com.gwatch.childtracker.location.LocationTrackingService
import com.gwatch.childtracker.location.TrackingStatus
import com.gwatch.childtracker.location.WatchStateReporter

/**
 * Al riavvio del watch: il foreground service non riparte da solo
 * (va rifatto esplicitamente) e le geofence registrate presso il
 * sistema vengono perse (vanno ri-registrate). I lavori periodici di
 * WorkManager invece sopravvivono al riavvio da soli, non serve
 * ri-accodarli qui.
 *
 * 2026-09-23: anche su MY_PACKAGE_REPLACED (app aggiornata). Prima,
 * dopo ogni aggiornamento (build da Android Studio, in futuro Play Store)
 * il tracking restava fermo finche' qualcuno non apriva l'app: il
 * sistema ferma il processo durante l'aggiornamento e nessuno rilanciava
 * il servizio. Avvio in try/catch con errore registrato in
 * TrackingStatus (visibile nella Ricerca GPS) invece di un fallimento
 * silenzioso; TrackingStatus.startedBy dice da cosa e' partito.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 2026-09-23. Precedente:
        //     if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        //     ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
        val startedBy = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "accensione watch"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "aggiornamento app"
            else -> return
        }
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java),
            )
            TrackingStatus.startedBy = startedBy
            Log.i(TAG, "tracking avviato da: $startedBy")
        } catch (e: Exception) {
            TrackingStatus.lastError = "avvio da $startedBy: ${e.javaClass.simpleName}"
            Log.e(TAG, "avvio tracking da $startedBy fallito", e)
        }

        // 2026-09-23: avvisa il telefono che il watch si e' riacceso (con
        // l'ora dello spegnimento, se registrata). Non dopo un aggiornamento.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) WatchStateReporter.onBoot(context)

        val work = OneTimeWorkRequestBuilder<GeofenceSyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            GeofenceSyncWorker.ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            work,
        )
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
