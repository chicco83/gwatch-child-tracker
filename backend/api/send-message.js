/**
 * POST /api/send-message
 * Versione: 0.2.0
 *
 * Messaggio di chat inviato dal watch verso il genitore. Scrive il
 * messaggio e invia subito la push FCM a tutti i genitori nella stessa
 * chiamata (stesso motivo di trigger-event.js: su Vercel non esiste un
 * trigger Firestore onDocumentCreated).
 *
 * Auth: header "X-Device-Token".
 * Body: { text, timestamp? }
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-10): versione iniziale.
 * - 0.2.0 (2026-09-10): aggiunto "expiresAt" (24h dal messaggio) —
 *   lo storico chat viene ora svuotato ogni 24h dallo stesso cron
 *   giornaliero che gia' pulisce posizioni/quota scadute, vedi
 *   backend/api/cleanup.js e firestore.indexes.json.
 */
const { getFirestore, Timestamp } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkDeviceToken } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const DEVICE_ID = "figlio";
const MAX_TEXT_LENGTH = 500;
const MESSAGE_RETENTION_HOURS = 24;

module.exports = async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }
  if (!checkDeviceToken(req)) {
    res.status(401).send("Unauthorized");
    return;
  }

  const { text, timestamp } = req.body || {};
  if (typeof text !== "string" || text.trim().length === 0) {
    res.status(400).send("Bad Request: 'text' mancante o vuoto");
    return;
  }
  if (text.length > MAX_TEXT_LENGTH) {
    res.status(400).send(`Bad Request: massimo ${MAX_TEXT_LENGTH} caratteri`);
    return;
  }

  getAdminApp();
  const db = getFirestore();

  const allowed = await checkAndConsumeQuota(db, DEVICE_ID);
  if (!allowed) {
    res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
    return;
  }

  const ts = timestamp ? Timestamp.fromMillis(timestamp) : Timestamp.now();
  const expiresAt = Timestamp.fromMillis(ts.toMillis() + MESSAGE_RETENTION_HOURS * 3_600_000);

  await db
    .collection("devices")
    .doc(DEVICE_ID)
    .collection("messages")
    .add({ sender: "child", text, timestamp: ts, expiresAt });

  const parentSnap = await db.collection("parents").get();
  const tokens = [];
  parentSnap.forEach((p) => {
    const t = p.data().fcmTokens;
    if (Array.isArray(t)) tokens.push(...t);
  });

  if (tokens.length > 0) {
    await getMessaging().sendEachForMulticast({
      tokens,
      notification: { title: "Messaggio dal watch", body: text },
      data: { type: "chat", sender: "child", text },
      android: { priority: "high" },
    });
  }

  res.status(200).json({ ok: true });
};
