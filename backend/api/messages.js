/**
 * GET /api/messages
 * Versione: 0.2.0
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
 * Auth: header "X-Device-Token" (identifica il bambino, vedi
 * _lib/auth.js/resolveDeviceId).
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-10): versione iniziale.
 * - 0.2.0 (2026-09-11): rimosso il "DEVICE_ID" hardcoded ("figlio") —
 *   ora supporta N bambini, il childId si risolve dal token. Risposta
 *   include ora anche senderId/senderName (denormalizzati al momento
 *   dell'invio, vedi send-message.js/parent-command.js) — necessari
 *   perche' con due genitori nella stessa conversazione "mittente
 *   parent" da solo non basta piu' a capire chi ha scritto cosa.
 */
const { getFirestore } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { resolveDeviceId } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const HISTORY_LIMIT = 30;

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
    .collection("messages")
    .orderBy("timestamp", "desc")
    .limit(HISTORY_LIMIT)
    .get();

  const messages = snap.docs
    .map((d) => ({
      sender: d.data().sender,
      senderId: d.data().senderId ?? null,
      senderName: d.data().senderName ?? null,
      text: d.data().text,
      timestamp: d.data().timestamp.toMillis(),
    }))
    .reverse(); // ordine cronologico per la UI

  res.status(200).json({ messages });
};
