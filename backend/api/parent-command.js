/**
 * POST /api/parent-command
 * Versione: 0.5.0
 *
 * Endpoint unico per i comandi rapidi del genitore verso il watch:
 * messaggio di chat, richiesta posizione immediata, annulla SOS,
 * conferma lettura posizione, nickname, aggiungi bambino, invito di un
 * secondo genitore. Prima erano 4 file separati
 * (send-message-to-child.js, request-location.js, cancel-sos.js,
 * ack-event.js): il deploy Vercel falliva silenziosamente da quando
 * ack-event.js aveva portato backend/api/ a 13 file — "No more than 12
 * Serverless Functions can be added to a Deployment on the Hobby
 * plan". Accorpati in questo unico file per tornare sotto al limite,
 * con margine per le nuove azioni successive.
 *
 * Auth: header "Authorization: Bearer <Firebase ID token genitore>".
 * Body: { action, ... }
 *   - action = "create_child": { nickname } — nessun childId, ne crea uno
 *   - action = "create_family_invite": {} — genera un codice per un secondo genitore
 *   - action = "accept_family_invite": { inviteCode }
 *   - action = "set_nickname": { childId, nickname }
 *   - action = "message": { childId, text }
 *   - action = "request_location": { childId }
 *   - action = "cancel_sos": { childId }
 *   - action = "ack_event": { childId, eventId }
 *   - action = "set_tracking_mode": { childId, highAccuracy: boolean }
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
 * - 0.3.0 (2026-09-18): bug segnalato — "invio fallito" sul telefono
 *   anche quando il messaggio arrivava comunque al watch (visibile in
 *   Firestore). Causa: getMessaging().send() non era mai avvolto in un
 *   try/catch in nessuno dei 4 handler — un token FCM del watch
 *   diventato non valido (reinstallazione app, token ruotato lato
 *   Google) mandava un'eccezione non gestita, Vercel rispondeva 500
 *   alla phone-app anche se la scrittura su Firestore (il messaggio, lo
 *   stato SOS, l'ack) era gia' andata a buon fine. Aggiunto
 *   sendPushSafe(): la push resta "best effort" (loggata se fallisce,
 *   mai un errore fatale per la richiesta) e se l'errore e'
 *   "registration-token-not-registered" il token viene ripulito dal
 *   documento device, cosi' i tentativi successivi non ripetono lo
 *   stesso fallimento silenzioso finche' il watch non si registra di
 *   nuovo (onNewToken/FcmService lato watch).
 * - 0.4.0 (2026-09-22): individuato da qwen3.8-27B-UD-IQ4_XS,
 *   implementato da Sonnet 5 — isolamento famiglie. checkParentAuth
 *   verificava solo che parents/{uid} esistesse: QUALSIASI genitore
 *   autenticato poteva agire su QUALSIASI childId, "cancel_sos"
 *   incluso (silenziare l'SOS attivo di un bambino di un'altra
 *   famiglia). Aggiunto verifyChildOwnership(): tutte le azioni con un
 *   childId esplicito (message/request_location/cancel_sos/ack_event/
 *   set_nickname, unificate sotto lo stesso controllo, prima
 *   set_nickname era un caso a parte) ora richiedono che
 *   parents/{uid}.familyId combaci con devices/{childId}.familyId,
 *   altrimenti 403. create_child assegna il familyId al nuovo bambino:
 *   se il genitore non ne ha ancora uno (primo bambino mai creato),
 *   ensureFamilyId() ne genera uno nuovo al volo (self-heal, stesso
 *   stile della migrazione legacy del token in _lib/auth.js) — cosi'
 *   non serve un'azione "crea famiglia" separata. Nuove azioni
 *   "create_family_invite"/"accept_family_invite" per collegare un
 *   secondo genitore alla stessa famiglia (codice a singolo uso, TTL
 *   24h, collezione familyInvites — backend-only, vedi
 *   firestore.rules v0.7.0): l'accept rifiuta se il genitore ha gia'
 *   una famiglia con bambini propri, per non perdere per sbaglio
 *   l'accesso a quelli entrando in un'altra famiglia. Richiede la
 *   migrazione one-time dei dati pre-esistenti, vedi
 *   backend/scripts/migrate-family-ids.js.
 * - 0.5.0 (2026-09-24): azione "set_tracking_mode" (richiesta utente):
 *   il tracking del watch da fermo torna a priorita' bilanciata, con un
 *   interruttore sulla phone-app per forzare l'alta precisione. Scrive
 *   devices/{childId}.trackingHighAccuracy e manda la push data-only
 *   "tracking_mode" al watch; stesso controllo di appartenenza alla
 *   famiglia delle altre azioni con childId.
 */
