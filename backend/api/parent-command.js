/**
 * POST /api/parent-command
 * Versione: 0.1.0
 *
 * Endpoint unico per i comandi rapidi del genitore verso il watch:
 * messaggio di chat, richiesta posizione immediata, annulla SOS,
 * conferma lettura posizione. Prima erano 4 file separati
 * (send-message-to-child.js, request-location.js, cancel-sos.js,
 * ack-event.js): il deploy Vercel falliva silenziosamente da quando
 * ack-event.js aveva portato backend/api/ a 13 file — "No more than 12
 * Serverless Functions can be added to a Deployment on the Hobby
 * plan". NESSUNA modifica dei commit successivi (5af2c9a, f7da87a,
 * 7a1a84a) era quindi mai realmente andata online. Accorpati in questo
 * unico file per tornare sotto al limite (10 file totali) con un
 * margine per future aggiunte.
 *
 * Auth: header "Authorization: Bearer <Firebase ID token genitore>".
 * Body: { action, ... }
 *   - action = "message": { text }
 *   - action = "request_location": nessun campo extra
 *   - action = "cancel_sos": nessun campo extra
 *   - action = "ack_event": { eventId }
 */
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkParentAuth } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const DEVICE_ID = "figlio";
const MAX_TEXT_LENGTH = 500;
const MESSAGE_RETENTION_HOURS = 24;

async function handleMessage(db, deviceRef, body, res) {
  const { text } = body;
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

  const ts = Timestamp.now();
  const expiresAt = Timestamp.fromMillis(ts.toMillis() + MESSAGE_RETENTION_HOURS * 3_600_000);
  await deviceRef.collection("messages").add({ sender: "parent", text, timestamp: ts, expiresAt });

  const deviceSnap = await deviceRef.get();
  const watchToken = deviceSnap.data()?.fcmToken;
  if (watchToken) {
    // Solo "data": onMessageReceived() deve girare sempre sul watch,
    // anche in background (stesso motivo di send-message.js lato phone).
    await getMessaging().send({
      token: watchToken,
      data: { type: "chat", sender: "parent", text },
      android: { priority: "high" },
    });
  }
  res.status(200).json({ ok: true });
}

async function handleRequestLocation(db, deviceRef, res) {
  const allowed = await checkAndConsumeQuota(db, DEVICE_ID);
  if (!allowed) {
    res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
    return;
  }

  const deviceSnap = await deviceRef.get();
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
}

async function handleCancelSos(deviceRef, res) {
  await deviceRef.set({ sosActive: false, updatedAt: FieldValue.serverTimestamp() }, { merge: true });

  const deviceSnap = await deviceRef.get();
  const watchToken = deviceSnap.data()?.fcmToken;
  if (watchToken) {
    await getMessaging().send({
      token: watchToken,
      data: { type: "sos_cancel" },
      android: { priority: "high" },
    });
  }
  res.status(200).json({ ok: true });
}

async function handleAckEvent(deviceRef, body, res) {
  const { eventId } = body;
  if (typeof eventId !== "string" || eventId.trim().length === 0) {
    res.status(400).send("Bad Request: 'eventId' mancante");
    return;
  }

  const eventRef = deviceRef.collection("events").doc(eventId);
  const eventSnap = await eventRef.get();
  if (!eventSnap.exists) {
    res.status(404).send("Evento non trovato");
    return;
  }

  // Idempotente: se gia' acknowledged non manda una seconda push (vedi
  // "wasSosActive" in trigger-event.js, stesso pattern).
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
}

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

  const body = req.body || {};
  const deviceRef = db.collection("devices").doc(DEVICE_ID);

  switch (body.action) {
    case "message":
      await handleMessage(db, deviceRef, body, res);
      return;
    case "request_location":
      await handleRequestLocation(db, deviceRef, res);
      return;
    case "cancel_sos":
      await handleCancelSos(deviceRef, res);
      return;
    case "ack_event":
      await handleAckEvent(deviceRef, body, res);
      return;
    default:
      res.status(400).send("Bad Request: 'action' non valido");
  }
};
