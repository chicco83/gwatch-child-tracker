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
    // v0.4.0 (2026-09-10): SOS attivo — mostra il banner di
    // disattivazione sulla mappa (vedi MapScreen.kt/trigger-event.js,
    // che lo marca true al primo evento "sos" di un episodio).
    val sosActive: Boolean = false,
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
    // v0.21.0 (2026-09-10): toggle per-zona configurabili da
    // GeofenceScreen.kt. notifyOnEnter/notifyOnExit di default true
    // (comportamento invariato per le zone gia' esistenti, che non
    // hanno ancora questi campi su Firestore); alarmOnExit di default
    // false (opt-in esplicito, vedi backend/api/trigger-event.js e
    // phone-app/.../alarm/ExitAlarmService.kt).
    val notifyOnEnter: Boolean = true,
    val notifyOnExit: Boolean = true,
    val alarmOnExit: Boolean = false,
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
