/**
 * GET /api/device-config
 * Versione: 0.4.0
 *
 * Restituisce al watch le geofence attive configurate dal genitore
 * dalla phone-app, per registrarle localmente con la Geofencing API
 * di Android (gestione a livello OS, risparmio batteria).
 *
 * Auth: header "X-Device-Token" (identifica il bambino, vedi
 * _lib/auth.js/resolveDeviceId).
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-09): versione iniziale.
 * - 0.2.0 (2026-09-09): aggiunta la guardia di quota giornaliera (vedi
 *   _lib/quota.js) come rete di sicurezza contro le soglie gratuite di
 *   Firestore/Vercel.
 * - 0.3.0 (2026-09-11): rimosso il "DEVICE_ID" hardcoded ("figlio") —
 *   ora supporta N bambini, il childId si risolve dal token.
 * - 0.4.0 (2026-09-11): le geofence non sono piu' lette dalla
 *   subcollection devices/{childId}/geofences ma dalla nuova
 *   collezione radice "geofences" (campo childIds: string[], una zona
 *   puo' valere per piu' bambini — vedi CONTEXT.md fase 2/4). Query a
 *   singolo campo (where childIds array-contains childId): il filtro
 *   "active" e' fatto qui in memoria invece che in Firestore per non
 *   richiedere un indice composito (array-contains + un'altra
 *   uguaglianza). Migrazione automatica: al primo utilizzo per un
 *   bambino, se non e' ancora stata fatta, copia le zone gia'
 *   esistenti nella vecchia subcollection dentro la nuova collezione
 *   radice (childIds: [childId]) e marca devices/{childId}
 *   .geofencesMigrated=true per non ripeterla — stesso pattern di
 *   auto-migrazione gia' usato in _lib/auth.js per il token legacy,
 *   nessun passaggio manuale richiesto.
 */
const { getFirestore } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { resolveDeviceId } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

async function ensureGeofencesMigrated(db, deviceRef, childId) {
  const legacySnap = await deviceRef.collection("geofences").get();
  const batch = db.batch();
  legacySnap.docs.forEach((d) => {
    batch.set(db.collection("geofences").doc(d.id), { ...d.data(), childIds: [childId] });
  });
  batch.set(deviceRef, { geofencesMigrated: true }, { merge: true });
  await batch.commit();
}

module.exports = async (req, res) => {
  if (req.method !== "GET") {
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

  const allowed = await checkAndConsumeQuota(db, childId);
  if (!allowed) {
    res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
    return;
  }

  const deviceRef = db.collection("devices").doc(childId);
  const deviceSnap = await deviceRef.get();
  if (!deviceSnap.data()?.geofencesMigrated) {
    await ensureGeofencesMigrated(db, deviceRef, childId);
  }

  const snap = await db.collection("geofences").where("childIds", "array-contains", childId).get();
  const geofences = snap.docs
    .map((d) => ({ id: d.id, ...d.data() }))
    .filter((g) => g.active !== false);

  res.status(200).json({ geofences });
};
