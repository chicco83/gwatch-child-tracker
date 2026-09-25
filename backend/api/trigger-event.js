/**
 * POST /api/trigger-event
 * Versione: 0.25.0
 *
 * Evento prioritario dal watch: SOS o transizione geofence
 * (ingresso/uscita zona). Scrive l'evento e invia subito la push FCM
 * al genitore nella stessa chiamata: su Vercel non esiste un
 * equivalente del trigger Firestore onDocumentCreated di Firebase
 * Functions, quindi qui combiniamo scrittura + notifica in un solo
 * passo invece di separarli in due funzioni (piu' semplice, e per
 * questi eventi non serve comunque disaccoppiarli).
 *
 * Auth: header "X-Device-Token" (identifica il bambino, vedi
 * _lib/auth.js/resolveDeviceId).
 * Body: { type: "sos" | "geofence_enter" | "geofence_exit" |
 *         "location_request", lat, lon, accuracy?, battery?,
 *         zoneId?, source? ("child" | "parent", solo per
 *         "location_request"), batteryTemp?, charging?, speed?,
 *         timestamp? }
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
 * - 0.9.0 (2026-09-11): rimosso il "DEVICE_ID" hardcoded ("figlio") —
 *   ora supporta N bambini, il childId si risolve dal token
 *   (resolveDeviceId). Il testo delle notifiche ora include il nome
 *   del bambino (devices/{childId}.childName, fallback "Bambino") —
 *   con un solo figlio era implicito "di chi" si trattasse, con N non
 *   piu'. Le geofence restano lette da devices/{childId}/geofences per
 *   ora (diventeranno una collezione condivisa in una fase successiva,
 *   vedi CONTEXT.md).
 * - 0.10.0 (2026-09-11): la lettura della zona (nome + toggle
 *   notifica/allarme) ora punta alla nuova collezione radice
 *   "geofences" (vedi device-config.js v0.4.0 per la migrazione
 *   automatica) invece della vecchia subcollection
 *   devices/{childId}/geofences — quest'ultima poteva anche non
 *   esistere piu' se il watch aveva gia' sincronizzato dopo la
 *   migrazione. Nessun impatto se la zona non viene trovata: restano i
 *   default prudenti gia' in uso (notifica sempre, nessun allarme).
 * - 0.10.0 (2026-09-16): due ritocchi:
 *   (1) gli eventi scrivono ora "expiresAt" (retention 12 mesi, come locations),
 *   puliti dal cron in cleanup.js: era l'ultimo gruppo di collezioni senza limite;
 *   (2) le push ai genitori partono sul topic FCM "parents" invece di leggere
 *   l'intera collezione parents e iterare gli array fcmTokens a ogni evento:
 *   -1 lettura Firestore per notifica, token obsoleti smaltiti da FCM stesso.
 *   L'iscrizione al topic avviene lato phone-app (TrackerApplication.kt).
 * - 0.11.0 (2026-09-18): richiesto dall'utente — la notifica SOS sul
 *   telefono doveva poter suonare anche a telefono in silenzioso/DND,
 *   come gia' fa l'allarme di uscita zona (vedi ExitAlarmService.kt).
 *   Al primo "sos" di un episodio (stesso gate di shouldNotify, per non
 *   far ripartire l'allarme ad ogni ping di sos-heartbeat.js) viene ora
 *   mandata anche una seconda push data-only "sos_alarm" (stesso
 *   pattern di "exit_alarm" sotto: deve poter avviare un foreground
 *   Service anche ad app in background/uccisa). A differenza
 *   dell'allarme di uscita zona, che e' opt-in per-zona
 *   (alarmOnExit), l'SOS e' sempre un allarme sonoro: e' la funzione di
 *   sicurezza piu' critica dell'app.
 * - 0.12.0 (2026-09-18): aggiunti "batteryTemp"/"charging" al body
 *   (richiesti dall'utente — vedi watch-app/.../location/
 *   BatteryInfo.kt), scritti su devices/{childId} insieme al resto
 *   dello stato. Non aggiunti a sos-heartbeat.js (ping ogni 30" durante
 *   un SOS attivo): non cambiano in modo significativo in 30 secondi.
 * - 0.13.0 (2026-09-18): aggiunto "speed" (m/s, richiesto dall'utente
 *   per mostrarla sulla mappa), stesso pattern.
 * - 0.14.0 (2026-09-18): DND automatico per zona, richiesto dall'utente
 *   ("quando arriva a scuola va in dnd in automatico"). Nuovo campo
 *   opzionale sulla zona, "dndOnZone" (GeofenceScreen.kt), letto insieme
 *   agli altri toggle per-zona gia' esistenti. Se true: un
 *   "geofence_enter" risponde con { dnd: true } (il watch deve
 *   ATTIVARE il "Non disturbare" di sistema), un "geofence_exit"
 *   risponde con { dnd: false } (il watch deve DISATTIVARLO) — un solo
 *   flag per entrambe le direzioni, cosi' uscendo da scuola il DND si
 *   toglie sempre da solo, niente stato "acceso per sempre" se il
 *   genitore dimentica un secondo toggle separato. Il campo e' assente
 *   (non "false") per zone con dndOnZone!=true o per tipi diversi da
 *   geofence_enter/exit: il watch (GeofenceEventWorker.kt) non tocca il
 *   DND in quel caso, invece di doverlo interpretare come "disattivalo".
 *   Il cambio effettivo avviene interamente lato watch (e' un'impostazione
 *   di sistema locale, NotificationManager.setInterruptionFilter — non
 *   ha senso passare dal telefono/push, il watch riceve gia' l'esito
 *   della propria chiamata trigger-event).
 * - 0.15.0 (2026-09-19): richiesto dall'utente — notifiche automatiche
 *   al genitore quando la batteria del watch scende al 10%/5%, con
 *   richiesta posizione + messaggio automatico al watch al 2% (vedi
 *   nuovo _lib/batteryAlerts.js). Riusa deviceSnapBefore, gia' letto
 *   sopra prima della scrittura del batch, invece di una seconda
 *   lettura Firestore.
 * - 0.16.0 (2026-09-19): aggiunto "batteryHoursRemaining" al body
 *   (richiesto dall'utente — autonomia residua in ore, chiesta dal
 *   watch al proprio sistema operativo, vedi watch-app/.../location/
 *   BatteryInfo.kt), salvato su devices/{childId} insieme al resto
 *   dello stato batteria, stesso pattern di batteryTemp/charging/speed.
 * - 0.17.0 (2026-09-22): bug segnalato dall'utente (vedi
 *   ingest-location.js v0.8.0, stesso problema) — lo stato "attuale"
 *   del device (lastLocation/lastSeen/batteria/ecc.) veniva
 *   sovrascritto in modo incondizionato, potendo regredire "lastSeen"
 *   all'indietro se una chiamata con dati piu' vecchi completa dopo
 *   una piu' recente (es. ritentativi con connettivita' ballerina). Ora
 *   scritto dentro una transazione che confronta il timestamp con
 *   "lastSeen" gia' salvato e salta l'aggiornamento se non e' piu'
 *   recente. "sosActive" resta sempre marcato true su un "sos" a
 *   prescindere dalla freschezza del fix (flag di sicurezza, non un
 *   dato di posizione).
 * - 0.18.0 (2026-09-23): Fase 2 di qwen_plan.md (individuato da
 *   qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — le tre push
 *   (notifica normale, exit_alarm, sos_alarm) partivano sul topic FCM
 *   globale "parents": qualunque telefono di qualunque famiglia
 *   iscritto riceveva/sentiva suonare l'allarme di un bambino non
 *   proprio. Ora ognuna parte sul topic per-bambino
 *   "child-<childId>" (vedi _lib/fcmTopics.js), a cui la phone-app si
 *   iscrive solo per i propri figli (TrackerApplication.kt/
 *   AppViewModel.kt).
 * - 0.19.0 (2026-09-23): nuovo type "status", SENZA lat/lon: segnalato
 *   dall'utente che se il watch non ottiene la posizione la phone-app
 *   non mostra piu' nemmeno batteria e temperatura aggiornate (il
 *   watch le mandava solo insieme a un fix). Aggiorna solo battery/
 *   batteryTemp/charging/batteryHoursRemaining + "lastStatusAt" su
 *   devices/{childId}; NON tocca lastLocation/lastSeen (restano
 *   "ultima posizione"), nessun documento evento, nessuna push. Conta
 *   1 nella quota come gli altri tipi non-SOS.
 * - 0.20.0 (2026-09-23): campi opzionali satsVisible/satsUsed (satelliti
 *   visti/agganciati durante il tentativo di posizione del watch,
 *   richiesta utente) salvati in devices/{childId}.gnss {visible, used,
 *   at} sia per "status" sia per gli altri tipi. Fuori dalla guardia di
 *   freschezza di lastSeen: descrivono l'ultimo tentativo, riuscito o no.
 * - 0.21.0 (2026-09-23): tre correzioni.
 *   (1) BUG: il watch manda gli eventi geofence con battery=null (e
 *   lat/lon 0,0 se Android non fornisce la posizione dell'evento): qui
 *   ogni campo veniva scritto comunque ("battery ?? null"), cancellando
 *   batteria/temperatura/carica sul device a ogni ingresso/uscita zona
 *   (la phone-app smetteva di mostrarle) e spostando "ultima posizione"
 *   a 0,0. Ora si aggiornano solo i campi presenti; lastLocation/
 *   lastSeen/speed solo con coordinate valide diverse da 0,0.
 *   (2) Richiesta utente: lo "status" senza posizione fa scattare anche
 *   gli avvisi di batteria scarica (checkBatteryAlerts).
 *   (3) gnssActive=false dal watch (posizione ottenuta da Wi-Fi/rete
 *   senza accendere il GPS): salvato come gnss {active:false}.
 * - 0.22.0 (2026-09-23): richiesta utente — avviso quando il watch va in
 *   modalita' aereo o si spegne, con icona sulla phone-app. Lo "status"
 *   accetta watchState ("airplane_on" | "airplane_off" | "shutdown" |
 *   "boot"), stateAt (ms, quando e' successo) e since (ms, inizio del
 *   periodo offline, per airplane_off/boot). Salva
 *   devices/{id}.watchState {state: "airplane"|"off"|"online", event,
 *   at, since}, un evento "watch_<watchState>" nello storico e una push
 *   al topic del bambino. Limite: entrando in modalita' aereo il watch
 *   perde la rete quasi subito, quindi "airplane_on"/"shutdown" arrivano
 *   solo se partono in tempo; "airplane_off"/"boot" arrivano al rientro
 *   e ricostruiscono comunque il periodo offline.
 * - 0.23.0 (2026-09-24): nuovo campo lastNoFixAt, scritto solo da uno
 *   "status" SENZA watchState (= il watch ha tentato il fix e non l'ha
 *   ottenuto, LocationRequestWorker). La phone-app lo usa per capire che
 *   una richiesta di posizione e' fallita; prima usava lastStatusAt, che
 *   si aggiorna anche con gli avvisi di modalita' aereo/riaccensione: al
 *   riavvio del watch l'avviso "boot" veniva scambiato per un tentativo
 *   fallito mentre la posizione stava arrivando (segnalato dall'utente).
 * - 0.24.0 (2026-09-24): richiesta utente, icona "in carica" sulla
 *   phone-app. Lo "status" accetta reason: "power" (inviato dal watch
 *   quando si collega/scollega il caricatore, WatchStateReporter): aggiorna
 *   batteria/charging e lastStatusAt ma NON lastNoFixAt (non e' un
 *   tentativo di fix fallito) e non tocca watchState. Nessuna push e
 *   nessun evento nello storico (solo l'icona).
 * - 0.25.0 (2026-09-25): gnssUpdate accetta i soli satelliti agganciati
 *   (visti null) e "GPS acceso" senza numeri (vedi gnssUpdate).
 */
