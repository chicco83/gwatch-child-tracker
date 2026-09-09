package com.gwatch.childtracker.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.gwatch.childtracker.geofence.GeofenceSyncWorker
import com.gwatch.childtracker.location.LocationTrackingService

/**
 * Al riavvio del watch: il foreground service non riparte da solo
 * (va rifatto esplicitamente) e le geofence registrate presso il
 * sistema vengono perse (vanno ri-registrate). I lavori periodici di
 * WorkManager invece sopravvivono al riavvio da soli, non serve
 * ri-accodarli qui.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        ContextCompat.startForegroundService(
            context,
            Intent(context, LocationTrackingService::class.java),
        )

        val work = OneTimeWorkRequestBuilder<GeofenceSyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            GeofenceSyncWorker.ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            work,
        )
    }
}
