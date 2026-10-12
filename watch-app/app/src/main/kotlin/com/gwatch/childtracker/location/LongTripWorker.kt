package com.gwatch.childtracker.location

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gwatch.childtracker.network.BackendClient

/**
 * Versione: 0.1.0 (2026-10-12)
 *
 * Avvisa il backend che il watch e' in un viaggio lungo (in movimento da
 * almeno LONG_TRIP_MIN_MINUTES e ad almeno LONG_TRIP_MIN_KM da dove e'
 * partito, vedi LocationTrackingService.checkLongTrip). Il backend manda al
 * genitore la richiesta "il viaggio e' previsto?" (trigger-event.js v0.26.0,
 * reason "long_trip"). Come PowerStateWorker: parte appena c'e' rete, ma con
 * pochi tentativi — un avviso di viaggio arrivato dopo ore sarebbe fuorviante.
 */
class LongTripWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val minutes = inputData.getInt(KEY_MINUTES, 0)
        val km = inputData.getDouble(KEY_KM, 0.0)
        val battery = BatteryInfo.read(applicationContext)
        val ok = BackendClient().sendStatus(
            battery = battery.percent,
            batteryTemp = battery.temperatureC,
            charging = battery.isCharging,
            batteryHoursRemaining = battery.hoursRemaining,
            reason = "long_trip",
            tripMinutes = minutes,
            tripKm = km,
        )
        Log.i(TAG, "viaggio lungo ($minutes min, ${"%.0f".format(km)} km) inviato=$ok")
        return when {
            ok -> Result.success()
            runAttemptCount + 1 >= MAX_ATTEMPTS -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "LongTripWorker"
        private const val MAX_ATTEMPTS = 3
        const val WORK_NAME = "long-trip"
        const val KEY_MINUTES = "minutes"
        const val KEY_KM = "km"

        fun enqueue(context: Context, minutes: Int, km: Double) {
            val work = OneTimeWorkRequestBuilder<LongTripWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_MINUTES to minutes, KEY_KM to km))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, work)
        }
    }
}