const crypto = require("crypto");
const { wrapHandler, errorResponse, successResponse, logError } = require("./_lib/errors.js");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkParentAuth, hashToken, getFamilyId } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const MAX_TEXT_LENGTH = 500;
const MAX_NICKNAME_LENGTH = 40;
const MESSAGE_RETENTION_HOURS = 24;
const INVITE_TTL_HOURS = 24;

/**
 * Invia una push al watch senza mai far fallire la richiesta del
 * genitore per un problema lato FCM (vedi Storico versioni 0.3.0): un
 * token non valido cancella se stesso dal device invece di ripetere lo
 * stesso errore ad ogni chiamata successiva.
 */
async function sendPushSafe(deviceRef, watchToken, payload) {
  try {
    await getMessaging().send({ token: watchToken, android: { priority: "high" }, ...payload });
  } catch (e) {
    console.error(`sendPushSafe: invio fallito (childId=${deviceRef.id})`, e);
    if (e.code === "messaging/registration-token-not-registered") {
      await deviceRef.set({ fcmToken: FieldValue.delete() }, { merge: true });
    }
  }
}

/**
 * Ritorna il familyId del genitore, creandone uno nuovo se non ne ha
 * ancora uno (primo bambino mai creato da questa identita' — vedi
 * Storico versioni 0.4.0). Non sovrascrive mai un familyId gia'
 * presente.
 */
async function ensureFamilyId(db, parentUid) {
  const existing = await getFamilyId(db, parentUid);
  if (existing) return existing;
  const familyId = crypto.randomUUID();
  await db.collection("parents").doc(parentUid).set({ familyId }, { merge: true });
  return familyId;
}

/** true se il bambino appartiene alla stessa famiglia del genitore chiamante. */
async function verifyChildOwnership(db, parentUid, childId) {
  const [myFamilyId, deviceSnap] = await Promise.all([
    getFamilyId(db, parentUid),
    db.collection("devices").doc(childId).get(),
  ]);
  const childFamilyId = deviceSnap.exists ? deviceSnap.data().familyId : null;
  return Boolean(myFamilyId) && myFamilyId === childFamilyId;
}

async function handleCreateChild(db, parentUid, body, res) {
  const { nickname } = body;
  if (typeof nickname !== "string" || nickname.trim().length === 0) {
    res.status(400).send("Bad Request: 'nickname' mancante o vuoto");
    return;
  }
  if (nickname.length > MAX_NICKNAME_LENGTH) {
    res.status(400).send(`Bad Request: massimo ${MAX_NICKNAME_LENGTH} caratteri`);
    return;
  }

  const familyId = await ensureFamilyId(db, parentUid);
  const token = crypto.randomBytes(24).toString("hex");
  const childRef = db.collection("devices").doc(); // Firestore auto-id

  await childRef.set({
    childName: nickname.trim(),
    deviceTokenHash: hashToken(token),
    familyId,
    createdAt: FieldValue.serverTimestamp(),
  });

  // Il token in chiaro esce SOLO in questa risposta, una volta sola:
  // da Firestore e' salvato solo il suo hash (vedi _lib/auth.js).
  res.status(200).json({ ok: true, childId: childRef.id, deviceToken: token });
}

/**
 * Genera un codice di invito a singolo uso (TTL 24h) per far entrare
 * un secondo genitore nella stessa famiglia — l'unico modo in cui
 * parents/{uid}.familyId puo' cambiare per un genitore che ne ha gia'
 * uno diverso e' rifiutato in handleAcceptFamilyInvite sotto.
 */
async function handleCreateFamilyInvite(db, parentUid, res) {
  const familyId = await ensureFamilyId(db, parentUid);
  const code = crypto.randomBytes(16).toString("hex");
  const expiresAt = Timestamp.fromMillis(Date.now() + INVITE_TTL_HOURS * 3_600_000);

  await db.collection("familyInvites").doc(code).set({ familyId, createdBy: parentUid, expiresAt });
  res.status(200).json({ ok: true, inviteCode: code, expiresAt: expiresAt.toDate().toISOString() });
}

