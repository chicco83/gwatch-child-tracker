/**
 * POST /api/request-location
 * Versione: 0.1.0
 *
 * Il genitore (phone-app) chiede al watch di inviare subito la
 * posizione attuale, senza aspettare il prossimo upload periodico
 * (ogni 15 minuti, vedi ingest-location.js) ne' dover usare il
 * pulsante fisico sul watch. Push FCM "data" (mai "notification": il
 * watch deve *agire*, non solo mostrare qualcosa — stesso schema di
 * send-message-to-child.js) che dice al watch di fare un fix GPS e
 * inviarlo: riusa LocationRequestWorker e trigger-event.js gia'
 * esistenti sul watch (stesso identico flusso del pulsante "Invia
 * posizione" li', solo innescato dal telefono invece che in locale).
 *
 * Auth: header "Authorization: Bearer <Firebase ID token genitore>".
 * Nessun body richiesto.
 */
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkParentAuth } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const DEVICE_ID = "figlio";

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

  const allowed = await checkAndConsumeQuota(db, DEVICE_ID);
  if (!allowed) {
    res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
    return;
  }

  const deviceSnap = await db.collection("devices").doc(DEVICE_ID).get();
  const watchToken = deviceSnap.data()?.fcmToken;
  if (!watchToken) {
    res.status(404).send("Watch non registrato (nessun token FCM salvato)");
    return;
  }

  await getMessaging().send({
    token: watchToken,
    data: { type: "location_request" },
    android: { priority: "high" },
  });

  res.status(200).json({ ok: true });
};
