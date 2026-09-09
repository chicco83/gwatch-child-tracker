/**
 * GET /api/ha-status
 * Versione: 0.1.0
 *
 * Endpoint di sola lettura per il polling di Home Assistant
 * (piattaforma `rest`). Livello aggiuntivo opzionale (vedi
 * CONTEXT.md, decisione v0.3.0): la sua assenza o il suo fallimento
 * non devono mai influenzare le altre funzioni.
 *
 * Auth: header "Authorization: Bearer <token>".
 */
const { getFirestore } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkHaToken } = require("./_lib/auth");

const DEVICE_ID = "figlio";

module.exports = async (req, res) => {
  if (req.method !== "GET") {
    res.status(405).send("Method Not Allowed");
    return;
  }
  if (!checkHaToken(req)) {
    res.status(401).send("Unauthorized");
    return;
  }

  getAdminApp();
  const db = getFirestore();
  const doc = await db.collection("devices").doc(DEVICE_ID).get();
  if (!doc.exists) {
    res.status(404).json({ error: "device non trovato" });
    return;
  }

  const data = doc.data();
  res.status(200).json({
    lat: data.lastLocation?.lat ?? null,
    lon: data.lastLocation?.lon ?? null,
    accuracy: data.lastLocation?.accuracy ?? null,
    battery: data.battery ?? null,
    last_seen: data.lastSeen ? data.lastSeen.toDate().toISOString() : null,
  });
};