async function handleAcceptFamilyInvite(db, parentUid, body, res) {
  const { inviteCode } = body;
  if (typeof inviteCode !== "string" || inviteCode.trim().length === 0) {
    res.status(400).send("Bad Request: 'inviteCode' mancante");
    return;
  }

  const inviteRef = db.collection("familyInvites").doc(inviteCode.trim());
  const inviteSnap = await inviteRef.get();
  if (!inviteSnap.exists) {
    res.status(404).send("Codice invito non valido o gia' usato");
    return;
  }
  const { familyId, expiresAt } = inviteSnap.data();
  if (expiresAt.toMillis() < Date.now()) {
    await inviteRef.delete();
    res.status(410).send("Codice invito scaduto");
    return;
  }

  // Rifiuta se il genitore ha gia' una propria famiglia con bambini:
  // accettare un altro invito sovrascriverebbe familyId e li renderebbe
  // irraggiungibili (nessuna migrazione automatica dei suoi bambini
  // esistenti verso la nuova famiglia).
  const currentFamilyId = await getFamilyId(db, parentUid);
  if (currentFamilyId) {
    const ownChildren = await db.collection("devices").where("familyId", "==", currentFamilyId).limit(1).get();
    if (!ownChildren.empty) {
      res.status(409).send("Hai gia' una famiglia con bambini registrati: non puoi unirti a un'altra famiglia");
      return;
    }
  }

  await db.collection("parents").doc(parentUid).set({ familyId }, { merge: true });
  await inviteRef.delete(); // uso singolo
  res.status(200).json({ ok: true });
}

async function handleSetNickname(deviceRef, body, res) {
  const { nickname } = body;
  if (typeof nickname !== "string" || nickname.trim().length === 0) {
    res.status(400).send("Bad Request: 'nickname' mancante o vuoto");
    return;
  }
  if (nickname.length > MAX_NICKNAME_LENGTH) {
    res.status(400).send(`Bad Request: massimo ${MAX_NICKNAME_LENGTH} caratteri`);
    return;
  }

  await deviceRef.set({ childName: nickname.trim() }, { merge: true });
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
    await sendPushSafe(deviceRef, watchToken, {
      data: { type: "chat", sender: "parent", senderName, text },
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

  await sendPushSafe(deviceRef, watchToken, { data: { type: "location_request" } });
  res.status(200).json({ ok: true });
}

// v0.5.0 (2026-09-24): alta precisione del tracking anche da fermo,
// scelta dal genitore (vedi Storico versioni). Salvata sul device (la
// rilegge anche device-config.js) e mandata subito al watch con una push.
async function handleSetTrackingMode(deviceRef, body, res) {
  const { highAccuracy } = body;
  if (typeof highAccuracy !== "boolean") {
    res.status(400).send("Bad Request: 'highAccuracy' deve essere true/false");
    return;
  }
  await deviceRef.set({ trackingHighAccuracy: highAccuracy }, { merge: true });
  const watchToken = (await deviceRef.get()).data()?.fcmToken;
  if (watchToken) {
    await sendPushSafe(deviceRef, watchToken, {
      data: { type: "tracking_mode", highAccuracy: String(highAccuracy) },
    });
  }
  res.status(200).json({ ok: true });
}

async function handleCancelSos(deviceRef, res) {
  await deviceRef.set({ sosActive: false, updatedAt: FieldValue.serverTimestamp() }, { merge: true });

  const deviceSnap = await deviceRef.get();
  const watchToken = deviceSnap.data()?.fcmToken;
  if (watchToken) {
    await sendPushSafe(deviceRef, watchToken, { data: { type: "sos_cancel" } });
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
      await sendPushSafe(deviceRef, watchToken, { data: { type: "location_seen" } });
    }
  }
  res.status(200).json({ ok: true });
}

module.exports = wrapHandler(async (req, res) => {
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

  // Le uniche tre azioni che non operano su un bambino gia' esistente.
  if (body.action === "create_child") {
    await handleCreateChild(db, parentUid, body, res);
    return;
  }
  if (body.action === "create_family_invite") {
    await handleCreateFamilyInvite(db, parentUid, res);
    return;
  }
  if (body.action === "accept_family_invite") {
    await handleAcceptFamilyInvite(db, parentUid, body, res);
    return;
  }

  const { childId } = body;
  if (typeof childId !== "string" || childId.trim().length === 0) {
    res.status(400).send("Bad Request: 'childId' mancante");
    return;
  }

  // Individuato da qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5
  // (vedi Storico versioni 0.4.0): senza questo controllo, un genitore
  // autenticato poteva agire su un childId di qualunque famiglia.
  const owns = await verifyChildOwnership(db, parentUid, childId);
  if (!owns) {
    res.status(403).send("Forbidden: bambino non associato alla tua famiglia");
    return;
  }

  const deviceRef = db.collection("devices").doc(childId);

  switch (body.action) {
    case "set_nickname":
      await handleSetNickname(deviceRef, body, res);
      return;
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
    case "set_tracking_mode":
      await handleSetTrackingMode(deviceRef, body, res);
      return;
    default:
      res.status(400).send("Bad Request: 'action' non valido");
  }
});
