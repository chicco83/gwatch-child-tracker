/**
 * Guardia di sicurezza sul traffico: impedisce che il backend, anche
 * in caso di bug o malfunzionamento del watch, possa mai avvicinarsi
 * alle soglie gratuite di Firestore Spark (20.000 scritture/giorno,
 * 50.000 letture/giorno, 1GB storage) o di Vercel Hobby (100 GB-Hours
 * di esecuzione funzioni/mese). La soglia qui sotto e' volutamente
 * molto piu' bassa di quelle reali: e' un margine di sicurezza fisso
 * nel codice, non un tentativo di sfruttarle al massimo.
 *
 * Un contatore per dispositivo per giorno (UTC), in
 * devices/{deviceId}/quota/{YYYY-MM-DD}, incrementato atomicamente ad
 * ogni chiamata a uno degli endpoint. Superata la soglia, l'endpoint
 * risponde 429 invece di eseguire l'operazione.
 *
 * Storico versioni:
 * - 0.2.0 (2026-09-23): Fase 3 di qwen_plan.md (individuato da
 *   qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — il contatore
 *   contava sempre "1" per chiamata, ma ingest-location.js scrive fino
 *   a MAX_POINTS_PER_REQUEST (100) documenti "locations" in una sola
 *   chiamata: la guardia sottostimava di molto le scritture Firestore
 *   reali (quella che le quote gratuite Spark limitano davvero,
 *   20.000/giorno per l'intero progetto, non per dispositivo), vanificando
 *   in parte lo scopo della guardia con piu' bambini attivi
 *   contemporaneamente. Aggiunto un parametro "weight" opzionale
 *   (default 1, invariato per tutti gli endpoint che scrivono un numero
 *   costante di documenti): ingest-location.js lo valorizza col numero
 *   di punti del batch.
 */
const { FieldValue, Timestamp } = require("firebase-admin/firestore");
const config = require("./config.js");

// Anche nello scenario peggiore (bug che chiama il backend ogni ~20
// secondi no-stop per 24h, molto oltre il sampling adattivo previsto)
// restiamo su una piccola frazione delle quote gratuite reali.
const MAX_BACKEND_CALLS_PER_DAY = config.MAX_BACKEND_CALLS_PER_DAY;
// Bug (2026-09-18): qui c'era
// "config.RETENTION_HOURS / 24" — RETENTION_HOURS e' la retention
// dello storico posizioni/eventi (12 mesi, vedi ingest-location.js),
// un concetto scorrelato da "per quanto tenere il contatore di quota
// giornaliero": il riuso portava la retention della quota da 7 giorni
// a 365 per un semplice omonimo. Tenuta una costante propria, come
// prima di questo refactor.
const QUOTA_DOC_RETENTION_DAYS = 7;

function todayKey() {
  return new Date().toISOString().slice(0, 10); // YYYY-MM-DD (UTC)
}

/**
 * Ritorna true se la chiamata e' concessa (e ne conta il peso), false
 * se supererebbe il limite giornaliero.
 * @param {number} [weight] scritture Firestore che questa chiamata sta
 *   per fare (vedi Storico versioni sopra) — default 1, invariato per
 *   gli endpoint che scrivono un numero costante di documenti.
 */
async function checkAndConsumeQuota(db, deviceId, weight = 1) {
  const quotaRef = db
    .collection("devices")
    .doc(deviceId)
    .collection("quota")
    .doc(todayKey());

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(quotaRef);
    const count = snap.exists ? snap.data().count ?? 0 : 0;

    if (count + weight > MAX_BACKEND_CALLS_PER_DAY) {
      return false;
    }

    const expiresAt = Timestamp.fromMillis(
      Date.now() + QUOTA_DOC_RETENTION_DAYS * 24 * 60 * 60 * 1000
    );
    tx.set(quotaRef, { count: FieldValue.increment(weight), expiresAt }, { merge: true });
    return true;
  });
}

module.exports = { checkAndConsumeQuota, MAX_BACKEND_CALLS_PER_DAY };
