package com.gwatch.childtracker.network

// v0.4.0 (2026-09-10): aggiunto sosHeartbeat, per il ping di posizione
// ogni 30" durante un SOS attivo (vedi
// location/SosLocationService.kt e backend/api/sos-heartbeat.js).
// Esito a 3 stati (non solo bool) perche' un 409 ("SOS non piu'
// attivo") e' un segnale distinto da un errore di rete: il primo dice
// al service di fermarsi, il secondo di ritentare al prossimo giro.
// v0.5.0 (2026-09-18): bug segnalato sul primo test hardware reale
// ("richiesta posizione: watch non raggiungibile" + invio posizione
// dal watch senza mai conferma) — a differenza del BackendClient.kt
// della phone-app, questo non loggava mai nulla su un esito negativo:
// impossibile distinguere da Logcat un vero problema di rete (watch
// senza dati, es. eSIM solo voce/SMS senza piano dati) da un 401 (token
// del device sbagliato in local.properties/BuildConfig) da un altro
// errore del backend. Aggiunto Log.w in executeForBody, stesso stile
// del client phone-app.
// v0.6.0 (2026-09-23): Fase 4 di qwen_plan.md (individuato da
// qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — ogni worker
// (GeofenceEventWorker, LocationUploadWorker, SosWorker, ecc.) crea la
// propria istanza di BackendClient ad ogni esecuzione, e "http" era una
// proprieta' di ISTANZA: un OkHttpClient (con relativo pool di
// connessioni/thread pool del Dispatcher) nuovo ad ogni chiamata,
// invece di essere riusato — sprecato su LTE, dove aprire/chiudere
// connessioni costa piu' batteria che tenerle vive nel pool. Spostato
// in companion object: un solo OkHttpClient condiviso da tutte le
// istanze per l'intero processo, come raccomandato dalla documentazione
// OkHttp stessa.

import android.util.Log
import com.gwatch.childtracker.config.BackendConfig
import com.gwatch.childtracker.network.model.ChatMessage
import com.gwatch.childtracker.network.model.GeofenceZone
import com.gwatch.childtracker.network.model.LocationPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Client verso gli endpoint del watch documentati in
 * backend/README.md. Un solo OkHttpClient condiviso (connection
 * pooling), timeout brevi perche' il watch e' su LTE e non deve
 * bloccare a lungo in caso di rete instabile.
 */
class BackendClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // v0.6.0: condiviso da tutte le istanze, vedi Storico versioni sopra.
    private val http = HTTP_CLIENT

    /** true se il batch e' stato accettato (HTTP 2xx). */
    suspend fun ingestLocation(points: List<LocationPoint>): Boolean {
        val body = JSONObject().apply {
            put("points", JSONArray(points.map { it.toJson() }))
        }
        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/api/ingest-location")
            .header("X-Device-Token", BackendConfig.deviceToken)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return executeForSuccess(request)
    }

    /**
     * SOS o transizione geofence. type: "sos" | "geofence_enter" | "geofence_exit".
     * Vedi backend/api/trigger-event.js — l'SOS non e' mai soggetto al
     * limite di traffico lato backend, per cui questa chiamata va
     * sempre tentata anche se altre chiamate sono state rifiutate.
     *
     * v0.5.0 (2026-09-10): "zoneName" rinominato "zoneId" — il valore
     * passato era gia' sempre l'id Firestore della zona (vedi
     * GeofenceBroadcastReceiver.kt, requestId della geofence), mai il
     * nome leggibile: il nome mostrato nelle notifiche era percio'
     * sbagliato (mostrava l'id). Ora il backend risolve il nome vero
     * leggendo la zona da Firestore con questo id — necessario comunque
     * per leggere i nuovi toggle per-zona notifyOnEnter/notifyOnExit/
     * alarmOnExit (vedi CONTEXT.md).
     * v0.6.0 (2026-09-10): aggiunto "source" (usato solo da
     * "location_request", vedi LocationRequestWorker.kt) — distingue
     * lato backend un invio manuale del bambino da una richiesta remota
     * del genitore, cosi' la notifica non dice sempre "il bambino ha
     * inviato la posizione" anche quando l'aveva chiesta il genitore
     * stesso.
     * v0.7.0 (2026-09-18): aggiunti batteryTemp/charging (vedi
     * location/BatteryInfo.kt) — SOS e "Invia posizione" sono chiamate
     * esplicite dell'utente, ha senso mandare lo stato batteria piu'
     * fresco possibile insieme al resto. Non aggiunto invece a
     * sosHeartbeat (ping ogni 30" durante un SOS attivo, vedi sotto):
     * temperatura/carica non cambiano in modo significativo in 30
     * secondi, non vale la cadenza piu' alta.
     * v0.8.0 (2026-09-18): aggiunto speedMps (Location.getSpeed(),
     * richiesto dall'utente per mostrare la velocita' sulla mappa) —
     * gia' incluso gratis in ogni fix GPS, nessuna chiamata in piu'.
     * v0.9.0 (2026-09-18): DND automatico per zona, richiesto
     * dall'utente. Il tipo di ritorno cambia da Boolean a
     * TriggerEventResult: oltre a "ok" (comportamento invariato per i
     * chiamanti che non lo usano, vedi SosWorker.kt/LocationRequestWorker.kt),
     * espone "dnd" — null se il backend non chiede nessun cambio DND
     * (tipo diverso da geofence_enter/exit, o zona senza dndOnZone),
     * true/false se il watch deve attivare/disattivare il "Non
     * disturbare" di sistema (vedi trigger-event.js v0.14.0 e
     * geofence/GeofenceEventWorker.kt, che applica il cambio).
     * v0.10.0 (2026-09-19): aggiunto batteryHoursRemaining (vedi
     * location/BatteryInfo.kt), stesso pattern di batteryTemp/charging.
     */
    suspend fun triggerEvent(
        type: String,
        lat: Double,
        lon: Double,
        accuracy: Float?,
        battery: Int?,
        zoneId: String? = null,
        source: String? = null,
        batteryTemp: Double? = null,
        charging: Boolean? = null,
        speedMps: Float? = null,
        batteryHoursRemaining: Double? = null,
        timestampMillis: Long = System.currentTimeMillis(),
        // 2026-09-23: satelliti del tentativo (GnssCounter), mostrati sulla phone-app.
        satsVisible: Int? = null,
        satsUsed: Int? = null,
    ): TriggerEventResult {
        val body = JSONObject().apply {
            put("type", type)
            put("lat", lat)
            put("lon", lon)
            accuracy?.let { put("accuracy", it.toDouble()) }
            battery?.let { put("battery", it) }
            zoneId?.let { put("zoneId", it) }
            source?.let { put("source", it) }
            batteryTemp?.let { put("batteryTemp", it) }
            charging?.let { put("charging", it) }
            speedMps?.let { put("speed", it.toDouble()) }
            batteryHoursRemaining?.let { put("batteryHoursRemaining", it) }
            put("timestamp", timestampMillis)
            satsVisible?.let { put("satsVisible", it) }
            satsUsed?.let { put("satsUsed", it) }
        }
        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/api/trigger-event")
            .header("X-Device-Token", BackendConfig.deviceToken)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val responseBody = executeForBody(request) ?: return TriggerEventResult(ok = false, dnd = null)
        val dnd = try {
            val json = JSONObject(responseBody)
            if (json.has("dnd") && !json.isNull("dnd")) json.getBoolean("dnd") else null
        } catch (e: Exception) {
            null
        }
        return TriggerEventResult(ok = true, dnd = dnd)
    }

    /**
     * 2026-09-23: stato batteria senza posizione (trigger-event.js type
     * "status", backend v0.19.0). Usato quando il GPS non da' un fix, cosi'
     * la phone-app mostra comunque batteria/temperatura aggiornate e
     * l'ora dell'ultimo contatto col watch. true se accettato.
     */
    suspend fun sendStatus(
        battery: Int?,
        batteryTemp: Double?,
        charging: Boolean?,
        batteryHoursRemaining: Double?,
        satsVisible: Int? = null,
        satsUsed: Int? = null,
    ): Boolean {
        val body = JSONObject().apply {
            put("type", "status")
            satsVisible?.let { put("satsVisible", it) }
            satsUsed?.let { put("satsUsed", it) }
            battery?.let { put("battery", it) }
            batteryTemp?.let { put("batteryTemp", it) }
            charging?.let { put("charging", it) }
            batteryHoursRemaining?.let { put("batteryHoursRemaining", it) }
        }
        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/api/trigger-event")
            .header("X-Device-Token", BackendConfig.deviceToken)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return executeForSuccess(request)
    }

    /** Geofence attive configurate dal genitore. Lista vuota se la chiamata fallisce. */
    suspend fun fetchDeviceConfig(): List<GeofenceZone> {
        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/api/device-config")
            .header("X-Device-Token", BackendConfig.deviceToken)
            .get()
            .build()

        val responseBody = executeForBody(request) ?: return emptyList()
        return try {
            val json = JSONObject(responseBody)
            val arr = json.getJSONArray("geofences")
            (0 until arr.length()).map { GeofenceZone.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Messaggio di chat verso il genitore. true se accettato (HTTP 2xx). */
    suspend fun sendMessage(text: String, timestampMillis: Long = System.currentTimeMillis()): Boolean {
        val body = JSONObject().apply {
            put("text", text)
            put("timestamp", timestampMillis)
        }
        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/api/send-message")
            .header("X-Device-Token", BackendConfig.deviceToken)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return executeForSuccess(request)
    }

    /**
     * Ping di posizione durante un SOS attivo. STOP significa "il
     * backend dice che l'SOS non e' piu' attivo" (il genitore l'ha
     * disattivato, o la push di cancellazione e' arrivata dopo che il
     * 409 e' gia' stato ricevuto): il chiamante deve fermare il loop.
     */
    suspend fun sosHeartbeat(
        lat: Double,
        lon: Double,
        accuracy: Float?,
        battery: Int?,
        timestampMillis: Long = System.currentTimeMillis(),
    ): SosHeartbeatResult {
        val body = JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            accuracy?.let { put("accuracy", it.toDouble()) }
            battery?.let { put("battery", it) }
            put("timestamp", timestampMillis)
        }
        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/api/sos-heartbeat")
            .header("X-Device-Token", BackendConfig.deviceToken)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(SosHeartbeatResult.FAILED)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val result = when {
                            it.isSuccessful -> SosHeartbeatResult.ACCEPTED
                            it.code == 409 -> SosHeartbeatResult.STOP
                            else -> SosHeartbeatResult.FAILED
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                }
            })
        }
    }

    /** Registra/aggiorna il token FCM del watch, per ricevere la chat. */
    suspend fun registerFcmToken(token: String): Boolean {
        val body = JSONObject().apply { put("token", token) }
        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/api/register-watch-token")
            .header("X-Device-Token", BackendConfig.deviceToken)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return executeForSuccess(request)
    }

    /** Storico chat recente. Lista vuota se la chiamata fallisce. */
    suspend fun fetchMessages(): List<ChatMessage> {
        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/api/messages")
            .header("X-Device-Token", BackendConfig.deviceToken)
            .get()
            .build()

        val responseBody = executeForBody(request) ?: return emptyList()
        return try {
            val arr = JSONObject(responseBody).getJSONArray("messages")
            (0 until arr.length()).map { ChatMessage.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun executeForSuccess(request: Request): Boolean =
        executeForBody(request) != null

    private suspend fun executeForBody(request: Request): String? =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "${request.url.encodedPath}: chiamata fallita (rete)", e)
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            Log.w(TAG, "${request.url.encodedPath}: HTTP ${it.code} — ${it.body?.string()}")
                        }
                        val result = if (it.isSuccessful) it.body?.string() ?: "" else null
                        if (cont.isActive) cont.resume(result)
                    }
                }
            })
        }

    companion object {
        private const val TAG = "BackendClient"

        // v0.6.0: un solo OkHttpClient per l'intero processo (vedi
        // Storico versioni sopra), non uno per istanza/chiamata.
        private val HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

enum class SosHeartbeatResult { ACCEPTED, STOP, FAILED }

/** Esito di triggerEvent() — vedi Storico versioni sopra (v0.9.0). */
data class TriggerEventResult(val ok: Boolean, val dnd: Boolean?)
