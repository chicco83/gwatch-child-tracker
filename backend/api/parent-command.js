/**
 * POST /api/parent-command
 * Versione: 0.2.0
 *
 * Endpoint unico per i comandi rapidi del genitore verso il watch:
 * messaggio di chat, richiesta posizione immediata, annulla SOS,
 * conferma lettura posizione, nickname, aggiungi bambino. Prima erano
 * 4 file separati (send-message-to-child.js, request-location.js,
 * cancel-sos.js, ack-event.js): il deploy Vercel falliva silenziosamente
 * da quando ack-event.js aveva portato backend/api/ a 13 file — "No
 * more than 12 Serverless Functions can be added to a Deployment on
 * the Hobby plan". Accorpati in questo unico file per tornare sotto al
 * limite, con margine per le due nuove azioni di questa versione.
 *
 * Auth: header "Authorization: Bearer <Firebase ID token genitore>".
 * Body: { action, ... }
 *   - action = "create_child": { nickname } — nessun childId, ne crea uno
 *   - action = "set_nickname": { childId, nickname }
 *   - action = "message": { childId, text }
 *   - action = "request_location": { childId }
 *   - action = "cancel_sos": { childId }
 *   - action = "ack_event": { childId, eventId }
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-10): versione iniziale (accorpamento dei 4 file).
 * - 0.2.0 (2026-09-11): richiesta utente — supporto a N bambini.
 *   Rimosso il "DEVICE_ID" hardcoded ("figlio"): le 4 azioni esistenti
 *   ora richiedono un "childId" esplicito nel body (il genitore sta
 *   sempre agendo su un bambino preciso, scelto in UI — prima ce n'era
 *   uno solo possibile). Due nuove azioni:
 *   - "create_child": genera un childId (Firestore auto-id) + un token
 *     casuale per il nuovo watch, salva SOLO l'hash SHA-256 del token
 *     su Firestore (mai in chiaro — quel campo sarebbe leggibile da
 *     qualunque genitore tramite lo stesso listener che legge
 *     posizione/batteria) e risponde col token in chiaro UNA volta
 *     sola: la UI lo mostra con l'invito a copiarlo subito nel
 *     local.properties del nuovo build watch. Nessuna variabile
 *     d'ambiente da editare a mano per ogni bambino aggiunto.
 *   - "set_nickname": scrive devices/{childId}.childName — il doc
 *     devices/* ha "allow write: if false" lato client (tutte le
 *     scritture passano da qui), coerente con l'esistente invece di
 *     bucare le regole per un singolo campo.
 *   "message" ora scrive anche senderId (uid del genitore) e
 *   senderName (parents/{uid}.nickname, fallback "Genitore") sul
 *   messaggio — necessario perche' con due genitori nella stessa
 *   conversazione "sender: parent" da solo non basta piu' a dire chi
 *   ha scritto cosa.
 */
const crypto = require("crypto");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkParentAuth, hashToken } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const MAX_TEXT_LENGTH = 500;
const MAX_NICKNAME_LENGTH = 40;
const MESSAGE_RETENTION_HOURS = 24;

async function handleCreateChild(db, body, res) {
  const { nickname } = body;
  if (typeof nickname !== "string" || nickname.trim().length === 0) {
    res.status(400).send("Bad Request: 'nickname' mancante o vuoto");
    return;
  }
  if (nickname.length > MAX_NICKNAME_LENGTH) {
    res.status(400).send(`Bad Request: massimo ${MAX_NICKNAME_LENGTH} caratteri`);
    return;
  }

  const token = crypto.randomBytes(24).toString("hex");
  const childRef = db.collection("devices").doc(); // Firestore auto-id

  await childRef.set({
    childName: nickname.trim(),
    deviceTokenHash: hashToken(token),
    createdAt: FieldValue.serverTimestamp(),
  });

  // Il token in chiaro esce SOLO in questa risposta, una volta sola:
  // da Firestore e' salvato solo il suo hash (vedi _lib/auth.js).
  res.status(200).json({ ok: true, childId: childRef.id, deviceToken: token });
}

async function handleSetNickname(db, body, res) {
  const { childId, nickname } = body;
  if (typeof childId !== "string" || childId.trim().length === 0) {
    res.status(400).send("Bad Request: 'childId' mancante");
    return;
  }
  if (typeof nickname !== "string" || nickname.trim().length === 0) {
    res.status(400).send("Bad Request: 'nickname' mancante o vuoto");
    return;
  }
  if (nickname.length > MAX_NICKNAME_LENGTH) {
    res.status(400).send(`Bad Request: massimo ${MAX_NICKNAME_LENGTH} caratteri`);
    return;
  }

  await db.collection("devices").doc(childId).set({ childName: nickname.trim() }, { merge: true });
  res.status(200).json({ ok: true });
}

async function handleMessage(db, deviceRef, childId, parentUid, body, res) {
  const { text } = body;
  if (typeof text !== "string" || text.trim().length === 0) {
    res.status(400).send("Bad Request: 'text' mancante o vuoto");
    return;
  }
  if (text.length > MAX_TEXT_LENGTH) {
    res.status(400).send(`Bad Request: massimo ${MAX_TEXT_LENGTH} caratteri`);
    return;
  }

  const allowed = await checkAndConsumeQuota(db, childId);
  if (!allowed) {
    res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
    return;
  }

  const parentSnap = await db.collection("parents").doc(parentUid).get();
  const senderName = parentSnap.data()?.nickname || "Genitore";

  const ts = Timestamp.now();
  const expiresAt = Timestamp.fromMillis(ts.toMillis() + MESSAGE_RETENTION_HOURS * 3_600_000);
  await deviceRef.collection("messages").add({
    sender: "parent",
    senderId: parentUid,
    senderName,
    text,
    timestamp: ts,
    expiresAt,
  });

  const deviceSnap = await deviceRef.get();
  const watchToken = deviceSnap.data()?.fcmToken;
  if (watchToken) {
    // Solo "data": onMessageReceived() deve girare sempre sul watch,
    // anche in background (stesso motivo di send-message.js lato phone).
    await getMessaging().send({
      token: watchToken,
      data: { type: "chat", sender: "parent", senderName, text },
      android: { priority: "high" },
    });
  }
  res.status(200).json({ ok: true });
}

async function handleRequestLocation(db, deviceRef, childId, res) {
  const allowed = await checkAndConsumeQuota(db, childId);
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

  // Le uniche due azioni che non operano su un bambino gia' esistente.
  if (body.action === "create_child") {
    await handleCreateChild(db, body, res);
    return;
  }
  if (body.action === "set_nickname") {
    await handleSetNickname(db, body, res);
    return;
  }

  const { childId } = body;
  if (typeof childId !== "string" || childId.trim().length === 0) {
    res.status(400).send("Bad Request: 'childId' mancante");
    return;
  }
  const deviceRef = db.collection("devices").doc(childId);

  switch (body.action) {
    case "message":
      await handleMessage(db, deviceRef, childId, parentUid, body, res);
      return;
    case "request_location":
      await handleRequestLocation(db, deviceRef, childId, res);
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
