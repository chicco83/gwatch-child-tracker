package com.gwatch.childtracker.phone.data

// v0.23.0 (2026-09-10): nuovo — richiesto un campo di ricerca indirizzi
// in GeofenceScreen.kt, la mappa parte sempre centrata su Roma finche'
// non si cerca/tocca un punto. Usa Nominatim (OpenStreetMap), lo
// stesso servizio gratuito e senza chiave API dei tile della mappa
// (osmdroid, vedi CONTEXT.md v0.17.0) — nessuna fatturazione Google,
// coerente con la scelta gia' fatta per la mappa stessa. Policy d'uso
// di Nominatim: richiede uno User-Agent che identifichi l'app (mai
// quello di default di OkHttp) — qui rispettata; il ritmo di chiamate
// resta comunque leggero perche' la ricerca parte solo su azione
// esplicita dell'utente (pulsante "Cerca"), mai in automatico mentre
// si digita.

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class GeocodingResult(val displayName: String, val lat: Double, val lon: Double)

class GeocodingClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Lista vuota se la ricerca fallisce o non trova nulla — mai un'eccezione verso il chiamante. */
    suspend fun search(query: String): List<GeocodingResult> {
        if (query.isBlank()) return emptyList()

        val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("limit", "5")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "gwatch-child-tracker (app di localizzazione familiare, uso personale)")
            .build()

        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(emptyList())
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            if (cont.isActive) cont.resume(emptyList())
                            return
                        }
                        val results = runCatching {
                            val arr = JSONArray(it.body?.string() ?: "[]")
                            (0 until arr.length()).map { i ->
                                val obj = arr.getJSONObject(i)
                                GeocodingResult(
                                    displayName = obj.getString("display_name"),
                                    lat = obj.getString("lat").toDouble(),
                                    lon = obj.getString("lon").toDouble(),
                                )
                            }
                        }.getOrDefault(emptyList())
                        if (cont.isActive) cont.resume(results)
                    }
                }
            })
        }
    }
}
