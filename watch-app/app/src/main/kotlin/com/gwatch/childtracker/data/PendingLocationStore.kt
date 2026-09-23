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
 *
 * Storico versioni:
 * - 0.2.0 (2026-09-23): Fase 3 di qwen_plan.md (individuato da
 *   qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — race condition
 *   reale: LocationUploadWorker gira sotto due nomi di lavoro
 *   WorkManager distinti (periodico + one-shot, vedi
 *   upload/LocationUploadWorker.kt), che possono eseguire
 *   contemporaneamente. Il vecchio peekBatch() (non distruttivo) +
 *   removeOldest() separato lasciava una finestra in cui due worker
 *   concorrenti potevano leggere lo STESSO batch, uploadarlo entrambi
 *   con successo, e il secondo removeOldest(N) finiva per rimuovere N
 *   punti piu' recenti MAI uploadati (perdita dati silenziosa).
 *   Aggravante trovata qui, non nel documento di review: ogni
 *   chiamante crea una propria istanza di PendingLocationStore (una
 *   nuova ad ogni doWork(), vedi LocationUploadWorker.kt) — @Synchronized
 *   in Kotlin sincronizza su "this" (l'istanza), quindi il vecchio
 *   lock non proteggeva AFFATTO le chiamate tra istanze diverse,
 *   nemmeno per una singola operazione. Sostituito con claimBatch()
 *   (legge e rimuove nello stesso blocco sincronizzato: chi la chiama
 *   ha il possesso esclusivo dei punti ricevuti) + requeue() (li
 *   rimette in testa alla coda se l'upload fallisce), entrambi
 *   sincronizzati su un lock condiviso a livello di companion object
 *   (LOCK), non piu' sull'istanza.
 */
class PendingLocationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addPoint(point: LocationPoint) = synchronized(LOCK) {
        val points = readAll().toMutableList()
        points.add(point)
        // Tetto di sicurezza locale: se per qualche motivo l'upload
        // non riesce per molto tempo, non lasciamo crescere il buffer
        // all'infinito (tiene comunque piu' storia di quanta ne serva
        // per un batch, il resto viene scartato dal piu' vecchio).
        while (points.size > MAX_BUFFERED_POINTS) points.removeAt(0)
        writeAll(points)
    }

    /**
     * Rimuove e ritorna atomicamente fino a maxBatchSize punti piu'
     * vecchi: chi li riceve ne ha il possesso esclusivo, nessun altro
     * chiamante concorrente puo' riprenderli in carico (vedi Storico
     * versioni sopra). Se l'upload fallisce, il chiamante DEVE
     * richiamare requeue() con lo stesso batch, altrimenti quei punti
     * sono persi per sempre.
     */
    fun claimBatch(maxBatchSize: Int = MAX_BATCH_SIZE): List<LocationPoint> = synchronized(LOCK) {
        val points = readAll().toMutableList()
        val claimed = points.take(maxBatchSize)
        if (claimed.isNotEmpty()) writeAll(points.drop(claimed.size))
        claimed
    }

    /** Rimette in testa alla coda (i piu' vecchi restano i primi a essere ritentati) un batch non uploadato con successo. */
    fun requeue(points: List<LocationPoint>) = synchronized(LOCK) {
        if (points.isEmpty()) return@synchronized
        val merged = (points + readAll()).toMutableList()
        while (merged.size > MAX_BUFFERED_POINTS) merged.removeAt(0)
        writeAll(merged)
    }

    fun size(): Int = synchronized(LOCK) { readAll().size }

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

        // v0.2.0: lock condiviso da TUTTE le istanze di questa classe
        // (companion object, non "this") — vedi Storico versioni sopra.
        // Ogni chiamante ne crea una propria istanza, quindi il lock
        // deve vivere qui per proteggere davvero le SharedPreferences
        // sottostanti da accessi concorrenti tra istanze diverse.
        private val LOCK = Any()
    }
}
