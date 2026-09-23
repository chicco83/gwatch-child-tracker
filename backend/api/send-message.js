/**
 * POST /api/send-message
 * Versione: 0.6.0
 *
 * Messaggio di chat inviato dal watch verso il genitore. Scrive il
 * messaggio e invia subito la push FCM a tutti i genitori nella stessa
 * chiamata (stesso motivo di trigger-event.js: su Vercel non esiste un
 * trigger Firestore onDocumentCreated).
 *
 * Auth: header "X-Device-Token" (identifica il bambino, vedi
 * _lib/auth.js/resolveDeviceId).
 * Body: { text, timestamp? }
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-10): versione iniziale.
 * - 0.2.0 (2026-09-10): aggiunto "expiresAt" (24h dal messaggio) —
 *   lo storico chat viene ora svuotato ogni 24h dallo stesso cron
 *   giornaliero che gia' pulisce posizioni/quota scadute, vedi
 *   backend/api/cleanup.js e firestore.indexes.json.
 * - 0.3.0 (2026-09-10): bug segnalato — i messaggi ricevuti dal watch
 *   comparivano SOLO come notifica di sistema sulla phone-app, mai
 *   nella schermata Chat. Causa: la push era "mista" (notification +
 *   data); con l'app in background Android consegna la parte
 *   "notification" al tray di sistema e NON invoca onMessageReceived()
 *   sul client, quindi nessun codice app girava per popolare la chat
 *   (che dipendeva solo dal listener Firestore, mai toccato). Ora la
 *   push e' solo "data", stesso pattern gia' usato in
 *   send-message-to-child.js verso il watch: onMessageReceived() gira
 *   sempre, anche in background, e FcmService.kt (phone-app) costruisce
 *   la notifica a mano E aggiorna subito la chat (IncomingMessageStore).
 * - 0.4.0 (2026-09-11): rimosso il "DEVICE_ID" hardcoded ("figlio") —
 *   ora supporta N bambini, il childId si risolve dal token. Il
 *   messaggio ora porta anche senderId (childId) e senderName
 *   (devices/{childId}.childName, fallback "Bambino") — necessari
 *   perche' con piu' bambini "sender: child" da solo non basta piu' a
 *   dire di chi si tratta. childId incluso anche nel payload della
 *   push, cosi' la phone-app puo' aprire la conversazione giusta.
 * - 0.5.0 (2026-09-16): le push ai
 *   genitori partono sul topic FCM "parents" invece di leggere l'intera
 *   collezione parents e iterare gli array fcmTokens a ogni messaggio (stesso
 *   intervento gia fatto in trigger-event.js v0.10.0): -1 lettura Firestore per
 *   invio, token obsoleti smaltiti da FCM stesso. L'iscrizione al topic e
 *   lato phone-app (TrackerApplication.kt + FcmService.onNewToken).
 * - 0.6.0 (2026-09-23): Fase 2 di qwen_plan.md (individuato da
 *   qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — il topic globale
 *   "parents" mandava un messaggio di chat di un bambino a QUALSIASI
 *   telefono di QUALSIASI famiglia iscritta. Sostituito col topic
 *   per-bambino "child-<childId>" (vedi _lib/fcmTopics.js), a cui la
 *   phone-app si iscrive solo per i propri figli.
 */
const { getFirestore, Timestamp } = require("firebase-admin/firestore");
const { wrapHandler, errorResponse, successResponse, logError } = require("./_lib/errors.js");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { resolveDeviceId } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");
const { childTopic } = require("./_lib/fcmTopics.js");

const MAX_TEXT_LENGTH = 500;
const MESSAGE_RETENTION_HOURS = 24;

module.exports = wrapHandler(async (req, res) => {
  if (req.method !== "POST") {
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

  const { text, timestamp } = req.body || {};
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

  const deviceRef = db.collection("devices").doc(childId);
  const deviceSnap = await deviceRef.get();
  const senderName = deviceSnap.data()?.childName || "Bambino";

  const ts = timestamp ? Timestamp.fromMillis(timestamp) : Timestamp.now();
  const expiresAt = Timestamp.fromMillis(ts.toMillis() + MESSAGE_RETENTION_HOURS * 3_600_000);

  await deviceRef.collection("messages").add({
    sender: "child",
    senderId: childId,
    senderName,
    text,
    timestamp: ts,
    expiresAt,
  });

  // Invio sul topic per-bambino "child-<childId>" (v0.6.0, iscrizione
  // lato phone-app). Niente piu lettura della collezione parents a
  // ogni messaggio; gli array fcmTokens restano scritti per debug ma non sono
  // usati per l'invio. Solo "data" (vedi storico versioni v0.3.0 sopra): cosi'
  // onMessageReceived() gira sempre lato phone-app anche ad app in background.
  await getMessaging().send({
    topic: childTopic(childId),
    data: { type: "chat", sender: "child", senderName, text, childId },
    android: { priority: "high" },
  });

  res.status(200).json({ ok: true });
});
