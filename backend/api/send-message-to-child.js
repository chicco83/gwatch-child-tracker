/**
 * POST /api/send-message-to-child
 * Versione: 0.1.0
 *
 * Messaggio di chat inviato dal genitore (phone-app) verso il watch.
 * A differenza delle geofence, il messaggio NON viene scritto
 * direttamente su Firestore dal client: serve un passaggio dal backend
 * perche' e' l'unico posto in cui si puo' inviare anche la push FCM
 * (dati, non solo notifica: sveglia l'app sul watch anche se non e' in
 * primo piano) nella stessa chiamata.
 *
 * Auth: header "Authorization: Bearer <Firebase ID token genitore>".
 * Body: { text }
 */
const { getFirestore, Timestamp } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkParentAuth } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const DEVICE_ID = "figlio";
const MAX_TEXT_LENGTH = 500;

module.exports = async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }

  getAdminApp();
  const db = getFirestore();

  const parentUid = await checkParentAuth(req, db);
  if (!parentUid) {
    res.status(401).send("Unauthorized");
    return;
  }

  const { text } = req.body || {};
  if (typeof text !== "string" || text.trim().length === 0) {
    res.status(400).send("Bad Request: 'text' mancante o vuoto");
    return;
  }
  if (text.length > MAX_TEXT_LENGTH) {
    res.status(400).send(`Bad Request: massimo ${MAX_TEXT_LENGTH} caratteri`);
    return;
  }

  const allowed = await checkAndConsumeQuota(db, DEVICE_ID);
  if (!allowed) {
    res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
    return;
  }

  const deviceRef = db.collection("devices").doc(DEVICE_ID);
  const ts = Timestamp.now();

  await deviceRef.collection("messages").add({ sender: "parent", text, timestamp: ts });

  const deviceSnap = await deviceRef.get();
  const watchToken = deviceSnap.data()?.fcmToken;

  if (watchToken) {
    // Solo "data": niente "notification", cosi' il messaggio arriva a
    // onMessageReceived() anche ad app in background invece di essere
    // gestito (e potenzialmente scartato) dal tray di sistema, che sul
    // watch non ha una UI di chat da aprire.
    await getMessaging().send({
      token: watchToken,
      data: { type: "chat", sender: "parent", text },
      android: { priority: "high" },
    });
  }

  res.status(200).json({ ok: true });
};