const { getFirestore, Timestamp, FieldValue } = require("firebase-admin/firestore");
const { wrapHandler, errorResponse, successResponse, logError } = require("./_lib/errors.js");
const { getMessaging } = require("firebase-admin/messaging");
const { getAdminApp } = require("./_lib/firebase-admin");
const { resolveDeviceId } = require("./_lib/auth");
const { checkAndConsumeQuota } = require("./_lib/quota");
const { checkBatteryAlerts } = require("./_lib/batteryAlerts.js");
const { childTopic } = require("./_lib/fcmTopics.js");

const VALID_TYPES = new Set(["sos", "geofence_enter", "geofence_exit", "location_request"]);
// Retention eventi, stesso valore di HISTORY_RETENTION_HOURS in ingest-location.js.
const EVENT_RETENTION_HOURS = 24 * 365;

// v0.20.0: {visible, used, at} se il watch ha mandato i satelliti, altrimenti null.
// v0.21.0: gnssActive=false -> GPS non usato (posizione da rete).
// v0.25.0 (2026-09-25): basta satsUsed (visible puo' mancare: il watch in
// background ha solo i satelliti del fix GPS, non quelli visti), e
// gnssActive=true senza numeri viene salvato comunque (GPS acceso, numero
// ignoto) invece di lasciare il dato precedente.
// Precedente (v0.21.0):
//   if (typeof satsVisible !== "number" || typeof satsUsed !== "number") return null;
//   return { active: true, visible: satsVisible, used: satsUsed, at: FieldValue.serverTimestamp() };
function gnssUpdate(satsVisible, satsUsed, gnssActive) {
  if (gnssActive === false) {
    return { active: false, visible: null, used: null, at: FieldValue.serverTimestamp() };
  }
  const visible = typeof satsVisible === "number" ? satsVisible : null;
  const used = typeof satsUsed === "number" ? satsUsed : null;
  if (gnssActive !== true && used === null) return null;
  return { active: true, visible, used, at: FieldValue.serverTimestamp() };
}

