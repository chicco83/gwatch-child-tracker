/**
 * POST /api/cancel-sos
 * Versione: 0.1.0
 *
 * Il genitore (phone-app) disattiva un SOS in corso: marca
 * devices/{id}.sosActive=false e manda una push FCM data-only al
 * watch per fermare subito SosLocationService (i ping ogni 30", vedi
 * sos-heartbeat.js) invece di aspettare che il watch se ne accorga da
 * solo al prossimo ping rifiutato con 409.
 *
 * Auth: header "Authorization: Bearer <Firebase ID token genitore>".
 * Nessun body richiesto.
 */
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkParentAuth } = require("./_lib/auth");

const DEVICE_ID = "figlio";

module.exports = async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }

  getAdminApp();
  const db = getFirestore();

  const parentUid = await checkParentAuth(req, db);
  if (!parentUid) {
    res.status(401).send("Unauthorized");
    return;
  }

  const deviceRef = db.collection("devices").doc(DEVICE_ID);
  await deviceRef.set(
    { sosActive: false, updatedAt: FieldValue.serverTimestamp() },
    { merge: true },
  );

  const deviceSnap = await deviceRef.get();
  const watchToken = deviceSnap.data()?.fcmToken;
  if (watchToken) {
    await getMessaging().send({
      token: watchToken,
      data: { type: "sos_cancel" },
      android: { priority: "high" },
    });
  }

  res.status(200).json({ ok: true });
};
