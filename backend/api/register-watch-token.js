/**
 * POST /api/register-watch-token
 * Versione: 0.2.0
 *
 * Registra/aggiorna il token FCM del watch, usato da parent-command.js
 * per svegliarlo con la push quando il genitore scrive. Un solo token
 * per bambino (un solo watch a testa), a differenza di
 * parents/{uid}.fcmTokens che e' un array (un genitore puo' avere piu'
 * telefoni).
 *
 * Auth: header "X-Device-Token" (identifica il bambino, vedi
 * _lib/auth.js/resolveDeviceId).
 * Body: { token }
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-10): versione iniziale.
 * - 0.2.0 (2026-09-11): rimosso il "DEVICE_ID" hardcoded ("figlio") —
 *   ora supporta N bambini, il childId si risolve dal token.
 */
const { getFirestore } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { resolveDeviceId } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

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

  const { token } = req.body || {};
  if (typeof token !== "string" || token.trim().length === 0) {
    res.status(400).send("Bad Request: 'token' mancante o vuoto");
    return;
  }

  const allowed = await checkAndConsumeQuota(db, childId);
  if (!allowed) {
    res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
    return;
  }

  await db
    .collection("devices")
    .doc(childId)
    .set({ fcmToken: token }, { merge: true });

  res.status(200).json({ ok: true });
};
