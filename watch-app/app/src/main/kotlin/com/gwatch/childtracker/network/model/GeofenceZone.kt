package com.gwatch.childtracker.network.model

import org.json.JSONObject

/** Una zona geofence come restituita da GET /api/device-config. */
data class GeofenceZone(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Float,
) {
    companion object {
        fun fromJson(json: JSONObject): GeofenceZone = GeofenceZone(
            id = json.getString("id"),
            name = json.optString("name", json.getString("id")),
            lat = json.getDouble("lat"),
            lon = json.getDouble("lon"),
            radiusMeters = json.getDouble("radiusMeters").toFloat(),
        )
    }
}
