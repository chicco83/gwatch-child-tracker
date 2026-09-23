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
 * soglia (vedi location/LocationTrackingService.kt) — i due nomi di
 * lavoro WorkManager sono distinti, quindi possono girare in
 * parallelo (vedi data/PendingLocationStore.kt, Storico versioni
 * v0.2.0, per la race condition che questo comportava).
 *
 * Storico versioni:
 * - 0.2.0 (2026-09-23): Fase 3 di qwen_plan.md (individuato da
 *   qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — peekBatch()
 *   (non distruttivo) + removeOldest() separato lasciava una finestra
 *   in cui due esecuzioni concorrenti di questo worker potevano
 *   uploadare lo stesso batch e la seconda rimuovere punti piu'
 *   recenti mai uploadati. claimBatch() ora toglie i punti dal buffer
 *   nello stesso momento in cui li assegna a questa esecuzione; se
 *   l'upload fallisce, requeue() li rimette in coda invece di
 *   perderli.
 */
class LocationUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = PendingLocationStore(applicationContext)
        val batch = store.claimBatch()
        if (batch.isEmpty()) return Result.success()

        val ok = BackendClient().ingestLocation(batch)
        if (!ok) {
            store.requeue(batch)
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        const val PERIODIC_WORK_NAME = "location-upload-periodic"
        const val ONE_SHOT_WORK_NAME = "location-upload-oneshot"
    }
}
