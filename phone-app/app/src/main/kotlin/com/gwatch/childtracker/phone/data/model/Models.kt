package com.gwatch.childtracker.phone.data.model

// Modelli allineati ai campi scritti dal backend (vedi
// backend/api/ingest-location.js, trigger-event.js, device-config.js).

data class LatLon(val lat: Double, val lon: Double)

data class DeviceState(
    val lastLocation: LatLon? = null,
    val accuracy: Double? = null,
    val battery: Int? = null,
    val activity: String? = null,
    val lastSeenMillis: Long? = null,
)

data class LocationPoint(
    val lat: Double,
    val lon: Double,
    val accuracy: Double? = null,
    val battery: Int? = null,
    val activity: String? = null,
    val timestampMillis: Long,
)

data class GeofenceZone(
    val id: String = "",
    val name: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radiusMeters: Double = 150.0,
    val active: Boolean = true,
)

data class DeviceEvent(
    val id: String = "",
    val type: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val zoneName: String? = null,
    val timestampMillis: Long = 0L,
)

data class ChatMessage(
    val id: String = "",
    val sender: String = "parent", // "parent" | "child"
    val text: String = "",
    val timestampMillis: Long = 0L,
)
