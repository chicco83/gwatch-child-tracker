/**
 * POST /api/sos-heartbeat
 * Versione: 0.2.0
 *
 * Ping di posizione ogni 30 secondi durante un SOS attivo (vedi
 * watch-app/location/SosLocationService.kt), finche' il genitore non
 * disattiva l'SOS dalla phone-app (azione "cancel_sos" di
 * parent-command.js). Endpoint separato da trigger-event.js apposta:
 * qui NON si scrive un evento in devices/{id}/events e NON parte
 * nessuna push — solo l'aggiornamento di lastLocation, altrimenti ogni
 * 30" il genitore riceverebbe una notifica e la collection eventi si
 * riempirebbe di centinaia di documenti per un singolo episodio SOS.
 *
 * Risponde 409 se l'SOS non risulta piu' attivo lato server (il
 * genitore l'ha gia' disattivato): il watch usa questo segnale come
 * rete di sicurezza per fermarsi da solo anche se la push
 * "sos_cancel" dovesse andare persa.
 *
 * Auth: header "X-Device-Token" (identifica il bambino, vedi
 * _lib/auth.js/resolveDeviceId).
 * Body: { lat, lon, accuracy?, battery?, timestamp? }
 *
 * Niente guardia di quota qui, deliberatamente: come per "sos" in
 * trigger-event.js, e' la funzione di sicurezza piu' critica dell'app
 * e non deve mai poter essere bloccata da un limite di traffico.
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-10): versione iniziale.
 * - 0.2.0 (2026-09-11): rimosso il "DEVICE_ID" hardcoded ("figlio") —
 *   ora supporta N bambini, il childId si risolve dal token.
 */
const { getFirestore, Timestamp, FieldValue } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");
const { resolveDeviceId } = require("./_lib/auth");

module.exports = async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }

  getAdminApp();
  const db = getFirestore();

  const childId = await resolveDeviceId(req, db);
  if (!childId) {
    res.status(401).send("Unauthorized");
    return;
  }

  const { lat, lon, accuracy, battery, timestamp } = req.body || {};
  if (typeof lat !== "number" || typeof lon !== "number") {
    res.status(400).send("Bad Request: 'lat'/'lon' mancanti o non validi");
    return;
  }

  const deviceRef = db.collection("devices").doc(childId);

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
