package com.gwatch.childtracker.network

// v0.4.0 (2026-09-10): aggiunto sosHeartbeat, per il ping di posizione
// ogni 30" durante un SOS attivo (vedi
// location/SosLocationService.kt e backend/api/sos-heartbeat.js).
// Esito a 3 stati (non solo bool) perche' un 409 ("SOS non piu'
// attivo") e' un segnale distinto da un errore di rete: il primo dice
// al service di fermarsi, il secondo di ritentare al prossimo giro.

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

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

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
     */
    suspend fun triggerEvent(
        type: String,
        lat: Double,
        lon: Double,
        accuracy: Float?,
        battery: Int?,
        zoneName: String? = null,
        timestampMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val body = JSONObject().apply {
            put("type", type)
            put("lat", lat)
            put("lon", lon)
            accuracy?.let { put("accuracy", it.toDouble()) }
            battery?.let { put("battery", it) }
            zoneName?.let { put("zoneName", it) }
            put("timestamp", timestampMillis)
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
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val result = if (it.isSuccessful) it.body?.string() ?: "" else null
                        if (cont.isActive) cont.resume(result)
                    }
                }
            })
        }
}

enum class SosHeartbeatResult { ACCEPTED, STOP, FAILED }
