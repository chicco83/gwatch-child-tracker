/**
 * POST /api/ingest-location
 * Versione: 0.3.0
 *
 * Riceve dal watch un batch di punti posizione accumulati (risparmio
 * batteria: un solo invio di rete per piu' punti, vedi CONTEXT.md) e
 * aggiorna sia lo stato corrente del dispositivo sia lo storico.
 *
 * Auth: header "X-Device-Token" (identifica il bambino, vedi
 * _lib/auth.js/resolveDeviceId).
 * Body: { points: [{ lat, lon, accuracy?, battery?, activity?, timestamp? }, ...] }
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-09): retention 48h.
 * - 0.2.0 (2026-09-09): retention estesa a 12 mesi (il consumo di
 *   storage resta comunque una piccola frazione del GB gratuito, vedi
 *   CONTEXT.md); aggiunti un tetto massimo di punti per chiamata e la
 *   guardia di quota giornaliera (vedi _lib/quota.js) come rete di
 *   sicurezza contro le soglie gratuite di Firestore/Vercel.
 * - 0.3.0 (2026-09-11): rimosso il "DEVICE_ID" hardcoded ("figlio") —
 *   ora supporta N bambini, il childId si risolve dal token via
 *   resolveDeviceId(req, db) (vedi _lib/auth.js).
 */
const { getFirestore, Timestamp, FieldValue } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { resolveDeviceId } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const HISTORY_RETENTION_HOURS = 24 * 365; // 12 mesi, vedi nota sopra
const MAX_POINTS_PER_REQUEST = 100; // limite difensivo per singola chiamata

module.exports = async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }

  getAdminApp();
  const db = getFirestore();

  const childId = await resolveDeviceId(req, db);
  if (!childId) {
    res.status(401).send("Unauthorized");
    return;
  }

  const points = Array.isArray(req.body?.points) ? req.body.points : null;
  if (!points || points.length === 0) {
    res.status(400).send("Bad Request: 'points' mancante o vuoto");
    return;
  }
  if (points.length > MAX_POINTS_PER_REQUEST) {
    res.status(400).send(`Bad Request: massimo ${MAX_POINTS_PER_REQUEST} punti per chiamata`);
    return;
  }

  const allowed = await checkAndConsumeQuota(db, childId);
  if (!allowed) {
    res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
    return;
  }

  const deviceRef = db.collection("devices").doc(childId);
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
