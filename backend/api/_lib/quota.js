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
 * ogni chiamata a una delle 4 funzioni. Superata la soglia, l'endpoint
 * risponde 429 invece di eseguire l'operazione.
 */
const { FieldValue, Timestamp } = require("firebase-admin/firestore");

// Anche nello scenario peggiore (bug che chiama il backend ogni ~20
// secondi no-stop per 24h, molto oltre il sampling adattivo previsto)
// restiamo su una piccola frazione delle quote gratuite reali.
const MAX_BACKEND_CALLS_PER_DAY = 4000;
const QUOTA_DOC_RETENTION_DAYS = 7;

function todayKey() {
  return new Date().toISOString().slice(0, 10); // YYYY-MM-DD (UTC)
}

/**
 * Ritorna true se la chiamata e' concessa (e la conta), false se il
 * limite giornaliero e' gia' stato raggiunto.
 */
async function checkAndConsumeQuota(db, deviceId) {
  const quotaRef = db
    .collection("devices")
    .doc(deviceId)
    .collection("quota")
    .doc(todayKey());

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(quotaRef);
    const count = snap.exists ? snap.data().count ?? 0 : 0;

    if (count >= MAX_BACKEND_CALLS_PER_DAY) {
      return false;
    }

    const expiresAt = Timestamp.fromMillis(
      Date.now() + QUOTA_DOC_RETENTION_DAYS * 24 * 60 * 60 * 1000
    );
    tx.set(quotaRef, { count: FieldValue.increment(1), expiresAt }, { merge: true });
    return true;
  });
}

module.exports = { checkAndConsumeQuota, MAX_BACKEND_CALLS_PER_DAY };
