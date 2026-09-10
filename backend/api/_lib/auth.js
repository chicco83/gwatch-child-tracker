/**
 * Verifica delle identita' che possono chiamare gli endpoint del
 * backend. Due schemi diversi:
 * - token statici (watch <-> backend, Home Assistant <-> backend) in
 *   variabili d'ambiente Vercel, confrontati con l'header della
 *   richiesta. Stesso schema gia' documentato in CONTEXT.md quando il
 *   backend era su Firebase Functions.
 * - ID token Firebase Auth (genitore, dalla phone-app) verificato con
 *   l'Admin SDK: serve per gli endpoint chiamati direttamente dal
 *   genitore (es. send-message-to-child), dove non ha senso un token
 *   statico condiviso come per il watch.
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-09): checkDeviceToken, checkHaToken.
 * - 0.2.0 (2026-09-10): aggiunta checkParentAuth (chat testuale,
 *   endpoint send-message-to-child.js) — verifica l'ID token Firebase
 *   del genitore e controlla che esista un documento parents/{uid}
 *   (stesso controllo isParent() delle firestore.rules, qui rifatto
 *   lato server perche' le regole non si applicano alle chiamate
 *   Admin SDK del backend).
 */
function checkDeviceToken(req) {
  const token = req.headers["x-device-token"];
  return Boolean(token) && token === process.env.DEVICE_TOKEN;
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

module.exports = { checkDeviceToken, checkHaToken, checkParentAuth };
