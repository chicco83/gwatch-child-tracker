/**
 * POST /api/sos-heartbeat
 * Versione: 0.1.0
 *
 * Ping di posizione ogni 30 secondi durante un SOS attivo (vedi
 * watch-app/location/SosLocationService.kt), finche' il genitore non
 * disattiva l'SOS dalla phone-app (cancel-sos.js). Endpoint separato
 * da trigger-event.js apposta: qui NON si scrive un evento in
 * devices/{id}/events e NON parte nessuna push — solo l'aggiornamento
 * di lastLocation, altrimenti ogni 30" il genitore riceverebbe una
 * notifica e la collection eventi si riempirebbe di centinaia di
 * documenti per un singolo episodio SOS.
 *
 * Risponde 409 se l'SOS non risulta piu' attivo lato server (il
 * genitore l'ha gia' disattivato): il watch usa questo segnale come
 * rete di sicurezza per fermarsi da solo anche se la push
 * "sos_cancel" dovesse andare persa.
 *
 * Auth: header "X-Device-Token".
 * Body: { lat, lon, accuracy?, battery?, timestamp? }
 *
 * Niente guardia di quota qui, deliberatamente: come per "sos" in
 * trigger-event.js, e' la funzione di sicurezza piu' critica dell'app
 * e non deve mai poter essere bloccata da un limite di traffico.
 */
const { getFirestore, Timestamp, FieldValue } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkDeviceToken } = require("./_lib/auth");

const DEVICE_ID = "figlio";

module.exports = async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }
  if (!checkDeviceToken(req)) {
    res.status(401).send("Unauthorized");
    return;
  }

  const { lat, lon, accuracy, battery, timestamp } = req.body || {};
  if (typeof lat !== "number" || typeof lon !== "number") {
    res.status(400).send("Bad Request: 'lat'/'lon' mancanti o non validi");
    return;
  }

  getAdminApp();
  const db = getFirestore();
  const deviceRef = db.collection("devices").doc(DEVICE_ID);

  const snap = await deviceRef.get();
  if (snap.data()?.sosActive !== true) {
    res.status(409).send("SOS non piu' attivo");
    return;
  }

  const ts = timestamp ? Timestamp.fromMillis(timestamp) : Timestamp.now();
  await deviceRef.set(
    {
      lastLocation: { lat, lon, accuracy: accuracy ?? null },
      battery: battery ?? null,
      lastSeen: ts,
      updatedAt: FieldValue.serverTimestamp(),
    },
    { merge: true },
  );

  res.status(200).json({ ok: true });
};
