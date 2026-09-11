/**
 * GET /api/device-config
 * Versione: 0.3.0
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
 */
const { getFirestore } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { resolveDeviceId } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

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

  const snap = await db
    .collection("devices")
    .doc(childId)
    .collection("geofences")
    .where("active", "==", true)
    .get();

  const geofences = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
  res.status(200).json({ geofences });
};
