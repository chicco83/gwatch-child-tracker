/**
 * GET /api/messages
 * Versione: 0.1.0
 *
 * Storico recente della chat, per il watch: a differenza della
 * phone-app (che legge Firestore direttamente con l'SDK client, vedi
 * DeviceRepository.observeMessages), il watch parla solo con il
 * backend via REST (vedi CONTEXT.md — niente SDK Firebase completo sul
 * watch, solo firebase-messaging per la push). Usato all'avvio/dopo
 * una disconnessione per recuperare eventuali messaggi arrivati mentre
 * l'app era chiusa (la sola push puo' non bastare: puo' non arrivare,
 * o l'app puo' non essere stata in esecuzione per gestirla).
 *
 * Auth: header "X-Device-Token".
 */
const { getFirestore } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkDeviceToken } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const DEVICE_ID = "figlio";
const HISTORY_LIMIT = 30;

module.exports = async (req, res) => {
  if (req.method !== "GET") {
    res.status(405).send("Method Not Allowed");
    return;
  }
  if (!checkDeviceToken(req)) {
    res.status(401).send("Unauthorized");
    return;
  }

  getAdminApp();
  const db = getFirestore();

  const allowed = await checkAndConsumeQuota(db, DEVICE_ID);
  if (!allowed) {
    res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
    return;
  }

  const snap = await db
    .collection("devices")
    .doc(DEVICE_ID)
    .collection("messages")
    .orderBy("timestamp", "desc")
    .limit(HISTORY_LIMIT)
    .get();

  const messages = snap.docs
    .map((d) => ({
      sender: d.data().sender,
      text: d.data().text,
      timestamp: d.data().timestamp.toMillis(),
    }))
    .reverse(); // ordine cronologico per la UI

  res.status(200).json({ messages });
};
