/**
 * POST /api/trigger-event
 * Versione: 0.8.0
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
 *         zoneId?, source? ("child" | "parent", solo per
 *         "location_request"), timestamp? }
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
 * - 0.5.0 (2026-09-10): l'SOS ora marca devices/{id}.sosActive=true al
 *   primo trigger — letto dalla phone-app per mostrare il banner "SOS
 *   attivo" con pulsante di disattivazione (vedi cancel-sos.js) e dal
 *   watch (sos-heartbeat.js) per sapere quando smettere di inviare la
 *   posizione ogni 30" anche se la push di cancellazione va persa. La
 *   notifica push "SOS ricevuto" parte solo al PRIMO evento sos
 *   dell'episodio (sosActive era gia' false), non ad ogni evento sos
 *   successivo, per non spammare il genitore.
 * - 0.6.0 (2026-09-10): il body ora manda "zoneId" (prima "zoneName",
 *   ma il valore era sempre stato l'id Firestore della zona — bug: le
 *   notifiche mostravano l'id al posto del nome). Per ogni transizione
 *   geofence la zona viene letta da Firestore con questo id: risolve
 *   il nome vero E legge i nuovi toggle per-zona configurabili dalla
 *   phone-app (GeofenceScreen.kt) — notifyOnEnter/notifyOnExit (invia
 *   o silenzia la notifica per direzione) e alarmOnExit (in aggiunta
 *   alla notifica normale, manda anche un secondo messaggio data-only
 *   "exit_alarm" che fa partire un allarme sonoro/vibrazione ripetuto
 *   sul telefono, vedi phone-app/.../alarm/ExitAlarmService.kt — serve
 *   data-only, non notification+data, per poter partire anche ad app
 *   in background/uccisa, non solo quando l'utente tocca la notifica).
 *   Zone create prima di questa versione non hanno questi campi:
 *   default notifyOnEnter/notifyOnExit = true (comportamento
 *   invariato), alarmOnExit = false (funzione opt-in, mai attiva senza
 *   scelta esplicita).
 * - 0.7.0 (2026-09-10): aggiunto "source" al body di "location_request"
 *   ("child" | "parent") — prima un invio manuale del bambino (pulsante
 *   sul watch) e una richiesta remota del genitore ("Aggiorna
 *   posizione" sulla phone-app) mandavano lo stesso identico evento:
 *   la notifica al genitore diceva sempre "il bambino ha inviato la
 *   posizione", anche quando l'aveva chiesta lui stesso. source assente
 *   (client watch non aggiornato) si comporta come "child", invariato.
 * - 0.8.0 (2026-09-10): "source" ora e' salvato anche sul documento
 *   evento (prima usato solo per il testo della notifica) — serve alla
 *   phone-app per distinguere un invio "location_request" davvero
 *   iniziato dal bambino da una richiesta remota del genitore: solo il
 *   primo caso ha senso per la notifica "il genitore ha visto la tua
 *   posizione" (vedi ack-event.js, nuovo in questa stessa versione).
 */
