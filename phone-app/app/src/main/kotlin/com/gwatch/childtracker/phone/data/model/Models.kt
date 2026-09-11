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
    // v0.6.0 (2026-09-11): a chi si applica la zona — supporto N
    // bambini (vedi CONTEXT.md fase 2/4). Le geofence sono ora una
    // collezione radice condivisa (backend/firestore.rules), non piu'
    // annidate sotto un singolo device.
    val childIds: List<String> = emptyList(),
)

// v0.6.0 (2026-09-11): elenco bambini registrati (query live su
// "devices", vedi DeviceRepository.observeChildren) — usato dal
// selettore toggle per-bambino di GeofenceScreen.kt.
data class ChildInfo(
    val id: String = "",
    val name: String = "",
)

data class DeviceEvent(
    val id: String = "",
    val type: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val zoneName: String? = null,
    // v0.24.0 (2026-09-10): "source" ("child" | "parent", solo su
    // "location_request") e "acknowledged" — usati da MapScreen.kt per
    // sapere quali eventi "posizione inviata dal bambino" notificare al
    // watch come visti (vedi backend/api/ack-event.js), senza rifarlo
    // per eventi gia' marcati.
    val source: String? = null,
    val acknowledged: Boolean = false,
    val timestampMillis: Long = 0L,
)

data class ChatMessage(
    val id: String = "",
    val sender: String = "parent", // "parent" | "child"
    val text: String = "",
    val timestampMillis: Long = 0L,
)