// v0.22.0: stati del watch accettati nello "status" e loro traduzione.
const WATCH_STATES = {
  airplane_on: "airplane",
  shutdown: "off",
  airplane_off: "online",
  boot: "online",
};

function formatTimeIt(ts) {
  return ts.toDate().toLocaleTimeString("it-IT", { timeZone: "Europe/Rome", hour: "2-digit", minute: "2-digit" });
}

function buildWatchStateNotification(watchState, childName, since) {
  const sinceText = since ? ` dalle ${formatTimeIt(since)}` : "";
  switch (watchState) {
    case "airplane_on":
      return {
        title: `✈️ ${childName}: watch in modalita' aereo`,
        body: "Non ricevera' richieste ne' inviera' posizioni finche' non viene disattivata.",
      };
    case "shutdown":
      return { title: `⏻ ${childName}: watch in spegnimento`, body: "Il watch si sta spegnendo." };
    case "airplane_off":
      return { title: `${childName}: watch di nuovo raggiungibile`, body: `Modalita' aereo disattivata (era attiva${sinceText}).` };
    default:
      return { title: `${childName}: watch riacceso`, body: `Il watch si e' riacceso (era spento${sinceText}).` };
  }
}

// v0.21.0: solo i campi batteria effettivamente inviati dal watch.
function batteryFields(battery, batteryTemp, charging, batteryHoursRemaining) {
  const out = {};
  if (typeof battery !== "number") return out;
  out.battery = battery;
  if (typeof batteryTemp === "number") out.batteryTemp = batteryTemp;
  if (typeof charging === "boolean") out.charging = charging;
  // Con battery presente, null = stima non disponibile (es. in carica).
  out.batteryHoursRemaining = typeof batteryHoursRemaining === "number" ? batteryHoursRemaining : null;
  return out;
}

