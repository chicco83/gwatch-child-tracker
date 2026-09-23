/**
 * POST /api/ingest-location
 * Versione: 0.10.0
 *
 * Riceve dal watch un batch di punti posizione accumulati (risparmio
 * batteria: un solo invio di rete per piu' punti, vedi CONTEXT.md) e
 * aggiorna sia lo stato corrente del dispositivo sia lo storico.
 *
 * Auth: header "X-Device-Token" (identifica il bambino, vedi
 * _lib/auth.js/resolveDeviceId).
 * Body: { points: [{ lat, lon, accuracy?, battery?, activity?, timestamp? }, ...] }
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-09): retention 48h.
 * - 0.2.0 (2026-09-09): retention estesa a 12 mesi (il consumo di
 *   storage resta comunque una piccola frazione del GB gratuito, vedi
 *   CONTEXT.md); aggiunti un tetto massimo di punti per chiamata e la
 *   guardia di quota giornaliera (vedi _lib/quota.js) come rete di
 *   sicurezza contro le soglie gratuite di Firestore/Vercel.
 * - 0.3.0 (2026-09-11): rimosso il "DEVICE_ID" hardcoded ("figlio") —
 *   ora supporta N bambini, il childId si risolve dal token via
 *   resolveDeviceId(req, db) (vedi _lib/auth.js).
 * - 0.4.0 (2026-09-18): aggiunti "batteryTemp"/"charging" ai punti
 *   (richiesti dall'utente — vedi watch-app/.../location/BatteryInfo.kt),
 *   salvati sia sullo storico locations sia sull'ultimo stato del
 *   device, stesso pattern gia' in uso per "battery"/"activity".
 * - 0.5.0 (2026-09-18): aggiunto "speed" (m/s, Location.getSpeed() —
 *   richiesto dall'utente per mostrarla sulla mappa), stesso pattern.
 * - 0.6.0 (2026-09-19): richiesto dall'utente — notifiche automatiche
 *   al genitore quando la batteria del watch scende al 10%/5%, con
 *   richiesta posizione + messaggio automatico al watch al 2% (vedi
 *   nuovo _lib/batteryAlerts.js, chiamato qui dopo il commit del batch
 *   con la batteria dell'ultimo punto ricevuto).
 * - 0.7.0 (2026-09-19): aggiunto "batteryHoursRemaining" ai punti
 *   (richiesto dall'utente — autonomia residua in ore, chiesta dal
 *   watch al proprio sistema operativo, vedi watch-app/.../location/
 *   BatteryInfo.kt), salvato sull'ultimo stato del device insieme al
 *   resto della batteria. Non salvato sullo storico locations (a
 *   differenza di battery/batteryTemp/ecc.): non serve nessuna
 *   estrapolazione lato client, il dato e' gia' la stima diretta del
 *   sistema operativo del watch ad ogni campione.
 * - 0.8.0 (2026-09-22): bug segnalato dall'utente — la StatusCard
 *   mostrava "ultima posizione 5 ore fa" anche se lo storico
 *   ("Percorso 24h") aveva gia' punti molto piu' recenti. Causa: lo
 *   stato "attuale" del device (lastLocation/lastSeen/batteria/ecc.)
 *   veniva sovrascritto in modo INCONDIZIONATO ad ogni chiamata, con
 *   l'ultimo punto DI QUESTO BATCH — senza controllare se fosse
 *   davvero piu' recente di quanto gia' salvato. Con connettivita'
 *   ballerina (es. a scuola) due upload (quello periodico e quello
 *   "immediato" al superamento soglia buffer, vedi
 *   LocationTrackingService.kt) possono restare entrambi in volo
 *   contemporaneamente: se quello con dati piu' vecchi completa DOPO
 *   quello con dati piu' freschi, il suo scrivere regredisce lo stato
 *   "attuale" all'indietro nel tempo — pur restando tutti i singoli
 *   punti corretti nello storico "locations" (mai sovrascritto,
 *   ogni punto e' un documento a se'), da cui la discrepanza. Lo stato
 *   "attuale" ora si scrive solo dentro una transazione che confronta
 *   il nuovo timestamp con l'attuale "lastSeen" salvato e salta la
 *   scrittura se non e' piu' recente — i punti storici restano scritti
 *   sempre, incondizionatamente, come prima.
 * - 0.9.0 (2026-09-23): Fase 3 di qwen_plan.md (individuato da
 *   qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — checkAndConsumeQuota
 *   contava questa chiamata come "1", ma scrive fino a
 *   MAX_POINTS_PER_REQUEST documenti "locations": la guardia di quota
 *   sottostimava di molto le scritture Firestore reali. Ora passa
 *   points.length come peso (vedi _lib/quota.js v0.2.0).
 * - 0.10.0 (2026-09-23): Fase 4 di qwen_plan.md (individuato da
 *   qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — tre difetti
 *   minori. (1) "received" nella risposta era sempre points.length,
 *   anche contando i punti scartati dal "continue" per lat/lon
 *   mancanti: il chiamante non poteva sapere quanti fossero DAVVERO
 *   stati scritti. (2) Nessun range-check su lat/lon (es. un bug lato
 *   watch che mandasse lat=9999 veniva scritto cosi' com'e', nessuna
 *   guardia lo fermava) — aggiunta isValidPoint() (numeri finiti,
 *   |lat|<=90, |lon|<=180); il peso della quota (v0.9.0) ora usa il
 *   conteggio dei punti VALIDI, non quello grezzo del body. Un batch
 *   senza nessun punto valido e' ora un 400 invece di un 200 silenzioso
 *   con "received" fuorviante. (3) validateConfig() spostato dentro
 *   wrapHandler (vedi _lib/errors.js v0.2.0) — rimosso da qui, girava
 *   solo su 3 endpoint su 9, ora su tutti.
 */
