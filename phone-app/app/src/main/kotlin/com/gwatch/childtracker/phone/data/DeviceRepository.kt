package com.gwatch.childtracker.phone.data

// v0.3.0 (2026-09-10): tutti i listener Firestore qui sotto ignoravano
// silenziosamente il secondo parametro (l'errore) di addSnapshotListener
// — un permission-denied o un indice mancante sparivano senza lasciare
// traccia, mostrando solo una lista vuota (es. "sezione messaggi"
// sempre vuota nonostante il backend scriva/notifichi correttamente).
// Aggiunto un log esplicito per ogni listener cosi' un errore reale e'
// almeno visibile in logcat invece di sembrare "nessun dato".
// v0.4.0 (2026-09-11): supporto N bambini (fase 2/4, vedi CONTEXT.md).
// Le geofence non sono piu' lette/scritte sotto devices/{DEVICE_ID}/
// geofences ma sulla nuova collezione radice "geofences" (campo
// childIds — una zona puo' valere per piu' bambini), coerente con
// backend/firestore.rules v0.6.0/device-config.js v0.4.0. Aggiunto
// anche observeChildren(): query live su "devices" per popolare il
// selettore di toggle per-bambino in GeofenceScreen.kt.
// v0.5.0 (2026-09-11): fase 3/4 — MapScreen mostra ora tutti i bambini
// insieme, non piu' un solo "deviceRef" fisso. observeDeviceState/
// observeHistory/observeRecentEvents prendono un childId esplicito
// invece di usare sempre Constants.DEVICE_ID (che resta il default
// solo per observeMessages, ancora a singola chat fino alla fase 4).
// Aggiunti anche observeOwnNickname/updateOwnNickname
// (parents/{uid}.nickname) per la nuova SettingsScreen.kt.
// v0.6.0 (2026-09-11): fase 4/4 — observeMessages prende ora un childId
// esplicito (thread scelto dal selettore destinatario di ChatScreen.kt,
// non piu' sempre Constants.DEVICE_ID) e mappa anche senderId/
// senderName/childId dal documento, denormalizzati al momento
// dell'invio (vedi backend/api/send-message.js, parent-command.js).

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.gwatch.childtracker.phone.data.model.ChatMessage
import com.gwatch.childtracker.phone.data.model.ChildInfo
import com.gwatch.childtracker.phone.data.model.DeviceEvent
import com.gwatch.childtracker.phone.data.model.DeviceState
import com.gwatch.childtracker.phone.data.model.GeofenceZone
import com.gwatch.childtracker.phone.data.model.LatLon
import com.gwatch.childtracker.phone.data.model.LocationPoint
import com.gwatch.childtracker.phone.util.Constants
import java.util.Date
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Legge/scrive direttamente su Firestore lato client, protetto dalle
 * regole di sicurezza (backend/firestore.rules): un genitore autorizzato
 * puo' leggere tutto sotto devices/{childId} (per ogni bambino, vedi
 * observeChildren) e scrivere solo le geofence e il proprio nickname;
 * posizione/batteria/eventi sono scritti solo dal backend tramite Admin
 * SDK (bypassa le regole), mai dal client.
 */
class DeviceRepository {

    private val db = FirebaseFirestore.getInstance()

    fun observeDeviceState(childId: String): Flow<DeviceState> = callbackFlow {
        val registration = db.collection("devices").document(childId).addSnapshotListener { snap, error ->
            if (error != null) Log.e(TAG, "observeDeviceState", error)
            if (snap == null || !snap.exists()) {
                trySend(DeviceState())
                return@addSnapshotListener
            }
            val locMap = snap.get("lastLocation") as? Map<*, *>
            val lat = (locMap?.get("lat") as? Number)?.toDouble()
            val lon = (locMap?.get("lon") as? Number)?.toDouble()
            trySend(
                DeviceState(
                    lastLocation = if (lat != null && lon != null) LatLon(lat, lon) else null,
                    accuracy = (locMap?.get("accuracy") as? Number)?.toDouble(),
                    battery = (snap.get("battery") as? Number)?.toInt(),
                    activity = snap.getString("activity"),
                    lastSeenMillis = snap.getTimestamp("lastSeen")?.toDate()?.time,
                    sosActive = snap.getBoolean("sosActive") ?: false,
                ),
            )
        }
        awaitClose { registration.remove() }
    }