function buildNotification(type, childName, zoneName, source) {
  if (type === "sos") {
    return {
      title: `🆘 SOS — ${childName}`,
      body: "Premuto il pulsante SOS sul watch. Posizione aggiornata.",
    };
  }
  if (type === "geofence_enter") {
    return { title: `Ingresso zona — ${childName}`, body: `Entrato in "${zoneName}".` };
  }
  if (type === "geofence_exit") {
    return { title: `Uscita zona — ${childName}`, body: `Uscito da "${zoneName}".` };
  }
  // "location_request": source distingue un invio manuale del bambino
  // (pulsante sul watch) da una richiesta remota del genitore
  // ("Aggiorna posizione" sulla phone-app) — prima la notifica diceva
  // sempre "il bambino ha inviato la posizione", anche quando l'aveva
  // chiesta il genitore stesso.
  if (source === "parent") {
    return { title: `Posizione aggiornata — ${childName}`, body: "Posizione aggiornata su tua richiesta." };
  }
  return {
    title: `Posizione aggiornata — ${childName}`,
    body: `${childName} ha inviato la posizione attuale.`,
  };
}

// Rimossa fetchParentFcmTokens (leggeva l'intera collezione parents a
// ogni evento). L'invio avviene sul topic per-bambino "child-<childId>"
// (vedi _lib/fcmTopics.js/Storico versioni v0.18.0): gli array
// fcmTokens su parents/{uid} restano scritti dalla phone-app
// (inutilizzati per l'invio, utili solo al debug).

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

  // v0.24.0: aggiunto "reason" (vedi Storico versioni).
  // Precedente (2026-09-24): stessa destrutturazione senza "reason".
  const { type, lat, lon, accuracy, battery, zoneId, source, batteryTemp, charging, speed, batteryHoursRemaining, timestamp, satsVisible, satsUsed, gnssActive, watchState, stateAt, since, reason } = req.body || {};
  const gnss = gnssUpdate(satsVisible, satsUsed, gnssActive);

  // v0.19.0: stato batteria senza posizione (vedi Storico versioni).
  if (type === "status") {
    const allowed = await checkAndConsumeQuota(db, childId);
    if (!allowed) {
      res.status(429).send("Too Many Requests: limite giornaliero di sicurezza raggiunto");
      return;
    }
    const statusRef = db.collection("devices").doc(childId);
    const update = { lastStatusAt: FieldValue.serverTimestamp(), ...batteryFields(battery, batteryTemp, charging, batteryHoursRemaining) };
    if (gnss) update.gnss = gnss;
    // v0.22.0: modalita' aereo / spegnimento / rientro (vedi Storico versioni).
    const hasWatchState = Object.prototype.hasOwnProperty.call(WATCH_STATES, watchState);
    const stateTs = typeof stateAt === "number" ? Timestamp.fromMillis(stateAt) : Timestamp.now();
    const sinceTs = typeof since === "number" ? Timestamp.fromMillis(since) : null;
    if (hasWatchState) {
      update.watchState = { state: WATCH_STATES[watchState], event: watchState, at: stateTs, since: sinceTs };
    } else if (reason !== "power") {
      // v0.23.0: tentativo di fix non riuscito (vedi Storico versioni).
      // v0.24.0: escluso reason "power" (caricatore collegato/scollegato).
      // Precedente (2026-09-24): } else { update.lastNoFixAt = ... }
      update.lastNoFixAt = FieldValue.serverTimestamp();
    }
    await statusRef.set(update, { merge: true });
    if (hasWatchState) {
      await statusRef.collection("events").add({
        type: `watch_${watchState}`,
        since: sinceTs,
        timestamp: stateTs,
        acknowledged: true,
        expiresAt: Timestamp.fromMillis(stateTs.toMillis() + EVENT_RETENTION_HOURS * 3_600_000),
      });
      const childName = (await statusRef.get()).data()?.childName || "Bambino";
      const { title, body } = buildWatchStateNotification(watchState, childName, sinceTs);
      try {
        await getMessaging().send({
          topic: childTopic(childId),
          notification: { title, body },
          data: { type: "watch_state", watchState, childId },
          android: { priority: "high" },
        });
      } catch (e) {
        console.error(`trigger-event: push watchState fallita (childId=${childId})`, e);
      }
    }
    // v0.21.0: avvisi di batteria scarica anche senza posizione (richiesta utente).
    if (typeof battery === "number") {
      await checkBatteryAlerts(db, statusRef, childId, battery, typeof charging === "boolean" ? charging : null);
    }
    res.status(200).json({ ok: true });
    return;
  }
  if (!VALID_TYPES.has(type) || typeof lat !== "number" || typeof lon !== "number") {
    res.status(400).send("Bad Request: 'type'/'lat'/'lon' mancanti o non validi");
    return;
  }

  const deviceRef = db.collection("devices").doc(childId);

  if (type !== "sos") {
    const allowed = await checkAndConsumeQuota(db, childId);
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
  let dndOnZone = false;
  if (type === "geofence_enter" || type === "geofence_exit") {
    const zoneSnap = zoneId ? await db.collection("geofences").doc(zoneId).get() : null;
    const zone = zoneSnap?.exists ? zoneSnap.data() : null;
    if (zone) {
      zoneName = zone.name || zoneName;
      notifyOnEnter = zone.notifyOnEnter !== false;
      notifyOnExit = zone.notifyOnExit !== false;
      alarmOnExit = zone.alarmOnExit === true;
      dndOnZone = zone.dndOnZone === true;
    }
  }

  const ts = timestamp ? Timestamp.fromMillis(timestamp) : Timestamp.now();

  // Serve PRIMA della scrittura per sapere se questo e' il primo "sos"
  // dell'episodio (e quindi se notificare) o un evento successivo. Lo
  // stesso get() serve anche per il nome del bambino nel testo della
  // notifica (vedi storico versioni v0.9.0).
  const deviceSnapBefore = await deviceRef.get();
  const wasSosActive = type === "sos" ? deviceSnapBefore.data()?.sosActive === true : false;
  const childName = deviceSnapBefore.data()?.childName || "Bambino";

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
    // Letto da cleanup.js/purgeExpired
    expiresAt: Timestamp.fromMillis(ts.toMillis() + EVENT_RETENTION_HOURS * 3_600_000),
  });
  await batch.commit();

  // Lo stato "attuale" del device (lastLocation/lastSeen/batteria/ecc.)
  // si scrive dentro una transazione, non incondizionatamente: se
  // questa chiamata trasporta un fix piu' vecchio di quanto gia'
  // salvato (es. una richiesta rimasta in coda/ritentata con
  // connettivita' ballerina, arrivata dopo una piu' recente), non deve
  // regredire "lastSeen" all'indietro — stesso bug e stessa correzione
  // di ingest-location.js v0.8.0. "sosActive" invece va sempre marcato
  // true per un "sos", indipendentemente dalla freschezza del fix: e'
  // un flag di sicurezza, non un dato di posizione da proteggere.
  let updatedCurrentState = false;
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(deviceRef);
    const currentLastSeen = snap.exists ? snap.get("lastSeen") : null;
    const isFresh = !currentLastSeen || ts.toMillis() > currentLastSeen.toMillis();

    const update = {};
    // v0.21.0: solo i campi presenti (vedi Storico versioni). Precedente:
    //   update.lastLocation = { lat, lon, accuracy: accuracy ?? null };
    //   update.battery = battery ?? null; ... (tutti i campi, anche null)
    //   update.lastSeen = ts;
    const hasCoords = !(lat === 0 && lon === 0);
    if (isFresh) {
      if (hasCoords) {
        update.lastLocation = { lat, lon, accuracy: accuracy ?? null };
        update.speed = speed ?? null;
        update.lastSeen = ts;
      }
      Object.assign(update, batteryFields(battery, batteryTemp, charging, batteryHoursRemaining));
      update.updatedAt = FieldValue.serverTimestamp();
      updatedCurrentState = true;
    }
    if (type === "sos") {
      update.sosActive = true;
    }
    if (Object.keys(update).length > 0) {
      tx.set(deviceRef, update, { merge: true });
    }
  });
  // v0.20.0: satelliti dell'ultimo tentativo, fuori dalla guardia di freschezza.
  if (gnss) await deviceRef.set({ gnss }, { merge: true });

  if (typeof battery === "number" && updatedCurrentState) {
    await checkBatteryAlerts(db, deviceRef, childId, battery, charging ?? null, deviceSnapBefore.data());
  }

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
  // v0.11.0: vedi Storico versioni sopra — l'SOS suona sempre, non e'
  // opt-in come l'allarme di uscita zona.
  const shouldSosAlarm = type === "sos" && shouldNotify;

  if (shouldNotify || shouldAlarm || shouldSosAlarm) {
    // v0.18.0: invio sul topic per-bambino, non piu' sul topic globale
    // "parents" (vedi Storico versioni sopra) — nessun array di token
    // da leggere/controllare.
    const topic = childTopic(childId);
    {
      if (shouldNotify) {
        const { title, body } = buildNotification(type, childName, zoneName, source);
        await getMessaging().send({
          topic,
          notification: { title, body },
          data: { type, lat: String(lat), lon: String(lon), childId },
          android: { priority: "high" },
        });
      }
      if (shouldAlarm) {
        // Data-only (nessun campo "notification"): deve poter avviare
        // ExitAlarmService anche ad app in background/uccisa, non solo
        // mostrare una notifica passiva quando l'utente la tocca (vedi
        // storico versioni sopra).
        await getMessaging().send({
          topic,
          data: { type: "exit_alarm", zoneName, childId },
          android: { priority: "high" },
        });
      }
      if (shouldSosAlarm) {
        // v0.11.0: stesso motivo di "exit_alarm" sopra — data-only per
        // poter avviare SosAlarmService anche ad app in
        // background/uccisa (vedi phone-app/.../alarm/SosAlarmService.kt).
        await getMessaging().send({
          topic,
          data: { type: "sos_alarm", childName, childId },
          android: { priority: "high" },
        });
      }
    }
  }

  // v0.14.0: vedi Storico versioni sopra — dndAction e' undefined (il
  // client JSON.stringify lo omette dalla risposta) quando la zona non
  // ha dndOnZone attivo o il tipo non e' una transizione geofence,
  // cosi' il watch sa distinguere "nessuna azione DND richiesta" da
  // "disattiva il DND" (false esplicito).
  const dndAction =
    dndOnZone && (type === "geofence_enter" || type === "geofence_exit")
      ? type === "geofence_enter"
      : undefined;

  res.status(200).json({ ok: true, dnd: dndAction });
});
