/**
 * POST /api/trigger-event
 * Versione: 0.4.0
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
 * Body: { type: "sos" | "geofence_enter" | "geofence_exit" |
 *         "location_request", lat, lon, accuracy?, battery?,
 *         zoneName?, timestamp? }
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-09): versione iniziale.
 * - 0.2.0 (2026-09-09): aggiunta la guardia di quota giornaliera (vedi
 *   _lib/quota.js) come rete di sicurezza contro le soglie gratuite di
 *   Firestore/Vercel. L'SOS ne e' volutamente ESENTE: e' la funzione
 *   di sicurezza piu' critica dell'app, non deve mai poter essere
 *   bloccata da un limite di traffico, nemmeno in caso di quota gia'
 *   esaurita da un malfunzionamento altrove.
 * - 0.3.0 (2026-09-10): aggiunto "location_request" — pulsante
 *   "Invia posizione attuale" sul watch (invio manuale su richiesta
 *   del bambino, a differenza del tracking periodico automatico di
 *   ingest-location.js). Riusa lo stesso evento+push del SOS/geofence
 *   invece di un endpoint dedicato: stessa scrittura in
 *   devices/{id}/events e stessa notifica FCM al genitore.
 * - 0.4.0 (2026-09-10): bug — l'evento veniva scritto solo in
 *   devices/{id}/events, senza aggiornare devices/{id}.lastLocation:
 *   il pin sulla mappa della phone-app resta fermo fino al prossimo
 *   upload periodico di ingest-location.js, anche premendo SOS o
 *   "Invia posizione". Aggiunto lo stesso merge di
 *   lastLocation/battery/lastSeen che fa ingest-location.js, cosi'
 *   la mappa si aggiorna subito.
 */
const { getFirestore, Timestamp, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkDeviceToken } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const DEVICE_ID = "figlio";
const VALID_TYPES = new Set(["sos", "geofence_enter", "geofence_exit", "location_request"]);

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
  if (type === "geofence_exit") {
    return { title: "Uscita zona", body: `Uscito da "${zoneName ?? "zona"}".` };
  }
  return {
    title: "Posizione aggiornata",
    body: "Il bambino ha inviato la posizione attuale.",
  };
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

  if (type !== "sos") {
    const allowed = await checkAndConsumeQuota(db, DEVICE_ID);
    if (!allowed) {
      res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
      return;
    }
  }

  const ts = timestamp ? Timestamp.fromMillis(timestamp) : Timestamp.now();
  const deviceRef = db.collection("devices").doc(DEVICE_ID);

  const batch = db.batch();
  batch.set(deviceRef.collection("events").doc(), {
    type,
    lat,
    lon,
    accuracy: accuracy ?? null,
    battery: battery ?? null,
    zoneName: zoneName ?? null,
    timestamp: ts,
    acknowledged: false,
  });
  batch.set(
    deviceRef,
    {
      lastLocation: { lat, lon, accuracy: accuracy ?? null },
      battery: battery ?? null,
      lastSeen: ts,
      updatedAt: FieldValue.serverTimestamp(),
    },
    { merge: true },
  );
  await batch.commit();

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
