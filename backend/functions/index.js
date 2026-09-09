/**
 * Cloud Functions backend — gwatch-child-tracker
 * Versione: 0.1.0
 * Ultimo aggiornamento: 2026-09-09
 *
 * Endpoint HTTPS esposti:
 *  - POST /ingestLocation  (watch -> backend, auth: header X-Device-Token)
 *      Riceve un batch di punti posizione accumulati dal watch (vedi
 *      CONTEXT.md: invio in batch per risparmio batteria) e aggiorna
 *      sia lo stato corrente del dispositivo sia lo storico.
 *  - POST /triggerSos      (watch -> backend, auth: header X-Device-Token)
 *      Percorso separato da ingestLocation per garantire priorità e
 *      latenza minima, indipendente dal batching della posizione
 *      normale.
 *  - GET  /deviceConfig    (watch -> backend, auth: header X-Device-Token)
 *      Restituisce le geofence attive configurate dal genitore, cosi'
 *      il watch le registra localmente con la Geofencing API Android.
 *  - GET  /haStatus        (Home Assistant -> backend, auth: header
 *      "Authorization: Bearer <token>")
 *      Endpoint di sola lettura per il polling di HA. Livello
 *      aggiuntivo opzionale (vedi CONTEXT.md, decisione v0.3.0): la sua
 *      assenza o il suo fallimento non devono mai influenzare le altre
 *      funzioni.
 *
 * Trigger Firestore:
 *  - onEventCreated: su nuovo evento (sos, in futuro anche
 *    geofence_enter/exit) invia una push FCM al genitore. Funzione
 *    nativa dell'app, non dipende da Home Assistant.
 *
 * Retention storico: i documenti in devices/{id}/locations portano un
 * campo "expiresAt" pensato per una TTL policy Firestore (da abilitare
 * una tantum lato Google Cloud Console/gcloud, non gestibile da questo
 * file — vedi backend/README.md).
 */

const { onRequest } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { defineString } = require("firebase-functions/params");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();
const db = getFirestore();

// Token condivisi, valorizzati da backend/functions/.env (locale) o dai
// secret dell'ambiente in produzione. Vedi .env.example.
const DEVICE_TOKEN = defineString("DEVICE_TOKEN");
const HA_STATUS_TOKEN = defineString("HA_STATUS_TOKEN");

// MVP: un solo dispositivo tracciato. Rendere dinamico (path param o
// claim del token) quando si passera' a multi-figlio (Fase 2 backlog).
const DEVICE_ID = "figlio";
const HISTORY_RETENTION_HOURS = 48;

function checkDeviceToken(req) {
  const token = req.get("X-Device-Token");
  return Boolean(token) && token === DEVICE_TOKEN.value();
}

function checkHaToken(req) {
  const header = req.get("Authorization") || "";
  const token = header.replace(/^Bearer\s+/i, "");
  return Boolean(token) && token === HA_STATUS_TOKEN.value();
}

exports.ingestLocation = onRequest(async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }
  if (!checkDeviceToken(req)) {
    res.status(401).send("Unauthorized");
    return;
  }

  const points = Array.isArray(req.body?.points) ? req.body.points : null;
  if (!points || points.length === 0) {
    res.status(400).send("Bad Request: 'points' mancante o vuoto");
    return;
  }

  const deviceRef = db.collection("devices").doc(DEVICE_ID);
  const batch = db.batch();
  let last = null;

  for (const p of points) {
    if (typeof p.lat !== "number" || typeof p.lon !== "number") continue;

    const ts = p.timestamp ? Timestamp.fromMillis(p.timestamp) : Timestamp.now();
    const expiresAt = Timestamp.fromMillis(
      ts.toMillis() + HISTORY_RETENTION_HOURS * 60 * 60 * 1000
    );

    const locRef = deviceRef.collection("locations").doc();
    batch.set(locRef, {
      lat: p.lat,
      lon: p.lon,
      accuracy: p.accuracy ?? null,
      battery: p.battery ?? null,
      activity: p.activity ?? null,
      timestamp: ts,
      expiresAt,
    });

    if (!last || ts.toMillis() > last.timestamp.toMillis()) {
      last = { ...p, timestamp: ts };
    }
  }

  if (last) {
    batch.set(
      deviceRef,
      {
        lastLocation: {
          lat: last.lat,
          lon: last.lon,
          accuracy: last.accuracy ?? null,
        },
        battery: last.battery ?? null,
        activity: last.activity ?? null,
        lastSeen: last.timestamp,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
  }

  await batch.commit();
  res.status(200).json({ ok: true, received: points.length });
});

exports.triggerSos = onRequest(async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }
  if (!checkDeviceToken(req)) {
    res.status(401).send("Unauthorized");
    return;
  }

  const { lat, lon, accuracy, battery, timestamp } = req.body || {};
  if (typeof lat !== "number" || typeof lon !== "number") {
    res.status(400).send("Bad Request: 'lat'/'lon' mancanti");
    return;
  }

  const ts = timestamp ? Timestamp.fromMillis(timestamp) : Timestamp.now();

  await db
    .collection("devices")
    .doc(DEVICE_ID)
    .collection("events")
    .add({
      type: "sos",
      lat,
      lon,
      accuracy: accuracy ?? null,
      battery: battery ?? null,
      timestamp: ts,
      acknowledged: false,
    });

  res.status(200).json({ ok: true });
});

exports.deviceConfig = onRequest(async (req, res) => {
  if (req.method !== "GET") {
    res.status(405).send("Method Not Allowed");
    return;
  }
  if (!checkDeviceToken(req)) {
    res.status(401).send("Unauthorized");
    return;
  }

  const snap = await db
    .collection("devices")
    .doc(DEVICE_ID)
    .collection("geofences")
    .where("active", "==", true)
    .get();

  const geofences = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
  res.status(200).json({ geofences });
});

exports.haStatus = onRequest(async (req, res) => {
  if (req.method !== "GET") {
    res.status(405).send("Method Not Allowed");
    return;
  }
  if (!checkHaToken(req)) {
    res.status(401).send("Unauthorized");
    return;
  }

  const doc = await db.collection("devices").doc(DEVICE_ID).get();
  if (!doc.exists) {
    res.status(404).json({ error: "device non trovato" });
    return;
  }

  const data = doc.data();
  res.status(200).json({
    lat: data.lastLocation?.lat ?? null,
    lon: data.lastLocation?.lon ?? null,
    accuracy: data.lastLocation?.accuracy ?? null,
    battery: data.battery ?? null,
    last_seen: data.lastSeen ? data.lastSeen.toDate().toISOString() : null,
  });
});

exports.onEventCreated = onDocumentCreated(
  "devices/{deviceId}/events/{eventId}",
  async (event) => {
    const data = event.data?.data();
    if (!data) return;

    const parentSnap = await db.collection("parents").get();
    const tokens = [];
    parentSnap.forEach((p) => {
      const t = p.data().fcmTokens;
      if (Array.isArray(t)) tokens.push(...t);
    });
    if (tokens.length === 0) return;

    const isSos = data.type === "sos";
    await getMessaging().sendEachForMulticast({
      tokens,
      notification: {
        title: isSos ? "SOS ricevuto" : "Aggiornamento posizione",
        body: isSos
          ? "Premuto il pulsante SOS sul watch. Posizione aggiornata."
          : `Evento: ${data.type}`,
      },
      data: {
        type: String(data.type ?? ""),
        lat: String(data.lat ?? ""),
        lon: String(data.lon ?? ""),
      },
      android: { priority: "high" },
    });
  }
);
