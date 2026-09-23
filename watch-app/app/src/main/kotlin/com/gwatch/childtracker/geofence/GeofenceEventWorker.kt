package com.gwatch.childtracker.geofence

// v0.2.0 (2026-09-10): KEY_ZONE_NAME rinominata KEY_ZONE_ID — il valore
// passato qui e' sempre stato il requestId della geofence (l'id
// Firestore della zona, vedi GeofenceBroadcastReceiver.kt), mai il nome
// leggibile. Il nome sbagliato nelle notifiche ("Entrato in <id>"
// invece che "Entrato in Casa") viene corretto lato backend, che ora
// risolve il nome vero leggendo la zona da Firestore con questo id
// (serve comunque per leggere i nuovi toggle notifyOnEnter/
// notifyOnExit/alarmOnExit, vedi CONTEXT.md).
// v0.3.0 (2026-09-18): DND automatico per zona, richiesto dall'utente —
// triggerEvent() ora ritorna anche "dnd" (null/true/false, vedi
// BackendClient.kt/trigger-event.js v0.14.0). Se non null, applica
// subito il cambio con DndController: e' la stessa identica chiamata
// gia' fatta per notificare l'evento, nessuna richiesta di rete in
// piu'.
// v0.4.0 (2026-09-23): vedi Storico versioni in
// GeofenceBroadcastReceiver.kt. doWork() prima non passava mai un
// timestamp esplicito a triggerEvent(): il default del client
// (System.currentTimeMillis() al momento della CHIAMATA) datava
// l'evento all'istante del retry riuscito, non del passaggio di
// confine reale, se il primo invio falliva. Ora l'orario arriva da
// KEY_TIMESTAMP, catturato dal BroadcastReceiver al momento del vero
// rilevamento.

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gwatch.childtracker.dnd.DndController
import com.gwatch.childtracker.network.BackendClient

/** Invia una transizione geofence (ingresso/uscita zona) a POST /api/trigger-event. */
class GeofenceEventWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE) ?: return Result.failure()
        val zoneId = inputData.getString(KEY_ZONE_ID)
        val lat = inputData.getDouble(KEY_LAT, 0.0)
        val lon = inputData.getDouble(KEY_LON, 0.0)
        // Fallback a "adesso" solo per sicurezza (non dovrebbe mai
        // mancare, la scrive sempre GeofenceBroadcastReceiver): meglio
        // un orario leggermente impreciso che un crash del worker.
        val timestampMillis = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())

        val result = BackendClient().triggerEvent(
            type = type,
            lat = lat,
            lon = lon,
            accuracy = null,
            battery = null,
            zoneId = zoneId,
            timestampMillis = timestampMillis,
        )
        if (!result.ok) return Result.retry()
        result.dnd?.let { DndController.setDnd(applicationContext, it) }
        return Result.success()
    }

    companion object {
        const val KEY_TYPE = "type"
        const val KEY_ZONE_ID = "zoneId"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_TIMESTAMP = "timestampMillis"
    }
}
