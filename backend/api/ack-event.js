/**
 * POST /api/ack-event
 * Versione: 0.1.0
 *
 * Marca un evento come "visto" dal genitore e avvisa il watch — usato
 * per la posizione inviata volontariamente dal bambino (pulsante
 * "Invia posizione" o SOS): il bambino sa cosi' che il genitore ha
 * davvero guardato dove si trova, senza dover chiedere "hai visto?"
 * in chat. Chiamato dalla phone-app quando la schermata mappa mostra
 * un evento di questo tipo non ancora marcato (vedi MapScreen.kt).
 *
 * Idempotente: se l'evento e' gia' acknowledged=true non manda una
 * seconda push (stesso motivo/pattern di "wasSosActive" in
 * trigger-event.js — evita di spammare il bambino di notifiche se la
 * phone-app richiama questo endpoint piu' volte per lo stesso evento,
 * es. per ricomposizioni successive della UI).
 *
 * Auth: header "Authorization: Bearer <Firebase ID token genitore>".
 * Body: { eventId }
 */
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkParentAuth } = require("./_lib/auth");

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

  const { eventId } = req.body || {};
  if (typeof eventId !== "string" || eventId.trim().length === 0) {
    res.status(400).send("Bad Request: 'eventId' mancante");
    return;
  }

  const deviceRef = db.collection("devices").doc(DEVICE_ID);
  const eventRef = deviceRef.collection("events").doc(eventId);
  const eventSnap = await eventRef.get();
  if (!eventSnap.exists) {
    res.status(404).send("Evento non trovato");
    return;
  }

  const alreadyAcknowledged = eventSnap.data()?.acknowledged === true;
  await eventRef.set({ acknowledged: true, acknowledgedAt: FieldValue.serverTimestamp() }, { merge: true });

  if (!alreadyAcknowledged) {
    const deviceSnap = await deviceRef.get();
    const watchToken = deviceSnap.data()?.fcmToken;
    if (watchToken) {
      await getMessaging().send({
        token: watchToken,
        data: { type: "location_seen" },
        android: { priority: "high" },
      });
    }
  }

  res.status(200).json({ ok: true });
};
