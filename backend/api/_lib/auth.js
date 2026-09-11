/**
 * Verifica delle identita' che possono chiamare gli endpoint del
 * backend. Tre schemi diversi:
 * - watch <-> backend: un token per bambino, mai in chiaro su
 *   Firestore (solo il suo hash SHA-256, vedi resolveDeviceId sotto) —
 *   generato dal backend stesso quando un genitore aggiunge un nuovo
 *   bambino (parent-command.js, azione "create_child").
 * - Home Assistant <-> backend: un token statico in variabile
 *   d'ambiente Vercel (HA_STATUS_TOKEN), confrontato con l'header della
 *   richiesta.
 * - ID token Firebase Auth (genitore, dalla phone-app) verificato con
 *   l'Admin SDK: serve per gli endpoint chiamati direttamente dal
 *   genitore, dove non ha senso un token statico condiviso come per il
 *   watch.
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-09): checkDeviceToken, checkHaToken.
 * - 0.2.0 (2026-09-10): aggiunta checkParentAuth (chat testuale,
 *   endpoint send-message-to-child.js) — verifica l'ID token Firebase
 *   del genitore e controlla che esista un documento parents/{uid}
 *   (stesso controllo isParent() delle firestore.rules, qui rifatto
 *   lato server perche' le regole non si applicano alle chiamate
 *   Admin SDK del backend).
 * - 0.3.0 (2026-09-11): richiesta utente — supporto a N bambini, non
 *   piu' un solo watch con un token statico globale
 *   (process.env.DEVICE_TOKEN confrontato sempre allo stesso valore).
 *   checkDeviceToken -> resolveDeviceId(req, db): calcola l'hash
 *   SHA-256 del token ricevuto e cerca quale documento devices/{id} ha
 *   quell'hash salvato in deviceTokenHash, restituendo l'id del
 *   bambino invece di un semplice booleano. Mai il token in chiaro su
 *   Firestore (leggibile da qualunque genitore tramite lo stesso
 *   listener che legge posizione/batteria) — solo il suo hash, che non
 *   permette di risalire al token originale. Aggiungere un bambino
 *   diventa quindi self-service (nessuna variabile d'ambiente da
 *   editare a mano per ognuno, vedi parent-command.js/create_child).
 *   Migrazione: il device "figlio" gia' esistente non ha ancora un
 *   deviceTokenHash salvato (con lo schema vecchio l'identita' non
 *   passava affatto dal documento). Se l'hash-lookup non trova nulla,
 *   resolveDeviceId ricade sul confronto col vecchio
 *   process.env.DEVICE_TOKEN: se combacia, salva l'hash su
 *   devices/figlio al volo (self-healing, una tantum) cosi' il watch
 *   gia' installato prima di questa versione continua a funzionare
 *   senza nessun passaggio manuale. DEVICE_TOKEN resta quindi
 *   documentato come legacy in .env.example, non va rimosso.
 */
const crypto = require("crypto");

function hashToken(token) {
  return crypto.createHash("sha256").update(token).digest("hex");
}

const LEGACY_DEVICE_ID = "figlio";

/** Ritorna il childId del device autenticato, o null se il token non corrisponde a nessuno. */
async function resolveDeviceId(req, db) {
  const token = req.headers["x-device-token"];
  if (!token) return null;

  const hash = hashToken(token);
  const snap = await db.collection("devices").where("deviceTokenHash", "==", hash).limit(1).get();
  if (!snap.empty) return snap.docs[0].id;

  // Migrazione automatica dal vecchio schema (vedi Storico versioni
  // v0.3.0 sopra): un solo token statico globale, mai salvato sul
  // documento device. Se il token ricevuto combacia col vecchio
  // DEVICE_TOKEN e "figlio" non ha ancora un deviceTokenHash, lo
  // impostiamo ora invece di rispondere 401 a un watch che prima
  // funzionava.
  if (process.env.DEVICE_TOKEN && token === process.env.DEVICE_TOKEN) {
    const legacyRef = db.collection("devices").doc(LEGACY_DEVICE_ID);
    const legacySnap = await legacyRef.get();
    if (legacySnap.exists && !legacySnap.data().deviceTokenHash) {
      await legacyRef.set({ deviceTokenHash: hash }, { merge: true });
      return LEGACY_DEVICE_ID;
    }
  }

  return null;
}

function checkHaToken(req) {
  const header = req.headers["authorization"] || "";
  const token = header.replace(/^Bearer\s+/i, "");
  return Boolean(token) && token === process.env.HA_STATUS_TOKEN;
}

/** Ritorna l'uid del genitore autenticato, o null se non autorizzato. */
async function checkParentAuth(req, db) {
  const { getAuth } = require("firebase-admin/auth");
  const header = req.headers["authorization"] || "";
  const idToken = header.replace(/^Bearer\s+/i, "");
  if (!idToken) return null;

  try {
    const decoded = await getAuth().verifyIdToken(idToken);
    const parentDoc = await db.collection("parents").doc(decoded.uid).get();
    return parentDoc.exists ? decoded.uid : null;
  } catch (e) {
    return null;
  }
}

module.exports = { resolveDeviceId, hashToken, checkHaToken, checkParentAuth };
