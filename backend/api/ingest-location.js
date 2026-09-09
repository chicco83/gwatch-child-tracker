/**
 * POST /api/ingest-location
 * Versione: 0.1.0
 *
 * Riceve dal watch un batch di punti posizione accumulati (risparmio
 * batteria: un solo invio di rete per piu' punti, vedi CONTEXT.md) e
 * aggiorna sia lo stato corrente del dispositivo sia lo storico.
 *
 * Auth: header "X-Device-Token".
 * Body: { points: [{ lat, lon, accuracy?, battery?, activity?, timestamp? }, ...] }
 */
const { getFirestore, Timestamp, FieldValue } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkDeviceToken } = require("./_lib/auth");

// MVP: un solo dispositivo tracciato. Rendere dinamico quando si
// passera' a multi-figlio (Fase 2 backlog, vedi CONTEXT.md).
const DEVICE_ID = "figlio";
const HISTORY_RETENTION_HOURS = 48;

module.exports = async (req, res) => {
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

  getAdminApp();
  const db = getFirestore();
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
      expiresAt, // usato dalla TTL policy Firestore per la pulizia automatica
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
};
