package com.gwatch.childtracker.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gwatch.childtracker.data.PendingLocationStore
import com.gwatch.childtracker.network.BackendClient

/**
 * Svuota il buffer locale verso POST /api/ingest-location, a batch
 * (vedi CONTEXT.md: un solo invio di rete per piu' punti accumulati,
 * risparmio batteria). Schedulato periodicamente (vedi
 * ui/MainActivity.kt) e anche one-shot quando il buffer supera una
 * soglia (vedi location/LocationTrackingService.kt).
 */
class LocationUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = PendingLocationStore(applicationContext)
        val batch = store.peekBatch()
        if (batch.isEmpty()) return Result.success()

        val ok = BackendClient().ingestLocation(batch)
        if (!ok) return Result.retry()

        store.removeOldest(batch.size)
        return Result.success()
    }

    companion object {
        const val PERIODIC_WORK_NAME = "location-upload-periodic"
        const val ONE_SHOT_WORK_NAME = "location-upload-oneshot"
    }
}