const { getFirestore, Timestamp, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { checkDeviceToken } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");

const DEVICE_ID = "figlio";
const VALID_TYPES = new Set(["sos", "geofence_enter", "geofence_exit", "location_request"]);

function buildNotification(type, zoneName, source) {
  if (type === "sos") {
    return {
      title: "SOS ricevuto",
      body: "Premuto il pulsante SOS sul watch. Posizione aggiornata.",
    };
  }
  if (type === "geofence_enter") {
    return { title: "Ingresso zona", body: `Entrato in "${zoneName}".` };
  }
  if (type === "geofence_exit") {
    return { title: "Uscita zona", body: `Uscito da "${zoneName}".` };
  }
  // "location_request": source distingue un invio manuale del bambino
  // (pulsante sul watch) da una richiesta remota del genitore
  // ("Aggiorna posizione" sulla phone-app) — prima la notifica diceva
  // sempre "il bambino ha inviato la posizione", anche quando l'aveva
  // chiesta il genitore stesso.
  if (source === "parent") {
    return { title: "Posizione aggiornata", body: "Posizione aggiornata su tua richiesta." };
  }
  return {
    title: "Posizione aggiornata",
    body: "Il bambino ha inviato la posizione attuale.",
  };
}

async function fetchParentFcmTokens(db) {
  const parentSnap = await db.collection("parents").get();
  const tokens = [];
  parentSnap.forEach((p) => {
    const t = p.data().fcmTokens;
    if (Array.isArray(t)) tokens.push(...t);
  });
  return tokens;
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

  const { type, lat, lon, accuracy, battery, zoneId, source, timestamp } = req.body || {};
  if (!VALID_TYPES.has(type) || typeof lat !== "number" || typeof lon !== "number") {
    res.status(400).send("Bad Request: 'type'/'lat'/'lon' mancanti o non validi");
    return;
  }

  getAdminApp();
  const db = getFirestore();
  const deviceRef = db.collection("devices").doc(DEVICE_ID);

  if (type !== "sos") {
    const allowed = await checkAndConsumeQuota(db, DEVICE_ID);
    if (!allowed) {
      res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
      return;
    }
  }

  // Config della zona (nome vero + toggle notifica/allarme): letta solo
  // per le transizioni geofence, dall'id che il watch riceve gia' da
  // GET /api/device-config. Zona non trovata (cancellata nel frattempo
  // dal genitore) -> notifica comunque, coi default piu' prudenti.
  let zoneName = "zona";
  let notifyOnEnter = true;
  let notifyOnExit = true;
  let alarmOnExit = false;
  if (type === "geofence_enter" || type === "geofence_exit") {
    const zoneSnap = zoneId ? await deviceRef.collection("geofences").doc(zoneId).get() : null;
    const zone = zoneSnap?.exists ? zoneSnap.data() : null;
    if (zone) {
      zoneName = zone.name || zoneName;
      notifyOnEnter = zone.notifyOnEnter !== false;
      notifyOnExit = zone.notifyOnExit !== false;
      alarmOnExit = zone.alarmOnExit === true;
    }
  }

  const ts = timestamp ? Timestamp.fromMillis(timestamp) : Timestamp.now();

  // Serve PRIMA della scrittura per sapere se questo e' il primo "sos"
  // dell'episodio (e quindi se notificare) o un evento successivo.
  const wasSosActive = type === "sos" ? (await deviceRef.get()).data()?.sosActive === true : false;

  const deviceUpdate = {
    lastLocation: { lat, lon, accuracy: accuracy ?? null },
    battery: battery ?? null,
    lastSeen: ts,
    updatedAt: FieldValue.serverTimestamp(),
  };
  if (type === "sos") {
    deviceUpdate.sosActive = true;
  }

  const batch = db.batch();
  batch.set(deviceRef.collection("events").doc(), {
    type,
    lat,
    lon,
    accuracy: accuracy ?? null,
    battery: battery ?? null,
    zoneId: zoneId ?? null,
    zoneName: type === "geofence_enter" || type === "geofence_exit" ? zoneName : null,
    // "source" (solo su "location_request") serve alla phone-app per
    // sapere se notificare al watch, con /api/ack-event, che il
    // genitore ha visto la posizione — ha senso solo per un invio
    // decisamente iniziato dal bambino (pulsante sul watch), non per
    // una richiesta remota del genitore stesso. Vedi ack-event.js.
    source: type === "location_request" ? (source ?? "child") : null,
    timestamp: ts,
    acknowledged: false,
  });
  batch.set(deviceRef, deviceUpdate, { merge: true });
  await batch.commit();

  // Niente notifica per un "sos" quando l'episodio e' gia' attivo
  // (arriva qui solo se il bambino ripreme il pulsante durante un SOS
  // gia' in corso — non per i ping ogni 30", quelli passano da
  // sos-heartbeat.js e non toccano questo endpoint). Per le geofence,
  // rispetta il toggle per-zona/per-direzione scelto dal genitore.
  const shouldNotify =
    type === "sos" ? !wasSosActive :
    type === "geofence_enter" ? notifyOnEnter :
    type === "geofence_exit" ? notifyOnExit :
    true;
  const shouldAlarm = type === "geofence_exit" && alarmOnExit;

  if (shouldNotify || shouldAlarm) {
    const tokens = await fetchParentFcmTokens(db);
    if (tokens.length > 0) {
      if (shouldNotify) {
        const { title, body } = buildNotification(type, zoneName, source);
        await getMessaging().sendEachForMulticast({
          tokens,
          notification: { title, body },
          data: { type, lat: String(lat), lon: String(lon) },
          android: { priority: "high" },
        });
      }
      if (shouldAlarm) {
        // Data-only (nessun campo "notification"): deve poter avviare
        // ExitAlarmService anche ad app in background/uccisa, non solo
        // mostrare una notifica passiva quando l'utente la tocca (vedi
        // storico versioni sopra).
        await getMessaging().sendEachForMulticast({
          tokens,
          data: { type: "exit_alarm", zoneName },
          android: { priority: "high" },
        });
      }
    }
  }

  res.status(200).json({ ok: true });
};
