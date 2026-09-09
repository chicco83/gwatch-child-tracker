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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
        accuracy?.let { put("accuracy", it.toDouble()) }
        battery?.let { put("battery", it) }
        activity?.let { put("activity", it) }
        put("timestamp", timestampMillis)
    }

    companion object {
        fun fromJson(json: JSONObject): LocationPoint = LocationPoint(
            lat = json.getDouble("lat"),
            lon = json.getDouble("lon"),
            accuracy = if (json.has("accuracy")) json.getDouble("accuracy").toFloat() else null,
            battery = if (json.has("battery")) json.getInt("battery") else null,
            activity = if (json.has("activity")) json.getString("activity") else null,
            timestampMillis = json.getLong("timestamp"),
        )
    }
}
