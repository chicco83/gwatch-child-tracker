package com.gwatch.childtracker.network.model

import org.json.JSONObject

/** Un singolo punto posizione, stesso schema del backend (vedi backend/api/ingest-location.js). */
data class LocationPoint(
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val battery: Int?,
    val activity: String?,
    val timestampMillis: Long,
    // 2026-09-18: richiesto dall'utente ("aggiungi anche la temperatura
    // batteria e lo stato di carica"), vedi location/BatteryInfo.kt.
    val batteryTemp: Double? = null,
    val charging: Boolean? = null,
    // 2026-09-18: richiesto dall'utente ("mostra anche la velocita'
    // sulla mappa") — Location.getSpeed() (m/s) e' gia' incluso gratis
    // in ogni fix GPS (calcolato dal chip GPS stesso, es. via Doppler),
    // nessun costo aggiuntivo al ritmo di campionamento attuale.
    val speedMps: Float? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
        accuracy?.let { put("accuracy", it.toDouble()) }
        battery?.let { put("battery", it) }
        activity?.let { put("activity", it) }
        batteryTemp?.let { put("batteryTemp", it) }
        charging?.let { put("charging", it) }
        speedMps?.let { put("speed", it.toDouble()) }
        put("timestamp", timestampMillis)
    }

    companion object {
        fun fromJson(json: JSONObject): LocationPoint = LocationPoint(
            lat = json.getDouble("lat"),
            lon = json.getDouble("lon"),
            accuracy = if (json.has("accuracy")) json.getDouble("accuracy").toFloat() else null,
            battery = if (json.has("battery")) json.getInt("battery") else null,
            activity = if (json.has("activity")) json.getString("activity") else null,
            batteryTemp = if (json.has("batteryTemp")) json.getDouble("batteryTemp") else null,
            charging = if (json.has("charging")) json.getBoolean("charging") else null,
            speedMps = if (json.has("speed")) json.getDouble("speed").toFloat() else null,
            timestampMillis = json.getLong("timestamp"),
        )
    }
}