    fun observeHistory(childId: String, hours: Long = Constants.HISTORY_WINDOW_HOURS): Flow<List<LocationPoint>> =
        callbackFlow {
            val since = Timestamp(Date(System.currentTimeMillis() - hours * 3_600_000L))
            val query = db.collection("devices").document(childId).collection("locations")
                .whereGreaterThan("timestamp", since)
                .orderBy("timestamp", Query.Direction.ASCENDING)

            val registration = query.addSnapshotListener { snap, error ->
                if (error != null) Log.e(TAG, "observeHistory", error)
                if (snap == null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(
                    snap.documents.mapNotNull { d ->
                        val lat = d.getDouble("lat")
                        val lon = d.getDouble("lon")
                        val ts = d.getTimestamp("timestamp")?.toDate()?.time
                        if (lat == null || lon == null || ts == null) return@mapNotNull null
                        LocationPoint(
                            lat = lat,
                            lon = lon,
                            accuracy = d.getDouble("accuracy"),
                            battery = (d.get("battery") as? Number)?.toInt(),
                            activity = d.getString("activity"),
                            timestampMillis = ts,
                        )
                    },
                )
            }
            awaitClose { registration.remove() }
        }

    // v0.4.0: collezione radice "geofences" (non piu' annidata sotto un
    // singolo device) — una zona puo' valere per piu' bambini, vedi
    // GeofenceZone.childIds e backend/firestore.rules v0.6.0.
    fun observeGeofences(): Flow<List<GeofenceZone>> = callbackFlow {
        val registration = db.collection("geofences").addSnapshotListener { snap, error ->
            if (error != null) Log.e(TAG, "observeGeofences", error)
            if (snap == null) {
                trySend(emptyList())
                return@addSnapshotListener
            }
            trySend(
                snap.documents.map { d ->
                    GeofenceZone(
                        id = d.id,
                        name = d.getString("name") ?: "",
                        lat = d.getDouble("lat") ?: 0.0,
                        lon = d.getDouble("lon") ?: 0.0,
                        radiusMeters = d.getDouble("radiusMeters") ?: 150.0,
                        active = d.getBoolean("active") ?: true,
                        notifyOnEnter = d.getBoolean("notifyOnEnter") ?: true,
                        notifyOnExit = d.getBoolean("notifyOnExit") ?: true,
                        alarmOnExit = d.getBoolean("alarmOnExit") ?: false,
                        childIds = (d.get("childIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                    )
                },
            )
        }
        awaitClose { registration.remove() }
    }

    // v0.4.0: elenco bambini registrati (per il selettore toggle di
    // GeofenceScreen.kt e, dalle prossime fasi, chat/mappa/impostazioni).
    // Query live sull'intera collezione "devices", gia' leggibile da
    // qualunque genitore autorizzato (backend/firestore.rules, isParent()
    // non dipende dal singolo id).
    fun observeChildren(): Flow<List<ChildInfo>> = callbackFlow {
        val registration = db.collection("devices").addSnapshotListener { snap, error ->
            if (error != null) Log.e(TAG, "observeChildren", error)
            if (snap == null) {
                trySend(emptyList())
                return@addSnapshotListener
            }
            trySend(
                snap.documents.map { d ->
                    ChildInfo(id = d.id, name = d.getString("childName")?.takeIf { it.isNotBlank() } ?: d.id)
                },
            )
        }
        awaitClose { registration.remove() }
    }

    fun observeRecentEvents(childId: String, limit: Long = 20): Flow<List<DeviceEvent>> = callbackFlow {
        val registration = db.collection("devices").document(childId).collection("events")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, error ->
                if (error != null) Log.e(TAG, "observeRecentEvents", error)
                if (snap == null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(
                    snap.documents.map { d ->
                        DeviceEvent(
                            id = d.id,
                            type = d.getString("type") ?: "",
                            lat = d.getDouble("lat") ?: 0.0,
                            lon = d.getDouble("lon") ?: 0.0,
                            zoneName = d.getString("zoneName"),
                            source = d.getString("source"),
                            acknowledged = d.getBoolean("acknowledged") ?: false,
                            timestampMillis = d.getTimestamp("timestamp")?.toDate()?.time ?: 0L,
                        )
                    },
                )
            }
        awaitClose { registration.remove() }
    }

    // Sola lettura: l'invio passa da BackendClient.sendMessageToChild
    // (serve la push FCM nella stessa chiamata, vedi
    // backend/api/send-message.js), non da qui.
    fun observeMessages(childId: String, limit: Long = 50): Flow<List<ChatMessage>> = callbackFlow {
        val registration = db.collection("devices").document(childId).collection("messages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, error ->
                if (error != null) Log.e(TAG, "observeMessages", error)
                if (snap == null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(
                    snap.documents.mapNotNull { d ->
                        val ts = d.getTimestamp("timestamp")?.toDate()?.time ?: return@mapNotNull null
                        ChatMessage(
                            id = d.id,
                            sender = d.getString("sender") ?: "parent",
                            senderId = d.getString("senderId"),
                            senderName = d.getString("senderName"),
                            childId = childId,
                            text = d.getString("text") ?: "",
                            timestampMillis = ts,
                        )
                    }.reversed(), // ordine cronologico per la UI
                )
            }
        awaitClose { registration.remove() }
    }

    suspend fun saveGeofence(zone: GeofenceZone) {
        val ref = if (zone.id.isBlank()) {
            db.collection("geofences").document()
        } else {
            db.collection("geofences").document(zone.id)
        }
        ref.set(
            mapOf(
                "name" to zone.name,
                "lat" to zone.lat,
                "lon" to zone.lon,
                "radiusMeters" to zone.radiusMeters,
                "active" to zone.active,
                "notifyOnEnter" to zone.notifyOnEnter,
                "notifyOnExit" to zone.notifyOnExit,
                "alarmOnExit" to zone.alarmOnExit,
                "childIds" to zone.childIds,
            ),
            SetOptions.merge(),
        ).await()
    }

    suspend fun deleteGeofence(id: String) {
        db.collection("geofences").document(id).delete().await()
    }

    // parents/{uid} esiste gia' (pre-creato da admin, vedi CONTEXT.md);
    // qui aggiorniamo solo il proprio array di token FCM.
    suspend fun registerFcmToken(uid: String, token: String) {
        db.collection("parents").document(uid)
            .update("fcmTokens", FieldValue.arrayUnion(token))
            .await()
    }

    // v0.5.0: nickname del genitore — scritto direttamente dal client
    // (le regole gia' permettono update su parents/{parentId} == proprio
    // uid, stesso pattern di registerFcmToken sopra).
    fun observeOwnNickname(uid: String): Flow<String?> = callbackFlow {
        val registration = db.collection("parents").document(uid).addSnapshotListener { snap, error ->
            if (error != null) Log.e(TAG, "observeOwnNickname", error)
            trySend(snap?.getString("nickname"))
        }
        awaitClose { registration.remove() }
    }

    suspend fun updateOwnNickname(uid: String, nickname: String) {
        db.collection("parents").document(uid)
            .set(mapOf("nickname" to nickname), SetOptions.merge())
            .await()
    }

    companion object {
        private const val TAG = "DeviceRepository"
    }
}
