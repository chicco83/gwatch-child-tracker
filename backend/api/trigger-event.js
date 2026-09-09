/**
 * POST /api/trigger-event
 * Versione: 0.1.0
 *
 * Evento prioritario dal watch: SOS o transizione geofence
 * (ingresso/uscita zona). Scrive l'evento e invia subito la push FCM
 * al genitore nella stessa chiamata: su Vercel non esiste un
 * equivalente del trigger Firestore onDocumentCreated di Firebase
 * Functions, quindi qui combiniamo scrittura + notifica in un solo
 * passo invece di separarli in due funzioni (piu' semplice, e per
 * questi eventi non serve comunque disaccoppiarli).
 *
 * Auth: header "X-Device-Token".
 * Body: { type: "sos" | "geofence_enter" | "geofence_exit", lat, lon,
 *         accuracy?, battery?, zoneName?, timestamp? }
 */
const { getFirestore, Timestamp } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkDeviceToken } = require("./_lib/auth");

const DEVICE_ID = "figlio";
const VALID_TYPES = new Set(["sos", "geofence_enter", "geofence_exit"]);

function buildNotification(type, zoneName) {
  if (type === "sos") {
    return {
      title: "SOS ricevuto",
      body: "Premuto il pulsante SOS sul watch. Posizione aggiornata.",
    };
  }
  if (type === "geofence_enter") {
    return { title: "Ingresso zona", body: `Entrato in "${zoneName ?? "zona"}".` };
  }
  return { title: "Uscita zona", body: `Uscito da "${zoneName ?? "zona"}".` };
}

module.exports = async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }
  if (!checkDeviceToken(req)) {
    res.status(401).send("Unauthorized");
    return;
  }

  const { type, lat, lon, accuracy, battery, zoneName, timestamp } = req.body || {};
  if (!VALID_TYPES.has(type) || typeof lat !== "number" || typeof lon !== "number") {
    res.status(400).send("Bad Request: 'type'/'lat'/'lon' mancanti o non validi");
    return;
  }

  getAdminApp();
  const db = getFirestore();
  const ts = timestamp ? Timestamp.fromMillis(timestamp) : Timestamp.now();

  await db
    .collection("devices")
    .doc(DEVICE_ID)
    .collection("events")
    .add({
      type,
      lat,
      lon,
      accuracy: accuracy ?? null,
      battery: battery ?? null,
      zoneName: zoneName ?? null,
      timestamp: ts,
      acknowledged: false,
    });

  const parentSnap = await db.collection("parents").get();
  const tokens = [];
  parentSnap.forEach((p) => {
    const t = p.data().fcmTokens;
    if (Array.isArray(t)) tokens.push(...t);
  });

  if (tokens.length > 0) {
    const { title, body } = buildNotification(type, zoneName);
    await getMessaging().sendEachForMulticast({
      tokens,
      notification: { title, body },
      data: { type, lat: String(lat), lon: String(lon) },
      android: { priority: "high" },
    });
  }

  res.status(200).json({ ok: true });
};
