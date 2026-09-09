package com.gwatch.childtracker.geofence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gwatch.childtracker.network.BackendClient

/** Invia una transizione geofence (ingresso/uscita zona) a POST /api/trigger-event. */
class GeofenceEventWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE) ?: return Result.failure()
        val zoneName = inputData.getString(KEY_ZONE_NAME)
        val lat = inputData.getDouble(KEY_LAT, 0.0)
        val lon = inputData.getDouble(KEY_LON, 0.0)

        val ok = BackendClient().triggerEvent(
            type = type,
            lat = lat,
            lon = lon,
            accuracy = null,
            battery = null,
            zoneName = zoneName,
        )
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_TYPE = "type"
        const val KEY_ZONE_NAME = "zoneName"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
    }
}
