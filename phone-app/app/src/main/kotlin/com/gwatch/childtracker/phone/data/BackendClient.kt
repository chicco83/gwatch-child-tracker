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
// v0.3.0 (2026-09-10): backend/api/ era arrivato a 13 file — il piano
//   Hobby di Vercel ne permette al massimo 12 per deployment, il build
//   falliva da 3 commit senza che nessuno se ne accorgesse (nessuna
//   delle modifiche backend recenti era mai realmente online).
//   send-message-to-child, request-location, cancel-sos e ack-event
//   sono stati accorpati in un unico endpoint backend/api/parent-
//   command.js (dispatch su un campo "action" nel body): tutti i
//   metodi qui sotto ora chiamano quello, cambia solo il body inviato.
// v0.4.0 (2026-09-11): supporto N bambini (fase 3/4, vedi CONTEXT.md).
//   Il backend richiede gia' da fase 1 un "childId" esplicito nel body
//   per "message"/"request_location"/"cancel_sos"/"ack_event"
//   (parent-command.js v0.2.0) — questa versione del client non lo
//   mandava ancora: le quattro chiamate rispondevano gia' 400 "'childId'
//   mancante" (bug latente, mai emerso perche' la UI a valle non era
//   ancora stata aggiornata). Aggiunto il parametro childId a tutte e
//   quattro. Aggiunti anche setChildNickname e createChild per la nuova
//   SettingsScreen.kt (nickname bambini, "Aggiungi bambino").

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
 * watch, richiesta posizione immediata, annulla SOS, conferma lettura
 * posizione — tutte via backend/api/parent-command.js). Non e' un
 * client Firestore diretto come DeviceRepository perche' serve anche
 * la push FCM che sveglia il watch, e su Vercel non esiste un trigger
 * equivalente a onDocumentCreated: deve quindi passare da un endpoint
 * che fa scrittura+push (o solo push) nella stessa chiamata. Stessa
 * libreria (OkHttp) e stile di watch-app/network/BackendClient.kt, per
 * coerenza.
 */
/** Esito di "create_child": il token esce in chiaro solo in questa risposta, una volta sola. */
data class NewChildResult(val childId: String, val deviceToken: String)

class BackendClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** true se il messaggio e' stato accettato (HTTP 2xx). */
    suspend fun sendMessageToChild(idToken: String, childId: String, text: String): Boolean {
        val body = JSONObject().apply {
            put("action", "message")
            put("childId", childId)
            put("text", text)
        }
        return callParentCommand(idToken, body, "sendMessageToChild")
    }

    /** true se la richiesta e' stata accettata (HTTP 2xx): il watch riceve la push a parte. */
    suspend fun requestLocation(idToken: String, childId: String): Boolean {
        val body = JSONObject().apply {
            put("action", "request_location")
            put("childId", childId)
        }
        return callParentCommand(idToken, body, "requestLocation")
    }

    /** Disattiva un SOS in corso. */
    suspend fun cancelSos(idToken: String, childId: String): Boolean {
        val body = JSONObject().apply {
            put("action", "cancel_sos")
            put("childId", childId)
        }
        return callParentCommand(idToken, body, "cancelSos")
    }

    /**
     * Marca un evento come "visto" dal genitore — usato per notificare
     * al watch che la posizione inviata dal bambino e' stata guardata.
     */
    suspend fun ackEvent(idToken: String, childId: String, eventId: String): Boolean {
        val body = JSONObject().apply {
            put("action", "ack_event")
            put("childId", childId)
            put("eventId", eventId)
        }
        return callParentCommand(idToken, body, "ackEvent")
    }

    /** Rinomina un bambino (devices/{childId}.childName). */
    suspend fun setChildNickname(idToken: String, childId: String, nickname: String): Boolean {
        val body = JSONObject().apply {
            put("action", "set_nickname")
            put("childId", childId)
            put("nickname", nickname)
        }
        return callParentCommand(idToken, body, "setChildNickname")
    }

    /**
     * Registra un nuovo bambino: il backend genera childId + token e
     * salva solo l'hash del token. Il token in chiaro torna SOLO in
     * questa risposta (null se la chiamata fallisce) — la UI deve
     * mostrarlo subito con l'invito a copiarlo nel local.properties del
     * nuovo build watch, non sara' piu' recuperabile in seguito.
     */
    suspend fun createChild(idToken: String, nickname: String): NewChildResult? {
        val body = JSONObject().apply {
            put("action", "create_child")
            put("nickname", nickname)
        }
        val request = Request.Builder()
            .url("${Constants.BACKEND_BASE_URL}/api/parent-command")
            .header("Authorization", "Bearer $idToken")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return executeForNewChild(request, "createChild")
    }

    private suspend fun callParentCommand(idToken: String, body: JSONObject, tag: String): Boolean {
        val request = Request.Builder()
            .url("${Constants.BACKEND_BASE_URL}/api/parent-command")
            .header("Authorization", "Bearer $idToken")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return execute(request, tag)
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

    private suspend fun executeForNewChild(request: Request, tag: String): NewChildResult? =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "$tag: chiamata fallita", e)
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            Log.w(TAG, "$tag: HTTP ${it.code} — ${it.body?.string()}")
                            if (cont.isActive) cont.resume(null)
                            return
                        }
                        val json = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
                        val childId = json?.optString("childId")?.takeIf { s -> s.isNotBlank() }
                        val token = json?.optString("deviceToken")?.takeIf { s -> s.isNotBlank() }
                        val result = if (childId != null && token != null) NewChildResult(childId, token) else null
                        if (cont.isActive) cont.resume(result)
                    }
                }
            })
        }

    companion object {
        private const val TAG = "BackendClient"
    }
}
