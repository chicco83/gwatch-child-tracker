/**
 * POST /api/register-watch-token
 * Versione: 0.1.0
 *
 * Registra/aggiorna il token FCM del watch, usato da
 * send-message-to-child.js per svegliarlo con la push quando il
 * genitore scrive. Un solo token (un solo watch), a differenza di
 * parents/{uid}.fcmTokens che e' un array (un genitore puo' avere piu'
 * telefoni).
 *
 * Auth: header "X-Device-Token".
 * Body: { token }
 */
const { getFirestore } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkDeviceToken } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const DEVICE_ID = "figlio";

module.exports = async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }
  if (!checkDeviceToken(req)) {
    res.status(401).send("Unauthorized");
    return;
  }

  const { token } = req.body || {};
  if (typeof token !== "string" || token.trim().length === 0) {
    res.status(400).send("Bad Request: 'token' mancante o vuoto");
    return;
  }

  getAdminApp();
  const db = getFirestore();

  const allowed = await checkAndConsumeQuota(db, DEVICE_ID);
  if (!allowed) {
    res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
    return;
  }

  await db
    .collection("devices")
    .doc(DEVICE_ID)
    .set({ fcmToken: token }, { merge: true });

  res.status(200).json({ ok: true });
};
