/**
 * GET /api/cleanup
 * Versione: 0.6.0
 *
 * Pulizia programmata dello storico scaduto. Sostituisce la TTL
 * policy nativa di Firestore: quella richiede il piano Blaze anche se
 * l'uso reale resterebbe gratuito (vedi CONTEXT.md).
 *
 * Invocato una volta al giorno da un workflow GitHub Actions
 * (.github/workflows/cleanup-cron.yml), non da Vercel Cron: il piano
 * Hobby ha bloccato il deploy con "crons" in vercel.json (v0.10.0),
 * quindi la programmazione e' su GitHub Actions, gratuito e senza
 * questo vincolo. Autenticato con l'header "Authorization: Bearer
 * <CRON_SECRET>", stesso valore impostato su Vercel e come secret
 * GitHub del repository.
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-09): invocazione via Vercel Cron.
 * - 0.2.0 (2026-09-09): spostata su GitHub Actions (Vercel Cron
 *   bloccava il deploy su piano Hobby).
 * - 0.3.0 (2026-09-10): aggiunta la pulizia di "messages" (storico
 *   chat, retention 24h invece delle 12 mesi di locations — vedi
 *   send-message.js/send-message-to-child.js, che scrivono
 *   "expiresAt" allo stesso modo di ingest-location.js). Stesso
 *   meccanismo, nessun cron/endpoint separato necessario.
 * - 0.4.0 (2026-09-16): due ritocchi
 *   di sicurezza/manutenzione: (1) confronto costant-time del CRON_SECRET (vedi
 *   _lib/auth.js/timingSafeEquals), prima un semplice !==; (2) torna la pulizia
 *   del gruppo "events" (retention 12 mesi, scrive expiresAt in trigger-event.js):
 *   era rimasta indietro dopo il riordino multi-bambino.
 * - 0.5.0 (2026-09-19): bug segnalato dall'utente — il workflow GitHub
 *   Actions "Pulizia storico backend" falliva ogni notte con HTTP 500
 *   ("Internal server error"). Causa: la v0.4.0 sopra ha aggiunto
 *   purgeExpired(db, "events") ma non il corrispondente indice
 *   Firestore per query collectionGroup su "events.expiresAt" —
 *   ../firestore.indexes.json aveva l'override per locations/quota/
 *   messages ma non per "events", introdotto (query aggiunta senza
 *   l'indice) senza che nessuno se ne accorgesse finche' il cron non
 *   ha iniziato a fallire. Firestore rifiuta una query collectionGroup
 *   con filtro di range su un campo senza un indice esplicito a quello
 *   scope, Promise.all() la propaga, wrapHandler la trasforma nel 500
 *   generico (nessun dettaglio nella risposta, per non esporre stack
 *   trace al chiamante). Aggiunto l'override mancante in
 *   firestore.indexes.json — va comunque ripubblicato su Firestore
 *   (Console o "firebase deploy --only firestore:indexes"), il file
 *   nel repo da solo non basta, stessa classe di problema gia' vista
 *   con le regole di sicurezza (vedi CONTEXT.md).
 * - 0.6.0 (2026-09-23): Fase 4 di qwen_plan.md (individuato da
 *   qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — vercel.json
 *   applicava maxDuration:30 a TUTTE le funzioni, questa inclusa: le
 *   quattro purgeExpired() sopra girano in parallelo (Promise.all) ma
 *   ciascuna puo' fare fino a MAX_BATCHES_PER_RUN (10) cicli di
 *   query+commit da 500 documenti — con uno storico grande (locations
 *   e' la collezione piu' voluminosa) puo' avvicinarsi o superare i
 *   30s, la funzione muore a meta', il cron GitHub fallisce con 500 e
 *   la pulizia resta parziale (nessun danno ai dati, solo pulizia
 *   posticipata al giro successivo, ma va comunque evitato). Aggiunta
 *   una voce specifica "api/cleanup.js" in vercel.json con
 *   maxDuration:60 — il massimo consentito sul piano Vercel Hobby (le
 *   altre funzioni, tutte rapide, restano a 30 tramite "api/*.js").
 */
const { getFirestore, Timestamp } = require("firebase-admin/firestore");
const { wrapHandler, errorResponse, successResponse, logError } = require("./_lib/errors.js");
const { getAdminApp } = require("./_lib/firebase-admin");
// Confronto costant-time del CRON_SECRET.
const { timingSafeEquals } = require("./_lib/auth");

const BATCH_SIZE = 500;
const MAX_BATCHES_PER_RUN = 10; // tetto di sicurezza: max 5.000 delete/esecuzione

async function purgeExpired(db, collectionGroupName) {
  let totalDeleted = 0;
  for (let i = 0; i < MAX_BATCHES_PER_RUN; i++) {
    const snap = await db
      .collectionGroup(collectionGroupName)
      .where("expiresAt", "<=", Timestamp.now())
      .limit(BATCH_SIZE)
      .get();

    if (snap.empty) break;

    const batch = db.batch();
    snap.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
    totalDeleted += snap.size;

    if (snap.size < BATCH_SIZE) break;
  }
  return totalDeleted;
}

module.exports = wrapHandler(async (req, res) => {
  // timingSafeEquals al posto di !==.
  const authHeader = req.headers["authorization"] || "";
  if (!process.env.CRON_SECRET || !timingSafeEquals(authHeader, `Bearer ${process.env.CRON_SECRET}`)) {
    res.status(401).send("Unauthorized");
    return;
  }

  getAdminApp();
  const db = getFirestore();

  // Include "events" (vedi trigger-event.js).
  const [locationsDeleted, quotaDeleted, messagesDeleted, eventsDeleted] = await Promise.all([
    purgeExpired(db, "locations"),
    purgeExpired(db, "quota"),
    purgeExpired(db, "messages"),
    purgeExpired(db, "events"),
  ]);

  res.status(200).json({ ok: true, locationsDeleted, quotaDeleted, messagesDeleted, eventsDeleted });
});