const { getFirestore, Timestamp, FieldValue } = require("firebase-admin/firestore");
const { wrapHandler, errorResponse, successResponse, logError } = require("./_lib/errors.js");
const { getAdminApp } = require("./_lib/firebase-admin");
const { resolveDeviceId } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");
const { checkBatteryAlerts } = require("./_lib/batteryAlerts.js");

const HISTORY_RETENTION_HOURS = 24 * 365; // 12 mesi, vedi nota sopra
const MAX_POINTS_PER_REQUEST = 100; // limite difensivo per singola chiamata

// v0.10.0: coordinate valide (vedi Storico versioni sopra). Number.isFinite
// esclude anche NaN/Infinity, non solo i valori fuori range.
function isValidPoint(p) {
  return (
    Number.isFinite(p?.lat) && p.lat >= -90 && p.lat <= 90 &&
    Number.isFinite(p?.lon) && p.lon >= -180 && p.lon <= 180
  );
}

module.exports = wrapHandler(async (req, res) => {
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

  const points = Array.isArray(req.body?.points) ? req.body.points : null;
  if (!points || points.length === 0) {
    res.status(400).send("Bad Request: 'points' mancante o vuoto");
    return;
  }
  if (points.length > MAX_POINTS_PER_REQUEST) {
    res.status(400).send(`Bad Request: massimo ${MAX_POINTS_PER_REQUEST} punti per chiamata`);
    return;
  }

  // v0.10.0: filtra i punti validi PRIMA della guardia di quota, cosi'
  // il peso (v0.9.0) riflette le scritture Firestore reali che questa
  // chiamata sta per fare, non il conteggio grezzo del body (vedi
  // Storico versioni sopra).
  const validPoints = points.filter(isValidPoint);
  if (validPoints.length === 0) {
    res.status(400).send("Bad Request: nessun punto valido nel batch (lat/lon mancanti o fuori range)");
    return;
  }

  const allowed = await checkAndConsumeQuota(db, childId, validPoints.length);
  if (!allowed) {
    res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
    return;
  }

  const deviceRef = db.collection("devices").doc(childId);
  const batch = db.batch();
  let last = null;

  for (const p of validPoints) {
    const ts = p.timestamp ? Timestamp.fromMillis(p.timestamp) : Timestamp.now();
    const expiresAt = Timestamp.fromMillis(
      ts.toMillis() + HISTORY_RETENTION_HOURS * 60 * 60 * 1000
    );

    const locRef = deviceRef.collection("locations").doc();
    batch.set(locRef, {
      lat: p.lat,
      lon: p.lon,
      accuracy: p.accuracy ?? null,
      battery: p.battery ?? null,
      activity: p.activity ?? null,
      batteryTemp: p.batteryTemp ?? null,
      charging: p.charging ?? null,
      speed: p.speed ?? null,
      timestamp: ts,
      expiresAt, // usato dalla TTL policy Firestore per la pulizia automatica
    });
    // batteryHoursRemaining non va nello storico locations qui sopra
    // (vedi Storico versioni 0.7.0): e' gia' una stima diretta del
    // sistema operativo del watch, non un dato da riguardare nel tempo.

    if (!last || ts.toMillis() > last.timestamp.toMillis()) {
      last = { ...p, timestamp: ts };
    }
  }

  // Lo storico (sopra, nel batch) si scrive sempre incondizionatamente:
  // ogni punto e' un documento a se', non c'e' un "piu' vecchio" da
  // proteggere. Lo stato "attuale" del device invece puo' arrivare da
  // chiamate in volo contemporaneamente con dati di eta' diversa (vedi
  // Storico versioni 0.8.0) — va scritto solo se il nuovo punto e'
  // davvero piu' recente di quanto gia' salvato, altrimenti si rischia
  // di regredire "lastSeen" all'indietro nel tempo.
  await batch.commit();

  let updatedCurrentState = false;
  if (last) {
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(deviceRef);
      const currentLastSeen = snap.exists ? snap.get("lastSeen") : null;
      if (currentLastSeen && currentLastSeen.toMillis() >= last.timestamp.toMillis()) {
        return; // dato piu' vecchio (o pari) di quanto gia' salvato: non regredire
      }
      tx.set(
        deviceRef,
        {
          lastLocation: {
            lat: last.lat,
            lon: last.lon,
            accuracy: last.accuracy ?? null,
          },
          battery: last.battery ?? null,
          activity: last.activity ?? null,
          batteryTemp: last.batteryTemp ?? null,
          charging: last.charging ?? null,
          speed: last.speed ?? null,
          batteryHoursRemaining: last.batteryHoursRemaining ?? null,
          lastSeen: last.timestamp,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
      updatedCurrentState = true;
    });
  }

  // Le notifiche di batteria scarica valutano lo stato piu' fresco
  // conosciuto: se questo batch era piu' vecchio di quanto gia' salvato
  // (updatedCurrentState=false), il livello di batteria che porta non
  // e' il piu' recente e non ha senso valutarlo per un allarme.
  if (last && updatedCurrentState) {
    await checkBatteryAlerts(db, deviceRef, childId, last.battery ?? null, last.charging ?? null);
  }

  successResponse(res, { received: validPoints.length });
});
