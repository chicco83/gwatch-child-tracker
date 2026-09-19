/**
 * POST /api/ingest-location
 * Versione: 0.7.0
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
 * - 0.4.0 (2026-09-18): aggiunti "batteryTemp"/"charging" ai punti
 *   (richiesti dall'utente — vedi watch-app/.../location/BatteryInfo.kt),
 *   salvati sia sullo storico locations sia sull'ultimo stato del
 *   device, stesso pattern gia' in uso per "battery"/"activity".
 * - 0.5.0 (2026-09-18): aggiunto "speed" (m/s, Location.getSpeed() —
 *   richiesto dall'utente per mostrarla sulla mappa), stesso pattern.
 * - 0.6.0 (2026-09-19): richiesto dall'utente — notifiche automatiche
 *   al genitore quando la batteria del watch scende al 10%/5%, con
 *   richiesta posizione + messaggio automatico al watch al 2% (vedi
 *   nuovo _lib/batteryAlerts.js, chiamato qui dopo il commit del batch
 *   con la batteria dell'ultimo punto ricevuto).
 * - 0.7.0 (2026-09-19): aggiunto "batteryHoursRemaining" ai punti
 *   (richiesto dall'utente — autonomia residua in ore, chiesta dal
 *   watch al proprio sistema operativo, vedi watch-app/.../location/
 *   BatteryInfo.kt), salvato sull'ultimo stato del device insieme al
 *   resto della batteria. Non salvato sullo storico locations (a
 *   differenza di battery/batteryTemp/ecc.): non serve nessuna
 *   estrapolazione lato client, il dato e' gia' la stima diretta del
 *   sistema operativo del watch ad ogni campione.
 */
const { getFirestore, Timestamp, FieldValue } = require("firebase-admin/firestore");
const { wrapHandler, errorResponse, successResponse, logError } = require("./_lib/errors.js");
const { getAdminApp } = require("./_lib/firebase-admin");
const { resolveDeviceId } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");
const { validateConfig } = require("./_lib/config.js");
const { checkBatteryAlerts } = require("./_lib/batteryAlerts.js");

const HISTORY_RETENTION_HOURS = 24 * 365; // 12 mesi, vedi nota sopra
const MAX_POINTS_PER_REQUEST = 100; // limite difensivo per singola chiamata

module.exports = wrapHandler(async (req, res) => {
  try {
    validateConfig();
  } catch (err) {
    console.error("Config validation failed:", err.message);
    res.status(500).send("Internal server error: configuration");
    return;
  }


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
      batteryTemp: p.batteryTemp ?? null,
      charging: p.charging ?? null,
      speed: p.speed ?? null,
      timestamp: ts,
      expiresAt, // usato dalla TTL policy Firestore per la pulizia automatica
    });
    // batteryHoursRemaining non va nello storico locations qui sopra
    // (vedi Storico versioni 0.7.0): e' gia' una stima diretta del
    // sistema operativo del watch, non un dato da riguardare nel tempo.

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
        batteryTemp: last.batteryTemp ?? null,
        charging: last.charging ?? null,
        speed: last.speed ?? null,
        batteryHoursRemaining: last.batteryHoursRemaining ?? null,
        lastSeen: last.timestamp,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
  }

  await batch.commit();

  if (last) {
    await checkBatteryAlerts(db, deviceRef, childId, last.battery ?? null, last.charging ?? null);
  }

  successResponse(res, { received: points.length });
});
