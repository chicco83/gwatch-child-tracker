package com.gwatch.childtracker.phone.data

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
 * Unica chiamata REST verso il backend fatta dalla phone-app: l'invio
 * di un messaggio di chat al watch. Non e' un client Firestore diretto
 * come DeviceRepository perche' serve anche la push FCM che sveglia il
 * watch, e su Vercel non esiste un trigger equivalente a
 * onDocumentCreated (vedi backend/api/send-message-to-child.js) — deve
 * quindi passare da un endpoint che fa scrittura+push nella stessa
 * chiamata. Stessa libreria (OkHttp) e stile di watch-app/network/BackendClient.kt,
 * per coerenza.
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

        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (cont.isActive) cont.resume(it.isSuccessful)
                    }
                }
            })
        }
    }
}
