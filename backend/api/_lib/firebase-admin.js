/**
 * Inizializzazione condivisa dell'Admin SDK Firebase per le funzioni
 * Vercel. La service account arriva da una variabile d'ambiente (mai
 * committata, vedi ../../.env.example), codificata in base64 per
 * evitare problemi con i newline della chiave privata nell'ambiente
 * Vercel.
 */
const { initializeApp, getApps, cert } = require("firebase-admin/app");

function getAdminApp() {
  if (getApps().length > 0) return getApps()[0];

  const b64 = process.env.FIREBASE_SERVICE_ACCOUNT_B64;
  if (!b64) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_B64 non impostata");
  }
  const serviceAccount = JSON.parse(Buffer.from(b64, "base64").toString("utf8"));

  return initializeApp({ credential: cert(serviceAccount) });
}

module.exports = { getAdminApp };
