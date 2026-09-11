/**
 * GET /api/ha-status
 * Versione: 0.3.0
 *
 * Endpoint di sola lettura per il polling di Home Assistant
 * (piattaforma `rest`). Livello aggiuntivo opzionale (vedi
 * CONTEXT.md, decisione v0.3.0): la sua assenza o il suo fallimento
 * non devono mai influenzare le altre funzioni.
 *
 * Auth: header "Authorization: Bearer <token>" (token statico
 * HA_STATUS_TOKEN, uguale per tutti i bambini — non e' il token per
 * bambino usato dal watch).
 * Query: ?child=<childId>, facoltativo — default "figlio" (il primo
 * bambino, quello esistente prima del supporto a N bambini) per non
 * rompere una configurazione Home Assistant gia' in uso. Con piu' di
 * un bambino, aggiungere un secondo sensore `rest` con
 * `?child=<altro id>`.
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-09): versione iniziale.
 * - 0.2.0 (2026-09-09): aggiunta la guardia di quota giornaliera (vedi
 *   _lib/quota.js), condivisa con gli altri endpoint dello stesso
 *   dispositivo. Se mai esaurita, blocca solo il polling opzionale di
 *   HA (mai una funzione di sicurezza), coerente con la scelta che HA
 *   resti un livello aggiuntivo non vincolante.
 * - 0.3.0 (2026-09-11): rimosso il "DEVICE_ID" hardcoded ("figlio") —
 *   ora facoltativo via query string per supportare N bambini, con lo
 *   stesso default di prima quando non specificato.
 */
const { getFirestore } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkHaToken } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const DEFAULT_CHILD_ID = "figlio";

module.exports = async (req, res) => {
  if (req.method !== "GET") {
    res.status(405).send("Method Not Allowed");
    return;
  }
  if (!checkHaToken(req)) {
    res.status(401).send("Unauthorized");
    return;
  }

  const childId = typeof req.query?.child === "string" && req.query.child.trim().length > 0
    ? req.query.child.trim()
    : DEFAULT_CHILD_ID;

  getAdminApp();
  const db = getFirestore();

  const allowed = await checkAndConsumeQuota(db, childId);
  if (!allowed) {
    res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
    return;
  }

  const doc = await db.collection("devices").doc(childId).get();
  if (!doc.exists) {
    res.status(404).json({ error: "device non trovato" });
    return;
  }

  const data = doc.data();
  res.status(200).json({
    child_name: data.childName ?? null,
    lat: data.lastLocation?.lat ?? null,
    lon: data.lastLocation?.lon ?? null,
    accuracy: data.lastLocation?.accuracy ?? null,
    battery: data.battery ?? null,
    last_seen: data.lastSeen ? data.lastSeen.toDate().toISOString() : null,
  });
};
