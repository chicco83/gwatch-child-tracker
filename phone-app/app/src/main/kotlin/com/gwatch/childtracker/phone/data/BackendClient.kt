package com.gwatch.childtracker.phone.data

// Storico versioni
// v0.1.0 (2026-09-09): sendMessageToChild, esito bool senza logging —
//   un fallimento (401/429/errore di rete) spariva senza lasciare
//   traccia, indistinguibile da un successo lato log ("i messaggi non
//   partono" era invisibile da qui: solo la UI lo sapeva, e la UI
//   ignorava il risultato, vedi ChatScreen.kt).
// v0.2.0 (2026-09-10): aggiunto logging su ogni esito negativo (status
//   HTTP o eccezione di rete) per poter diagnosticare da logcat.
//   Aggiunto requestLocation, stesso stile/endpoint pattern, per il
//   pulsante "Aggiorna posizione" sulla mappa (vedi
//   backend/api/request-location.js).

import android.util.Log
import com.gwatch.childtracker.phone.util.Constants
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Chiamate REST verso il backend fatte dalla phone-app (invio chat al
 * watch, richiesta posizione immediata). Non e' un client Firestore
 * diretto come DeviceRepository perche' serve anche la push FCM che
 * sveglia il watch, e su Vercel non esiste un trigger equivalente a
 * onDocumentCreated (vedi backend/api/send-message-to-child.js) — deve
 * quindi passare da un endpoint che fa scrittura+push (o solo push)
 * nella stessa chiamata. Stessa libreria (OkHttp) e stile di
 * watch-app/network/BackendClient.kt, per coerenza.
 */
class BackendClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** true se il messaggio e' stato accettato (HTTP 2xx). */
    suspend fun sendMessageToChild(idToken: String, text: String): Boolean {
        val body = JSONObject().apply { put("text", text) }
        val request = Request.Builder()
            .url("${Constants.BACKEND_BASE_URL}/api/send-message-to-child")
            .header("Authorization", "Bearer $idToken")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return execute(request, "sendMessageToChild")
    }

    /** true se la richiesta e' stata accettata (HTTP 2xx): il watch riceve la push a parte. */
    suspend fun requestLocation(idToken: String): Boolean {
        val request = Request.Builder()
            .url("${Constants.BACKEND_BASE_URL}/api/request-location")
            .header("Authorization", "Bearer $idToken")
            .post("".toRequestBody(jsonMediaType))
            .build()
        return execute(request, "requestLocation")
    }

    /** Disattiva un SOS in corso (vedi backend/api/cancel-sos.js). */
    suspend fun cancelSos(idToken: String): Boolean {
        val request = Request.Builder()
            .url("${Constants.BACKEND_BASE_URL}/api/cancel-sos")
            .header("Authorization", "Bearer $idToken")
            .post("".toRequestBody(jsonMediaType))
            .build()
        return execute(request, "cancelSos")
    }

    /**
     * Marca un evento come "visto" dal genitore — usato per notificare
     * al watch che la posizione inviata dal bambino e' stata guardata
     * (vedi backend/api/ack-event.js).
     */
    suspend fun ackEvent(idToken: String, eventId: String): Boolean {
        val body = JSONObject().apply { put("eventId", eventId) }
        val request = Request.Builder()
            .url("${Constants.BACKEND_BASE_URL}/api/ack-event")
            .header("Authorization", "Bearer $idToken")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return execute(request, "ackEvent")
    }

    private suspend fun execute(request: Request, tag: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "$tag: chiamata fallita", e)
                    if (cont.isActive) cont.resume(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            Log.w(TAG, "$tag: HTTP ${it.code} — ${it.body?.string()}")
                        }
                        if (cont.isActive) cont.resume(it.isSuccessful)
                    }
                }
            })
        }

    companion object {
        private const val TAG = "BackendClient"
    }
}
