/**
 * GET /api/device-config
 * Versione: 0.1.0
 *
 * Restituisce al watch le geofence attive configurate dal genitore
 * dalla phone-app, per registrarle localmente con la Geofencing API
 * di Android (gestione a livello OS, risparmio batteria).
 *
 * Auth: header "X-Device-Token".
 */
const { getFirestore } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkDeviceToken } = require("./_lib/auth");

const DEVICE_ID = "figlio";

module.exports = async (req, res) => {
  if (req.method !== "GET") {
    res.status(405).send("Method Not Allowed");
    return;
  }
  if (!checkDeviceToken(req)) {
    res.status(401).send("Unauthorized");
    return;
  }

  getAdminApp();
  const db = getFirestore();
  const snap = await db
    .collection("devices")
    .doc(DEVICE_ID)
    .collection("geofences")
    .where("active", "==", true)
    .get();

  const geofences = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
  res.status(200).json({ geofences });
};
