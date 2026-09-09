package com.gwatch.childtracker.data

import android.content.Context
import com.gwatch.childtracker.network.model.LocationPoint
import org.json.JSONArray

/**
 * Buffer locale dei punti posizione non ancora inviati al backend.
 * SharedPreferences (non Room/DataStore) e' una scelta deliberata: il
 * volume e' basso (decine di punti tra un upload e l'altro, mai
 * migliaia) e questo evita di aggiungere un'altra dipendenza/versione
 * da far combaciare in un progetto che non posso compilare qui.
 *
 * FIFO: i punti vengono rimossi solo dopo conferma di upload riuscito
 * (vedi upload/LocationUploadWorker.kt), cosi' un fallimento di rete
 * non perde dati.
 */
class PendingLocationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun addPoint(point: LocationPoint) {
        val points = readAll().toMutableList()
        points.add(point)
        // Tetto di sicurezza locale: se per qualche motivo l'upload
        // non riesce per molto tempo, non lasciamo crescere il buffer
        // all'infinito (tiene comunque piu' storia di quanta ne serva
        // per un batch, il resto viene scartato dal piu' vecchio).
        while (points.size > MAX_BUFFERED_POINTS) points.removeAt(0)
        writeAll(points)
    }

    /** Fino a MAX_BATCH_SIZE punti piu' vecchi, senza rimuoverli (vedi removeOldest). */
    @Synchronized
    fun peekBatch(maxBatchSize: Int = MAX_BATCH_SIZE): List<LocationPoint> =
        readAll().take(maxBatchSize)

    @Synchronized
    fun removeOldest(count: Int) {
        val points = readAll().toMutableList()
        repeat(minOf(count, points.size)) { points.removeAt(0) }
        writeAll(points)
    }

    @Synchronized
    fun size(): Int = readAll().size

    private fun readAll(): List<LocationPoint> {
        val raw = prefs.getString(KEY_POINTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { LocationPoint.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAll(points: List<LocationPoint>) {
        val arr = JSONArray()
        points.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_POINTS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "pending_locations"
        private const val KEY_POINTS = "points"

        // Corrisponde al tetto lato backend (vedi
        // backend/api/ingest-location.js, MAX_POINTS_PER_REQUEST).
        const val MAX_BATCH_SIZE = 100
        private const val MAX_BUFFERED_POINTS = 500
    }
}
