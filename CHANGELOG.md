# Changelog

Tutte le modifiche rilevanti al progetto sono documentate in questo file.

Formato basato su [Keep a Changelog](https://keepachangelog.com/it/1.0.0/),
versionamento secondo [Semantic Versioning](https://semver.org/lang/it/).

## [Unreleased]

## [0.70.0] - 2026-09-23

### Fixed
- **phone-app: versione ferma nonostante codice nuovo**. L'utente ha
  chiesto quale versione della phone-app fosse in produzione, non
  essendone certo — verificando è emerso che il commit della Fase 1
  (isolamento famiglie, v0.68.0) aveva aggiunto la sezione "Genitori"
  in `SettingsScreen.kt` (invito/accettazione famiglia) e il relativo
  codice in `AppViewModel.kt`/`BackendClient.kt`/`DeviceRepository.kt`
  senza incrementare `versionCode`/`versionName`, rimasti a 13/"0.13.0"
  come prima della modifica — un rebuild sarebbe stato indistinguibile
  dal precedente. Portato a `versionCode = 14` / `versionName = "0.14.0"`.

## [0.69.0] - 2026-09-23

### Fixed
- **Timestamp sbagliato sugli eventi geofence in caso di retry**.
  Segnalato dall'utente: una mattina l'evento "uscito da zona casa"
  non è mai arrivato, mentre gli eventi "entrato a scuola" dello
  stesso giorno erano tutti concentrati dopo le 13 — indagando è
  emerso che `GeofenceEventWorker.kt` non passava mai l'orario reale
  della transizione a `triggerEvent()`: se il primo invio falliva
  (rete assente) e WorkManager ritentava più tardi, l'evento veniva
  datato al momento del retry riuscito, non del passaggio di confine
  vero — fuorviante per capire cosa fosse successo davvero e quando.
  `GeofenceBroadcastReceiver.kt` ora cattura l'orario al momento del
  rilevamento e lo passa al worker (`KEY_TIMESTAMP`), che lo inoltra
  al backend invece di lasciare il default "adesso" del client.

## [0.68.0] - 2026-09-22

### Added
- **Isolamento tra famiglie diverse sullo stesso deployment**
  (individuato da qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5).
  Prima `checkParentAuth()` verificava solo che `parents/{uid}`
  esistesse: qualunque genitore autenticato poteva leggere/comandare
  QUALSIASI bambino di QUALSIASI famiglia sullo stesso progetto
  Firebase — `cancel_sos` incluso (silenziare l'SOS attivo di un
  bambino non proprio). Bug di sicurezza reale, non solo teorico, non
  appena una seconda famiglia avesse installato l'app. Aggiunto un
  campo `familyId` su `parents/{uid}`/`devices/{childId}`/
  `geofences/{zoneId}`: `firestore.rules` (v0.7.0) ora richiede che
  `familyId` combaci per leggere/scrivere un documento;
  `parent-command.js` (v0.4.0) verifica la stessa cosa lato server
  prima di ogni azione con un `childId` esplicito (le regole non
  proteggono le chiamate Admin SDK). `familyId` non è mai scrivibile
  dal client (altrimenti basterebbe indovinare/forzare l'id di
  un'altra famiglia): lo scrive solo il backend, alla creazione del
  primo bambino (`create_child`, self-heal) o accettando un invito.
- Nuove azioni `create_family_invite`/`accept_family_invite` in
  `parent-command.js` per collegare un secondo genitore alla stessa
  famiglia (codice a singolo uso, TTL 24h, collezione
  `familyInvites` backend-only) — nuova sezione "Genitori" in
  `SettingsScreen.kt` (phone-app) per generarlo/inserirlo.
- Nuovo script one-time `backend/scripts/migrate-family-ids.js`:
  assegna un `familyId` a tutti i documenti pre-esistenti (oggi una
  sola famiglia reale) — da eseguire PRIMA di pubblicare
  `firestore.rules` v0.7.0.

### Fixed
- **Migrazione geofence legacy** (`device-config.js`, individuato da
  qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5): la subcollection
  legacy non viene mai svuotata dopo la copia, quindi
  `ensureGeofencesMigrated` si ripete a OGNI chiamata dell'endpoint,
  non solo alla prima. Scriveva `childIds: [childId]` con
  `merge:true` — su un campo array il merge di Firestore SOSTITUISCE
  il valore invece di unirlo: ogni sync del watch di un bambino con
  residui nella subcollection legacy azzerava i `childIds` di quella
  zona, staccandola da altri bambini a cui era stata assegnata nel
  frattempo. Sostituito con `FieldValue.arrayUnion(childId)`.

## [0.67.0] - 2026-09-22

### Changed
- Rimossi dal repo tutti i riferimenti nominali all'AI locale
  dell'utente (tag "qwen3.8turbo-coder"/"qwen3.8-Flash-Next", vedi
  v0.42.0) — richiesto dall'utente. Le spiegazioni tecniche nei
  commenti/Storico versioni sono state mantenute, solo il nome/data di
  attribuzione è stato tolto. Eliminato `PLAN-qwen3.8turbo-coder.md`
  (piano interamente relativo a quella sessione, superato da
  CONTEXT.md); da `TESTING-E2E.md` tolte inizialmente solo le due
  righe di attribuzione, mantenuta la procedura di test — poi
  l'utente stesso ha cancellato il file per intero con un commit
  diretto sul branch (autore ancora taggato "qwen3.8turbo-coder",
  suo strumento locale) mentre questa sessione era ancora al lavoro,
  cancellazione accettata in fase di merge. Le voci storiche di questo
  CHANGELOG e di CONTEXT.md sul relativo incidente (v0.42.0) restano
  come promemoria dei fatti, col nome dell'AI anonimizzato.
  sessione.

## [0.66.0] - 2026-09-22

### Added
- All'apertura della phone-app, richiesta automatica della posizione a
  tutti i bambini noti — stessa chiamata del pulsante "Aggiorna
  posizione" (`AppViewModel.requestLocation`), una sola volta per
  apertura. Richiesto dall'utente.
- "Ultima posizione" nella `StatusCard` mostra ora anche data e ora
  assolute oltre al relativo ("5 min fa · 22/09 14:35"), richiesto
  dall'utente per capire "di che giorno" quando il dato è vecchio di
  ore.

## [0.65.0] - 2026-09-22

### Fixed
- Segnalato dall'utente: la StatusCard mostrava "ultima posizione 5
  ore fa" anche quando lo storico ("Percorso 24h") aveva già punti
  molto più recenti. Causa: `ingest-location.js`/`trigger-event.js`
  sovrascrivevano lo stato "attuale" del device
  (`lastLocation`/`lastSeen`/batteria/ecc.) in modo **incondizionato**
  ad ogni chiamata — con connettività instabile (es. a scuola), due
  upload potevano restare in volo insieme e, se quello con dati più
  vecchi completava dopo quello con dati più freschi, "lastSeen"
  regrediva all'indietro nel tempo. I singoli punti nello storico non
  erano mai coinvolti (ogni punto è un documento a sé, mai
  sovrascritto), da cui la discrepanza tra le due viste.
  - Lo stato "attuale" ora si scrive dentro una transazione Firestore
    che confronta il nuovo timestamp con l'ultimo `lastSeen` salvato e
    salta l'aggiornamento se non è più recente.
  - Le notifiche di batteria scarica (10%/5%/2%) ora valutano solo
    dati effettivamente più freschi di quelli già noti, per lo stesso
    motivo.
  - `sosActive` resta sempre marcato `true` su un evento SOS a
    prescindere dalla freschezza del fix di posizione: è un flag di
    sicurezza, non va mai protetto dalla stessa logica.

## [0.64.0] - 2026-09-19

### Fixed
- Il workflow GitHub Actions "Pulizia storico backend" (`cleanup-cron.yml`)
  falliva ogni notte con HTTP 500, segnalato dall'utente. Causa: la
  pulizia del gruppo di collezioni `events` (aggiunta a `cleanup.js` il
  16/9) interroga Firestore con `collectionGroup("events")`, ma
  `firestore.indexes.json` non aveva mai avuto il corrispondente
  override di indice a scope "Collection group" per `events.expiresAt`
  (presenti solo per `locations`/`quota`/`messages`) — Firestore
  rifiuta la query, l'errore viene inghiottito dal gestore generico e
  restituito come "Internal server error" senza dettagli.
  - Aggiunto l'override mancante in `firestore.indexes.json`.
  - **Il file nel repo da solo non basta**: va ripubblicato su
    Firestore (Console o `firebase deploy --only firestore:indexes`),
    stessa classe di problema già vista con le regole di sicurezza
    (vedi CONTEXT.md, "Distribuzione app"/log decisioni).

## [0.63.0] - 2026-09-19

### Added
- Autonomia residua della batteria dello smartwatch, mostrata in ore/minuti
  sotto la percentuale batteria nella `StatusCard` della phone-app,
  richiesta dall'utente. Chiesta direttamente al sistema operativo del
  watch (`BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER` /
  `BATTERY_PROPERTY_CURRENT_NOW`, `capacità residua / corrente di
  scarica = ore`), non stimata da uno storico lato app: stesso dato
  grezzo che usa Android per le proprie stime di batteria, disponibile
  subito senza accumulare campioni.
  - `watch-app`: nuovo `BatteryInfo.readHoursRemaining()`, con un
    controllo di plausibilità (0.1h–100h) per scartare letture assurde
    su kernel/dispositivi che espongono il dato in modo inaffidabile —
    in quel caso nessuna stima invece di un numero sbagliato. Valore
    assente mentre il watch è in carica. Propagato in
    `LocationPoint`/`triggerEvent()` come per gli altri campi batteria.
  - `backend`: nuovo campo `batteryHoursRemaining` su
    `devices/{childId}`, scritto da `ingest-location.js`/
    `trigger-event.js` come per `batteryTemp`/`charging`/`speed`.
  - `phone-app`: nuova riga "Autonomia residua: ~Xh Ymin" in
    `StatusCard`, visibile solo quando il dato è disponibile.

## [0.62.0] - 2026-09-19

### Added
- Notifiche automatiche al genitore quando la batteria dello smartwatch
  scende al 10% e al 5%, richiesto dall'utente. Al 2% viene inoltre
  forzata una richiesta di posizione al watch e inviato automaticamente
  un messaggio in chat al watch con testo fisso "non hai più batteria,
  aspettami dove sei.".
  - Nuovo `backend/api/_lib/batteryAlerts.js` (helper condiviso, non un
    endpoint a se': Vercel Hobby permette max 12 Serverless Function,
    gia' a 10 in `backend/api/`), richiamato da `ingest-location.js` e
    `trigger-event.js` dopo la loro scrittura dello stato batteria.
  - Nuovo campo `devices/{childId}.batteryAlertLevel` per non
    rinotificare la stessa soglia ad ogni campione mentre la batteria
    resta bassa; si resetta quando il watch torna in carica o supera il
    15% di batteria.
  - Nessun codice nuovo lato phone-app/watch-app: riusa il fallback
    notifica generico gia' in `FcmService.kt` (phone) e la gestione gia'
    esistente di `chat`/`location_request` in `FcmService.kt` (watch).

### Known limitations
- Autonomia residua in ore sotto la percentuale batteria nella
  `StatusCard` (phone-app): analizzata su richiesta dell'utente, non
  ancora implementata. Richiede calcolare una velocita' di scarica
  (%/ora) dallo storico `devices/{childId}/locations` (gia' contiene
  `battery`+`timestamp` per punto) — stima solo indicativa, non
  precisa (dipende da uso schermo/GPS/LTE nel frattempo).

## [0.61.0] - 2026-09-18

### Added
- DND (Non disturbare) automatico per zona, richiesto dall'utente
  ("quando arriva a scuola va in dnd in automatico"). Nuovo toggle
  per-zona "Non disturbare (watch) in questa zona" in `GeofenceScreen`
  (phone-app): quando attivo, il watch attiva il "Non disturbare" di
  sistema entrando nella zona e lo disattiva uscendo — un solo flag per
  entrambe le direzioni, cosi' non resta mai "acceso per sempre" se ci
  si dimentica di disattivarlo a mano.
  - `backend/api/trigger-event.js` risolve il nuovo campo zona
    `dndOnZone` (stessa lettura gia' fatta per notifyOnEnter/
    notifyOnExit/alarmOnExit) e lo restituisce nella risposta come
    `dnd: true|false` (assente se nessuna azione richiesta) — nessuna
    chiamata di rete aggiuntiva, riusa la risposta della stessa
    trigger-event gia' mandata dal watch per notificare la transizione.
  - watch-app: nuovo `dnd/DndController.kt`, applica il cambio con
    `NotificationManager.setInterruptionFilter()`. Richiede il permesso
    speciale `ACCESS_NOTIFICATION_POLICY`, che Android non permette di
    concedere via codice — se manca, mostra una notifica con un tasto
    diretto alla schermata di sistema per concederlo, invece di fallire
    in silenzio.
  - `BackendClient.triggerEvent()` (watch) ora ritorna
    `TriggerEventResult(ok, dnd)` invece di un semplice `Boolean`.

### Changed
- phone-app build.gradle.kts v0.10.0 -> v0.11.0, watch-app
  build.gradle.kts v0.8.0 -> v0.9.0.

## [0.60.0] - 2026-09-18

### Changed
- phone-app: StatusCard (mappa) — i dettagli del bambino (ultima
  posizione, batteria, temperatura batteria, velocita') erano tutti su
  un'unica riga separati da "·", richiesto dall'utente un formato piu'
  leggibile: ora un'informazione per riga, etichetta normale + valore
  in grassetto ("Ultima posizione: **5 min fa**", "Batteria: **89%**",
  ecc.). `formatRelativeTime()` non include piu' il prefisso "Ultima
  posizione ricevuta" (ora fornito dall'etichetta della riga), ritorna
  solo la parte relativa ("5 min fa", "adesso", "2 h fa", ...).

### Fixed
- phone-app: rimossa la soglia minima di 2 km/h sotto cui la riga
  "Velocita'" spariva del tutto — con il nuovo formato a righe fisse
  non ha piu' senso nascondere il dato quando disponibile, anche se
  vicino allo zero.

### Known limitations
- La velocita' resta assente quando il watch non ha ancora inviato un
  fix GPS con `Location.hasSpeed()==true` (tipicamente serve un minimo
  di movimento reale, non solo un fix stazionario) — comportamento
  atteso lato Android, non un bug applicativo.

## [0.59.0] - 2026-09-18

### Fixed
- phone-app: dopo la pubblicazione manuale delle regole Firestore
  aggiornate (v0.6.0, confermata dall'utente — le zone ora sono
  visibili), tre correzioni UI su `GeofenceScreen`:
  - Campo ricerca indirizzo: rimossa la label fluttuante ("Cerca un
    indirizzo") a favore di un placeholder e spostata la lente di
    ingrandimento dentro il campo come icona (trailingIcon), eliminando
    la riga separata col pulsante testuale "Cerca" sotto — card di
    ricerca piu' bassa, un solo controllo invece di due.
  - Righe della lista zone compattate (meno padding verticale) e
    aggiunta una scrollbar verticale disegnata a mano (nessuna API
    scrollbar nativa per Android in questa versione di Compose
    Material3) per segnalare visivamente altre zone fuori schermo.
  - Switch "Allarme sonoro all'uscita" (nel pannello di
    creazione/modifica zona) risultava disallineato/tagliato a bordo
    schermo: causa, la Column di label+hint non aveva un vincolo di
    larghezza (niente `Modifier.weight()` disponibile in questa
    versione di Compose) nella stessa Row dello switch, e un hint lungo
    la spingeva fuori dai margini. Risolto con lo stesso pattern gia'
    usato per il pulsante "Aggiorna posizione" schiacciato in
    `MapScreen.kt` (v0.58.0): due righe impilate invece di una sola.

## [0.58.0] - 2026-09-18

### Fixed
- phone-app: pulsante "Aggiorna posizione" nella StatusCard renderizzato
  "schiacciato" (testo a capo lettera per lettera), segnalato
  dall'utente su screenshot v0.7.0. Causa: nome bambino + dettagli
  (ora piu' lunghi con temperatura/carica/velocita' aggiunti in
  v0.56.0/v0.57.0) e pulsante condividevano la stessa `Row`, e
  `Modifier.weight()` non e' utilizzabile in questa versione di
  Compose (vincolo gia' documentato altrove nel progetto) per far
  restringere proporzionalmente la colonna dei dettagli. Risolto
  separando la card in due righe impilate: nome + pulsante in alto
  (corti, non competono per lo spazio), dettagli su una riga propria
  sotto, libera di andare a capo normalmente.
- phone-app: notifica di un messaggio in arrivo dal watch mostrava
  sempre il titolo generico "Messaggio dal watch", senza dire da quale
  bambino (richiesto dall'utente, utile ora con supporto N bambini).
  Il backend (`send-message.js` v0.4.0) gia' includeva `senderName`
  nel payload push data-only; `FcmService.kt` ora lo usa per un titolo
  "Messaggio da {nickname}", con fallback sul vecchio testo generico
  se un payload piu' vecchio ne fosse privo.

### Known limitations
- Da confermare dall'utente su hardware reale: se il rebuild/reinstall
  del solo phone-app (senza rebuild del watch-app, ancora fermo a
  v0.8.0 con il codice che invia batteria/temperatura/carica/velocita'
  gia' presente da v0.6.0/v0.7.0) sia la causa dei "nuovi dati assenti"
  segnalati — nessun bug di nome-campo trovato (chiavi JSON
  `batteryTemp`/`charging`/`speed` coerenti tra watch, backend e
  phone-app).

## [0.57.0] - 2026-09-18

### Fixed
- watch-app: banner "SOS inviato"/"posizione inviata" che ricompariva
  ad ogni avvio dell'app anche senza un invio davvero nuovo, segnalato
  dall'utente ("in realta' non arriva sul cellulare"). Causa:
  `observeWorkOutcomes()` (v0.7.0) osserva `getWorkInfosForUniqueWorkLiveData`,
  che emette subito, alla sottoscrizione, lo stato piu' recente gia'
  presente nel database di WorkManager — anche se concluso ore/giorni
  prima (un vecchio SOS di test). Il dedup (`lastNotified*WorkId`) era
  in memoria, azzerato ad ogni riavvio dell'app, quindi quella prima
  emissione "vecchia" superava sempre il controllo e ri-mostrava il
  Toast come se fosse un esito nuovo. Aggiunto un controllo di
  baseline: la primissima emissione dopo l'apertura dell'app, se gia'
  in uno stato finale, viene registrata come "gia' vista" senza Toast;
  se invece e' ancora in corso, il comportamento v0.7.0 resta invariato
  (si vede comunque l'esito quando arriva).

### Known limitations
- Zone geofence ancora segnalate assenti sulla phone-app nonostante il
  fix di migrazione (v0.49.0) e il fix di sincronizzazione (v0.54.0).
  Nuova ipotesi, non ancora confermabile da questa sessione (nessun
  accesso a Firestore/Firebase Console): gli eventi "Entrato in casa"
  confermano che la zona esiste gia' correttamente lato backend
  (letta via Admin SDK, che bypassa le regole) — se le regole
  Firestore pubblicate sul progetto reale sono ancora quelle
  precedenti al supporto multi-bambino (senza il match a livello
  radice `geofences/{zoneId}` aggiunto in v0.6.0/backend/firestore.rules),
  il client della phone-app verrebbe bloccato in silenzio nella
  lettura, mostrando una lista vuota nonostante i dati esistano.
  Verifica richiesta all'utente: ripubblicare `backend/firestore.rules`
  sul progetto Firebase (Console → Firestore → Regole, o
  `firebase deploy --only firestore:rules`).

## [0.56.0] - 2026-09-18

### Added
- Velocita' mostrata in `StatusCard` (phone-app), richiesta
  dall'utente. `Location.getSpeed()` (m/s) e' gia' incluso gratis in
  ogni fix GPS (nessun costo di batteria aggiuntivo al ritmo di
  campionamento attuale) — propagato da `LocationPoint`/`BackendClient`
  (watch-app) a `ingest-location.js`/`trigger-event.js` (nuovo campo
  "speed") fino a `DeviceState` (phone-app), convertito in km/h solo
  in UI. Mostrata solo sopra 2 km/h (sotto e' rumore GPS, non
  movimento reale).

## [0.55.0] - 2026-09-18

### Added
- watch-app: nuovo `BatteryInfo.kt`, che centralizza lettura di
  percentuale batteria, **temperatura** e **stato di carica** (via
  intento sticky `ACTION_BATTERY_CHANGED`, nessun permesso/costo
  aggiuntivo) al posto di 4 copie duplicate di
  `currentBatteryPercent()` (solo percentuale) sparse in
  `LocationTrackingService`, `LocationRequestWorker`, `SosWorker`,
  `SosLocationService`. Richiesto dall'utente.
- backend: `ingest-location.js` e `trigger-event.js` accettano ora
  `batteryTemp`/`charging` nel body, salvati su `devices/{childId}`
  insieme al resto dello stato (non aggiunti a `sos-heartbeat.js`: non
  cambiano in modo significativo nei 30" fra un ping SOS e l'altro).
- phone-app: `StatusCard` mostra ora temperatura batteria (es. "31°C")
  e un'icona ⚡ quando il watch e' in carica, sulla stessa riga
  compatta introdotta in v0.54.0.

## [0.54.0] - 2026-09-18

### Added
- phone-app: allarme SOS che bypassa il silenzioso/DND, richiesto
  dall'utente. Riusa lo stesso pattern gia' collaudato dell'allarme di
  uscita zona (`ExitAlarmService`/`ExitAlarmActivity`): nuovo
  `SosAlarmService`/`SosAlarmActivity`, `AudioAttributes.USAGE_ALARM`
  (bypassa il volume suoneria come fanno le sveglie), full-screen
  intent sopra il lockscreen, vibrazione in loop, cap di sicurezza 10
  minuti. A differenza dell'allarme di uscita zona (opt-in per-zona),
  l'SOS suona sempre — `backend/api/trigger-event.js` manda ora anche
  una push data-only `sos_alarm` al primo SOS di un episodio (stesso
  gate anti-spam gia' in uso per la notifica normale).

### Changed
- phone-app: `StatusCard` (mappa) ridisegnata a riga singola larga
  quanto lo schermo, invece della riga scorrevole di card strette
  260dp — richiesto dall'utente dopo un secondo giro di test reale.
  Sostituita anche la `LazyRow` sottostante con una `Column` + forEach,
  che elimina alla radice il problema v0.52.0 (nessun bisogno di
  virtualizzazione con pochi bambini).
- phone-app: il colore soglia della batteria (verde/arancio/rosso) ora
  si applica solo al valore percentuale, non piu' anche all'etichetta
  "Batteria watch:" che stava nello stesso `Text` — segnalato
  dall'utente ("lascia verde solo il valore della carica").

### Fixed
- watch-app: causa reale del bug "le zone create in precedenza non
  ricompaiono sulla phone-app", persistente anche dopo il fix della
  migrazione lato backend (v0.49.0). `MainActivity.onCreate` accodava
  il lavoro one-shot `GeofenceSyncWorker` con
  `ExistingWorkPolicy.KEEP`: se un lavoro con lo stesso nome univoco
  esisteva gia' nel database di WorkManager (anche se completato mesi
  fa, prima del fix), riaprire l'app non forzava una nuova chiamata a
  `GET /api/device-config` — serviva il giro periodico (6h) o un
  riavvio del watch (`BootReceiver` usa gia' `REPLACE`). Cambiato a
  `REPLACE`: ogni apertura dell'app forza ora una sync fresca.

## [0.52.0] - 2026-09-18

### Fixed
- phone-app: `StatusCard` (mappa, riga scorrevole per bambino) alta
  circa metà schermo, confermato dall'utente con screenshot su build
  aggiornata (v0.3.0 visibile in `TopAppBar`, quindi non era un
  problema di build stale come inizialmente ipotizzato in v0.49.0).
  Causa: la `LazyRow` di `StatusCardRow` non aveva nessun vincolo di
  altezza (`EventsList`, nella stessa schermata, usa già correttamente
  `heightIn(max = 160.dp)` per lo stesso motivo) — una
  `LazyRow`/`LazyColumn` senza un `Modifier` che ne limiti l'altezza
  si espande a riempire tutto lo spazio verticale disponibile lasciato
  dal `Box` genitore, "stirando" la `Card` al suo interno. Aggiunto
  `heightIn(max = 140.dp)`. La visualizzazione della batteria (altro
  problema segnalato in v0.49.0) risultava invece già corretta nello
  screenshot dell'utente — nessun fix necessario lì.

## [0.51.0] - 2026-09-18

### Fixed
- phone-app: errore di compilazione `Unresolved reference: BuildConfig`
  in `MapScreen.kt` (numero di versione mostrato accanto a "Dov'è",
  v0.47.0). Causa: `phone-app/app/build.gradle.kts` non aveva
  `buildFeatures.buildConfig = true` — con AGP 8+ la generazione della
  classe `BuildConfig` non è più implicita e va abilitata
  esplicitamente (il watch-app ce l'aveva già, per `DEVICE_TOKEN`/
  `BACKEND_BASE_URL`). Aggiunta la flag: la build ora genera
  `BuildConfig.VERSION_NAME` come atteso.

## [0.49.0] - 2026-09-18

### Fixed
- **Zone geofence "scomparse" dalla phone-app**: `backend/api/device-config.js`
  marcava `devices/{childId}.geofencesMigrated=true` in modo permanente
  al primo utilizzo, anche se in quel momento la vecchia subcollection
  risultava vuota per qualunque motivo transitorio — da lì in poi la
  migrazione non veniva più ritentata e le zone restavano per sempre
  nella vecchia posizione (`devices/{id}/geofences`), invisibili alla
  query sulla nuova collezione radice `geofences` letta dalla
  phone-app. Rimosso il flag: la migrazione ora è idempotente
  per-documento e viene ritentata ad ogni chiamata (costo di una sola
  lettura extra, tipicamente su una subcollection vuota dopo la prima
  copia reale).

### Known limitations
- Non è stato possibile verificare da questa sessione se le zone
  dell'utente si trovassero effettivamente ancora nella vecchia
  posizione (nessun accesso diretto a Firestore da qui) — il fix
  copre la causa più probabile, ma va confermato dal prossimo test
  reale (aprire "Zone" sulla phone-app dopo che il watch ha rifatto
  una chiamata a `/api/device-config`).
- Segnalato anche uno "status card" (ultima posizione/batteria)
  visualmente molto più grande del previsto e senza batteria, sulla
  phone-app — il codice attuale (`MapScreen.kt`, `StatusCard`) risulta
  corretto e già presente da diversi commit; lo screenshot fornito
  dall'utente non mostra però il numero di versione appena aggiunto
  sotto "Dov'è" (v0.47.0), segno che la build installata potrebbe
  precedere queste correzioni. Da verificare dopo una ricompilazione/
  reinstallazione pulita della phone-app.

## [0.48.0] - 2026-09-18

### Added
- **`GpsAvailability`** (nuovo, `watch-app/.../location/GpsAvailability.kt`):
  stato condiviso "il GPS risponde?", aggiornato da
  `LocationTrackingService` (tracking periodico automatico) e da
  `LocationRequestWorker`/`SosWorker` (invio manuale/SOS) — nessun
  polling GPS aggiuntivo, riusa i fix già richiesti da queste tre fonti.
- **Pulsante "Invia posizione"**: disabilitato quando `GpsAvailability`
  segnala GPS assente, con etichetta "Posizione non disponibile,
  segnale GPS assente" al posto di "Invia posizione" — richiesto
  dall'utente dopo la diagnosi via Logcat (v0.45.0/0.46.0) che ha
  confermato il fix GPS mancante come causa reale. **SOS resta sempre
  abilitato** (scelta esplicita, non richiesta dell'utente ma
  raccomandazione accettata): è la funzione di sicurezza più critica,
  non va mai bloccata — è spesso proprio in mancanza di segnale (al
  chiuso) che serve di più, e deve poter partire comunque non appena un
  fix arriva.
- **Conferma "posizione/SOS inviata" più affidabile**: prima veniva
  osservato solo il `work.id` della singola pressione, legato al ciclo
  di vita dell'Activity — se il GPS restava assente per minuti/ore
  (caso reale) e l'utente chiudeva/riapriva l'app nel frattempo, la
  conferma finale poteva non comparire mai. Ora `MainActivity.kt`
  osserva una volta sola (in `onCreate`) il **nome univoco** del lavoro
  (`getWorkInfosForUniqueWorkLiveData`) invece del singolo id: riaprendo
  l'app si vede comunque l'esito reale (successo o fallimento
  definitivo), anche se il fix è arrivato ad app chiusa.

### Known limitations
- La conferma resta legata al processo dell'app: se il sistema termina
  del tutto il processo watch (non solo l'Activity) mentre il GPS è
  ancora assente, alla riapertura non c'è comunque nulla da "recuperare"
  a livello di notifica — WorkManager riprenderà comunque il lavoro in
  background, ma senza un Toast retroattivo. Non risolto in questa
  versione (richiederebbe una notifica di sistema invece di un Toast).

## [0.47.0] - 2026-09-18

### Added
- **Numero di versione visibile in app**, accanto al nome, su entrambe
  le app (`MapScreen.kt` sul telefono, `MainActivity.kt` sul watch) —
  richiesto dall'utente per poter verificare a colpo d'occhio, senza
  aprire le Impostazioni di sistema, se l'ultima build compilata sia
  davvero quella installata (rilevante per la diagnosi in corso).

### Confirmed
- **Tracking automatico periodico**: già presente e funzionante di suo
  (non serviva un nuovo sviluppo) — `LocationTrackingService.kt`
  richiede un fix ogni 10 minuti da fermo/1 minuto in movimento,
  bufferizzati e caricati verso il backend ogni 15 minuti
  (`LocationUploadWorker`, periodico) o subito se il buffer supera 15
  punti — più frequente della richiesta utente di "ogni 30 minuti".
  Dipende dallo stesso `FusedLocationProviderClient` del bug in
  diagnosi (fix GPS che non arriva mai): se il GPS non si aggancia,
  anche questo tracking automatico resta silenzioso, non solo l'invio
  manuale/SOS.

## [0.46.0] - 2026-09-18

### Fixed
- **`versionCode`/`versionName` di entrambe le app fermi a `2`/"0.2.0"**
  da decine di commit, mai incrementati dopo il primo bump — segnalato
  dall'utente durante la diagnosi corrente: senza un numero che cambia,
  da Impostazioni watch/telefono era impossibile verificare se l'APK
  appena compilato fosse davvero quello installato (rilevante ora,
  perché una build vecchia non reinstallata per errore avrebbe lo
  stesso identico comportamento "invio silenzioso" del bug in corso di
  diagnosi). Portati a `3`/"0.3.0" su entrambe (`watch-app`,
  `phone-app`); da qui in avanti vanno incrementati ad ogni release.

### Added
- Istruzioni per l'utente su come leggere il Logcat da Android Studio
  (tab "Logcat", filtro sui tag `LocationRequestWorker`/`SosWorker`) —
  richiesto perché la procedura via `adb` da terminale non era chiara.

## [0.45.0] - 2026-09-18

### Added
- **Diagnosi via log Vercel**: confermato che deploy/backend funzionano
  correttamente (`register-watch-token`, `device-config`,
  `send-message` tutti 200), ma **zero chiamate a `/api/trigger-event`**
  nonostante il test di "Invia posizione" e SOS sul watch — il blocco è
  quindi prima della rete, nel prendere il fix GPS. Aggiunto `Log.w` in
  `LocationRequestWorker.kt`/`SosWorker.kt` sui due casi che tornavano
  silenziosi (permesso `ACCESS_FINE_LOCATION` mancante →
  `Result.failure()`; fix GPS null/eccezione → `Result.retry()`), così
  il prossimo test mostra in Logcat la causa esatta invece di un nulla
  di fatto.

### Known limitations
- Diagnosi di "watch non invia posizione/SOS" ancora aperta — prossimo
  passo: Logcat sul watch (tag `LocationRequestWorker`/`SosWorker`)
  durante un nuovo test, per vedere se il motivo è permesso posizione
  non concesso o GPS che non arriva mai a un fix (es. al chiuso).

## [0.44.0] - 2026-09-18

### Added
- **`watch-app/.../network/BackendClient.kt`**: aggiunto logging
  (`Log.w`, tag "BackendClient") su ogni chiamata al backend non
  andata a buon fine — codice HTTP + corpo risposta su un errore del
  server, l'eccezione su un errore di rete. Prima non loggava nulla,
  a differenza dell'equivalente lato phone-app: da Logcat era
  impossibile distinguere "il watch non ha connettività dati" da "il
  token del device è sbagliato" (401) da un errore lato server.
  Segnalato durante il primo test su hardware reale: "richiesta
  posizione: watch non raggiungibile" (dalla phone-app) e "invio
  posizione dal watch senza mai conferma".

### Known limitations
- **Diagnosi ancora aperta** dei due sintomi sopra — vedi CONTEXT.md,
  log decisioni: il codice si comporta come documentato (nessun bug
  trovato in `LocationRequestWorker`/`SosWorker`: un fallimento di rete
  o di fix GPS ritorna `Result.retry()` di proposito, in silenzio, per
  non dare mai per persa una richiesta — motivo per cui l'invio non
  mostra mai "fallito", resta in ritentativo). Il sospetto più
  probabile è a monte del codice: token del device (`local.properties`)
  non corrispondente a quello salvato su Firestore per quel bambino,
  o un piano dati eSIM che non include traffico dati generico (molte
  eSIM per smartwatch bambini forniscono solo voce/SMS/localizzazione
  via backend del produttore, non internet libero) — vedi CONTEXT.md
  per i passi di verifica.

## [0.43.0] - 2026-09-18

### Fixed
- **`AppViewModel.kt` non compilava** (primo build reale in Android
  Studio dopo i fix precedenti — "Missing '}'" e "Unclosed comment" a
  fine file). Causa: un KDoc a riga singola conteneva la prosa
  `"devices/* e' scrivibile..."` — quel `/*` dentro il testo apre un
  commento annidato (i block comment Kotlin si annidano), e il `*/` di
  fine riga chiudeva quello annidato invece di quello esterno: il KDoc
  restava aperto fino a fine file, inghiottendo tutto il codice
  successivo. Bug pre-esistente di questa sessione (fase 3/4, mai
  emerso prima per mancanza di SDK Android da compilare). Riscritto
  senza l'asterisco letterale. Controllato l'intero codebase Kotlin
  (entrambe le app) per lo stesso pattern: nessun altro caso.

## [0.42.0] - 2026-09-18

### Fixed
- **Review di un'altra AI locale che aveva pushato direttamente su
  questo branch**: conteneva alcune idee
  valide ma con difetti di esecuzione gravi, alcuni al punto da rompere
  completamente sia il backend che la phone-app. Corretti tutti prima che
  arrivassero al bambino/genitore in produzione:
  - **Backend completamente giù**: tutti e 10 gli endpoint
    (`ingest-location`, `sos-heartbeat`, `register-watch-token`,
    `device-config`, `ha-status`, `messages`, `parent-command`,
    `cleanup`, `trigger-event`, `send-message`) erano stati avvolti in
    un nuovo `wrapHandler(...)` (vedi `_lib/errors.js`) senza la
    parentesi di chiusura corrispondente — errore di sintassi che
    impediva il caricamento di ogni singola funzione. Corretto
    aggiungendo la `)` mancante ovunque.
  - **Phone-app non compilava**: `TrackerApplication.kt` aveva la
    chiamata di iscrizione al topic FCM `"parents"` piazzata *dopo* la
    chiusura di `onCreate()`, come statement diretto nel corpo della
    classe — non valido in Kotlin. Spostata dentro `onCreate()`.
  - **`MapScreen.kt` (indicatore batteria colorato)**: la UX era una
    buona idea (verde/arancio/rosso in base alla percentuale) ma
    l'esecuzione aveva graffe/parentesi sbilanciate (il pulsante
    "Aggiorna posizione" era rimasto incastrato dentro il blocco della
    batteria) e un `LinearProgressIndicator` con `Modifier.weight()` —
    API interna in questa versione di Compose, esattamente il vincolo
    già documentato altrove nel progetto. Riscritta la sezione: tenuto
    il testo colorato, tolta la progress bar per non reintrodurre
    `weight()`. Rimosso anche un blocco di colore copiato per errore in
    `EventsList`, dove referenziava una variabile `battery` inesistente
    in quello scope (altro errore di compilazione).
  - **`_lib/quota.js`**: la retention del contatore di quota giornaliero
    era stata agganciata per errore a `config.RETENTION_HOURS` (la
    retention dello storico posizioni, 12 mesi) invece di restare un
    valore proprio — portava i documenti di quota da 7 a 365 giorni di
    vita. Ripristinata una costante dedicata.
  - **`test/auth.test.js`**: sintassi `import` ESM in un progetto
    CommonJS senza `"type": "module"` — il test non veniva nemmeno
    caricato da `npm test`. Riportato a `require()`. `package.json`:
    lo script `test` (`node --test test/`) falliva a risolvere la
    cartella su Node 22 in questo ambiente — reso esplicito
    (`test/*.test.js`). Tutti i 10 test (auth + quota) passano ora.
  - Verificato con `node -c` su tutti i file `.js` del backend e con
    `npm test` (dopo `npm ci`): tutto verde.

### Added (idee valide della review, tenute)
- Confronto costant-time (`crypto.timingSafeEqual`) per i due token
  statici superstiti (`DEVICE_TOKEN` legacy, `HA_STATUS_TOKEN`) invece
  di `===`, con fail-closed esplicito se `HA_STATUS_TOKEN` non è
  impostata — mitiga un timing attack teorico. Test unitari aggiunti e
  verificati (`test/auth.test.js`).
- Push ai genitori (chat dal bambino, SOS, geofence) via topic FCM
  `"parents"` invece di leggere l'intera collezione `parents` e
  iterare gli array `fcmTokens` ad ogni evento — una lettura Firestore
  in meno per notifica, token obsoleti gestiti da FCM stesso.
  Sottoscrizione lato phone-app in `TrackerApplication.onCreate()` e
  ri-sottoscrizione in `FcmService.onNewToken()` (entrambe idempotenti).
- `cleanup.js`: aggiunta la pulizia del gruppo di collezioni "events"
  (era rimasto scoperto dal cron dopo il riordino multi-bambino);
  `trigger-event.js` ora scrive `expiresAt` sugli eventi.
- `compileSdk`/`targetSdk` portati a 35 su entrambe le app +
  `enableEdgeToEdge()` esplicito sul telefono — requisito Play Store
  per i nuovi upload (34 non più accettato). AGP/Gradle allineati tra
  le due app (8.13.2/8.13).
- Indicatore batteria colorato (verde >50%, arancio 25-50%, rosso
  <25%) nelle status-card della mappa — tenuto, corretto come sopra.

### Known limitations
- `TESTING-E2E.md`, creato dalla stessa review, resta nel repo come
  documentazione di quella sessione — a differenza di
  CONTEXT.md/CHANGELOG.md non è mantenuto né considerato fonte di
  verità sullo stato del progetto.

## [0.41.0] - 2026-09-18

### Fixed
- **Bug segnalato dall'utente al primo test su hardware reale**: la
  phone-app mostrava "Invio fallito" mandando un messaggio al watch,
  anche quando il messaggio arrivava comunque (visibile in Firestore/
  sul watch). Causa: `parent-command.js` chiamava
  `getMessaging().send()` senza try/catch in tutti e 4 gli handler
  (message/request_location/cancel_sos/ack_event) — un token FCM del
  watch non più valido (tipico dopo una reinstallazione dell'app o una
  rotazione del token lato Google) mandava un'eccezione non gestita,
  Vercel rispondeva 500 alla phone-app anche se la scrittura su
  Firestore era già andata a buon fine. Aggiunta `sendPushSafe()`: la
  push resta "best effort" (loggata se fallisce, mai un errore fatale
  per la richiesta del genitore) e se l'errore è
  "registration-token-not-registered" il token viene ripulito dal
  documento device, così i tentativi successivi non ripetono lo stesso
  fallimento silenzioso finché il watch non si ri-registra da solo.

## [0.40.0] - 2026-09-11

### Added (Fase 4/4 — supporto a N bambini: chat con destinatario, completa il piano)
- Phone-app: `ChatScreen.kt` mostra un selettore "Scrivi a: [bambino] ▾"
  (visibile solo con più di un bambino registrato — nessuna scelta
  richiesta con un solo bambino, retrocompatibile). `AppViewModel.kt`
  gestisce `selectedChatChildId` (di default il primo bambino) e deriva
  `messages` per il thread scelto (`devices/{childId}/messages`),
  combinando remoto + echi ottimistici locali filtrati per `childId`.
  Le bolle si allineano ora su `senderId == proprio uid` invece che sul
  solo ruolo "parent"/"child" (necessario perché due genitori
  condividono lo stesso thread), col nome mostrato da `senderName`.
- Watch-app: `ChatMessage.kt` riceve `senderName`; `FcmService.kt` lo
  legge dal payload push data-only e lo passa a `MessageStore`;
  `ChatScreen.kt` mostra `senderName` (nickname del genitore) sopra i
  messaggi ricevuti invece dell'etichetta fissa "Genitore" (rimasta
  come fallback se un genitore non ha ancora impostato un nickname).
- Corretto un commento obsoleto in `watch-app/local.properties.example`
  e nel relativo README (parlavano ancora di un `DEVICE_TOKEN` statico
  su Vercel, superato dalla fase 1 — auth per hash lookup su Firestore,
  un token diverso per bambino).

### Changed
- **Deviazione dal piano approvato**: il piano originale prevedeva un
  `BuildConfig.CHILD_ID` sul watch (nuova chiave `local.properties`)
  per sapere "chi sono io" nel confronto `senderId`. Non implementato:
  ogni watch legge/scrive solo il proprio thread dedicato
  (`devices/{childId}/messages`), quindi `sender=="child"` è già
  inequivocabilmente "io" per l'allineamento delle bolle, senza
  ambiguità possibile (nessun messaggio di un altro bambino può mai
  comparire in quel thread). Aggiungere `CHILD_ID` sarebbe stata
  un'astrazione non necessaria — coerente con la linea del progetto di
  non introdurre complessità oltre quella richiesta dal problema reale.
  Vedi commento di versione in `network/model/ChatMessage.kt`.

Con questa fase il piano "Supporto a N bambini/watch, nickname, chat
con destinatario, zone multi-assegnate"
(`/root/.claude/plans/clever-roaming-moth.md`) è completo.

## [0.39.0] - 2026-09-11

### Added (Fase 3/4 — supporto a N bambini: impostazioni e mappa multi-bambino)
- Nuova `SettingsScreen.kt` (phone-app), raggiungibile dal menu
  hamburger di `MapScreen.kt` accanto a "Logout": nickname proprio
  (scritto direttamente su `parents/{uid}.nickname`), nickname di ogni
  bambino registrato (passa da `parent-command.js`, azione
  `set_nickname`), "Aggiungi bambino" (azione `create_child` — mostra
  il token generato una volta sola, con un `AlertDialog` non
  dismissabile a caso e un pulsante "Copia" negli appunti, coerente col
  fatto che il token non sarà più recuperabile in seguito).
- `MapScreen.kt` mostra ora **tutti i bambini insieme su una sola
  mappa** (non uno switcher): un marker per bambino, riga orizzontale
  scorrevole di status-card (una per bambino, ciascuna col proprio
  pulsante "Aggiorna posizione" e il proprio stato di caricamento),
  lista di banner SOS (invece di uno singolo — più bambini potrebbero
  avere un SOS attivo insieme), percorso storico per bambino con colori
  distinti quando "Percorso 24h" è attivo. Le geofence restano
  disegnate una sola volta ciascuna (risorsa condivisa, fase 2/4).
- `AppViewModel.kt`: `deviceState`/`history`/`events` (singolari)
  sostituiti da `deviceStates`/`historyByChild`/`eventsByChild` (mappe
  `childId -> dato`), derivate dalla lista bambini con
  `flatMapLatest`+`combine`. Aggiunti `ownNickname`, `updateOwnNickname`,
  `setChildNickname`, `createChild`.

### Fixed
- **Bug latente introdotto in fase 1** (mai emerso perché la UI a
  valle non era stata ancora aggiornata): `BackendClient.kt` non
  mandava `childId` nel body di `message`/`request_location`/
  `cancel_sos`/`ack_event`, azioni che `parent-command.js` richiede
  esplicitamente dalla fase 1 — ogni chiamata rispondeva già 400
  `'childId' mancante`. Corretto aggiungendo il parametro a tutte e
  quattro.

### Known limitations
- La chat resta a singolo destinatario fisso (`Constants.DEVICE_ID`):
  il selettore "Scrivi a: ..." e le bolle per identità (invece che per
  ruolo) arrivano in fase 4.

## [0.38.0] - 2026-09-11

### Added (Fase 2/4 — supporto a N bambini: geofence condivise)
- Backend: le geofence non sono più annidate sotto un singolo device
  (`devices/{childId}/geofences/{zoneId}`) ma vivono in una nuova
  collezione radice `geofences/{zoneId}` con un campo `childIds:
  string[]` — una zona può ora essere assegnata a uno o più bambini.
  `device-config.js` (letto dal watch) interroga `geofences` con
  `where("childIds","array-contains",childId)` — query a singolo
  campo, nessun indice composito necessario (il filtro `active` è
  applicato in memoria dopo la lettura).
- Backend: **migrazione automatica** delle zone già esistenti — al
  primo utilizzo per ciascun bambino, se non ancora fatta
  (`devices/{childId}.geofencesMigrated`), `device-config.js` copia le
  zone dalla vecchia subcollection alla nuova collezione radice
  (`childIds: [childId]`), stesso pattern di auto-migrazione già usato
  per il token legacy in `_lib/auth.js` — nessun passaggio manuale.
- `firestore.rules`: `geofences/{zoneId}` promossa a collezione radice
  (`allow read, write: if isParent()`), rimossa la vecchia regola
  annidata.
- `trigger-event.js`: la lettura della zona (nome, toggle
  notifica/allarme) per le notifiche di ingresso/uscita ora punta alla
  nuova collezione radice.
- Phone-app: `GeofenceScreen.kt` guadagna una riga di toggle "Assegna
  a: [bambino]" per ogni bambino registrato, sia nel pannello di
  creazione/modifica zona sia su ogni riga della lista zone —
  `DeviceRepository` espone `observeChildren()` (query live su
  `devices`) e `observeGeofences()`/`saveGeofence()`/`deleteGeofence()`
  ripuntano alla nuova collezione radice. Una zona nuova parte con
  tutti i bambini conosciuti già selezionati (comportamento invariato
  con un solo bambino); una zona in modifica riparte dalla sua
  selezione salvata.

### Known limitations
- La UI mappa/chat/impostazioni resta ancora a singolo bambino
  (`Constants.DEVICE_ID`) — arriva nelle fasi 3/4. Il selettore
  per-bambino di questa fase riguarda solo le geofence.

## [0.37.0] - 2026-09-11

### Added (Fase 1/4 — supporto a N bambini, solo backend)
- Registro bambini dinamico: ogni bambino è un documento
  `devices/{childId}` (childId auto-generato da Firestore per i nuovi,
  `"figlio"` resta l'id del primo — zero migrazione dei dati storici).
- Autenticazione watch multi-device: `_lib/auth.js` sostituisce
  `checkDeviceToken` (un unico token statico globale) con
  `resolveDeviceId(req, db)` — hash SHA-256 del token ricevuto,
  lookup su `devices` per `deviceTokenHash`. Solo l'hash è salvato su
  Firestore, mai il token in chiaro. **Migrazione automatica**: se
  l'hash-lookup non trova nulla ma il token combacia col vecchio
  `process.env.DEVICE_TOKEN`, il device `"figlio"` viene aggiornato al
  volo con il suo `deviceTokenHash` — il watch già installato continua
  a funzionare senza nessun passaggio manuale.
- `parent-command.js`: due nuove azioni, `create_child` (genera
  childId + token per un nuovo bambino, salva solo l'hash, risponde col
  token in chiaro una volta sola) e `set_nickname` (imposta
  `devices/{childId}.childName`). Le 4 azioni esistenti (`message`,
  `request_location`, `cancel_sos`, `ack_event`) richiedono ora un
  `childId` esplicito nel body invece di agire implicitamente su
  `"figlio"`.
- Messaggi di chat portano ora `senderId`/`senderName` (denormalizzati
  al momento dell'invio) oltre al vecchio `sender` (ruolo) — servirà a
  distinguere i due genitori nella stessa conversazione (fase 4).
- Notifiche push (SOS/geofence/posizione) includono ora il nome del
  bambino nel testo — con un solo figlio era implicito, con N non più.
- `ha-status.js` accetta un `?child=<childId>` facoltativo (default
  `"figlio"`, nessuna rottura per una configurazione Home Assistant
  già in uso).

### Changed
- Tutti gli endpoint `backend/api/*.js` non usano più la costante
  `DEVICE_ID = "figlio"`: risolvono il bambino chiamante dal token
  (watch) o dal `childId` nel body (comandi genitore).

### Known limitations
- Geofence, mappa e chat restano per ora a singolo bambino lato
  entrambe le app (nessuna UI ancora aggiornata) — questa fase è solo
  la fondazione backend, retrocompatibile: con un solo bambino nulla
  cambia visibilmente per l'utente. Le fasi successive (geofence
  condivise con toggle per bambino, mappa multi-bambino, selettore
  destinatario in chat, Impostazioni con nickname/"Aggiungi bambino")
  seguiranno in commit separati.
- Nessuna modifica a `firestore.rules`/`firestore.indexes.json` in
  questa fase: i nuovi campi (`deviceTokenHash`, `nickname`) sono già
  coperti dalle regole esistenti su `devices/{deviceId}` e
  `parents/{parentId}`.

## [0.36.0] - 2026-09-11

### Changed
- **Phone, MapScreen**: riorganizzazione richiesta dopo il fix v0.35.0
  (non era chiaro che "Esci" fosse un logout):
  - "Logout" (rinominato da "Esci") non è più un pulsante diretto in
    barra ma una voce in un nuovo menu hamburger, con lo stesso dialog
    di conferma già presente (v0.35.0).
  - Lo switch "Percorso 24h" si è spostato dalla card sotto la mappa
    alla TopAppBar, accanto all'icona del menu.
  - Il pulsante "Aggiorna posizione" si è spostato sulla stessa riga
    della card di stato (testo + batteria), la card separata sotto è
    stata rimossa.
  - Testo di stato rinominato da "Aggiornato X min fa" a "Ultima
    posizione ricevuta X min fa" (`util/TimeFormat.kt`).
- **Watch, ChatScreen**: lo scroll automatico in fondo alla chat ora
  avviene solo aprendo la chat dalla notifica di un nuovo messaggio
  (`scrollToBottom` passato da `MainActivity.kt`), non più aprendo la
  chat dal pulsante "Messaggi" del menu principale — lì la chat ora si
  apre da dove l'ha lasciata l'utente.

### Added
- Nuova dipendenza `androidx.compose.material:material-icons-core`
  (phone-app) per l'icona del menu hamburger.

## [0.35.0] - 2026-09-11

### Fixed
- **Phone, MapScreen**: segnalato un logout inatteso premendo "Esci"
  nella barra in alto — era un `TextButton` affiancato a "Messaggi"/
  "Zone" che disconnetteva subito al tocco, senza nessuna conferma.
  Aggiunto un `AlertDialog` di conferma ("Uscire dall'app?") prima di
  eseguire il logout vero e proprio.

### Known limitations
- Richiesta simile sul watch ("a volte in avvio chiede l'accesso con
  l'account genitore") **non è riproducibile nel codice**: la
  watch-app non ha nessuna schermata di login Google (usa solo un
  device token via FCM, vedi CONTEXT.md — decisione esplicita "niente
  login Google sul watch"). Il prompt visto sul watch è quasi certamente
  un prompt di sistema Wear OS/Google Play Services legato
  all'account Google "adulto" usato per attivare il device, non
  qualcosa di risolvibile lato app — da confermare con uno screenshot
  alla prossima occorrenza.

## [0.34.0] - 2026-09-10

### Added
- Predisposizione per la pubblicazione su **Play Console → Internal
  Testing** (richiesta utente, per far provare l'app anche alla
  mamma): `signingConfig` "release" in entrambe le app
  (`phone-app/app/build.gradle.kts`, `watch-app/app/build.gradle.kts`),
  letta da chiavi facoltative in `local.properties` (mai committato,
  stesso pattern gia' in uso per `device.token`) — se assenti, il
  build "release" resta semplicemente senza firma, nessun impatto sui
  build di sviluppo. Aggiornati entrambi i `local.properties.example`
  con le nuove chiavi (`release.storeFile/storePassword/keyAlias/
  keyPassword`) e il comando `keytool` per generare il keystore.
- `backend/privacy.html`: informativa privacy statica, servita da
  Vercel a costo zero insieme al resto del backend — richiesta da
  Google Play anche per canali di test privati/non pubblici quando
  l'app dichiara permessi sensibili (posizione).

### Note
- La generazione del keystore di release, il build del `.aab` firmato
  (Android Studio → Build → Generate Signed App Bundle) e i passaggi
  su Play Console (creazione app, Internal Testing, Data Safety form,
  aggiunta email tester) restano manuali lato utente: non sono
  automatizzabili da qui (servizio esterno con login personale, nessun
  accesso da questo ambiente).

## [0.33.0] - 2026-09-10

### Fixed
- **Zone (Geofence, telefono)**: cliccando "Modifica" su una zona in
  lista, la mappa ora si centra automaticamente sulla posizione di
  quella zona (`mapView.controller.animateTo()` + zoom 17, stessa
  chiamata gia' usata per i risultati della ricerca indirizzo). Prima
  la camera restava ferma dov'era: se la zona da modificare era fuori
  dall'inquadratura corrente, il pannello di modifica si apriva ma la
  zona restava invisibile fuori schermo.

## [0.32.0] - 2026-09-10

### Changed
- Migliorata la visualizzazione della chat su entrambe le app,
  replicando lo stile WhatsApp (richiesta utente, dopo essersi
  documentati sul suo pattern mittente/destinatario): bolle colorate
  invece del testo piatto/del contenitore generico Material, allineate
  a destra (propri messaggi, verde) o sinistra (ricevuti, bianco/grigio
  scuro secondo il tema), con angolo meno arrotondato dal lato del
  "mittente" per dare l'effetto fumetto. Aggiunto il nome del mittente
  in grassetto colorato sopra il testo, solo per i messaggi ricevuti
  (utile soprattutto in prospettiva multi-genitore, dove "ricevuto" non
  e' sempre ovviamente riconducibile a una sola persona).
  - Watch (`ChatScreen.kt`): colori del tema scuro di WhatsApp (schermo
    sempre nero su Wear OS), nome mittente "Genitore".
  - Phone (`ChatScreen.kt`): colori chiari/scuri secondo il tema di
    sistema (`isSystemInDarkTheme()`), nome mittente "Bambino".

## [0.31.0] - 2026-09-10

### Fixed
- **Confermato dall'utente con screenshot**: il tap sulla notifica del
  watch ora funziona ("Apri app" porta correttamente in chat).

### Changed
- Icona della notifica sul watch ancora generica (fumetto) e nessun
  nome app visibile in nessuna parte della card, confermato dallo
  screenshot. Aggiunto `setLargeIcon()` con l'icona reale dell'app
  (`mipmap/ic_launcher`), che Wear OS mostra tipicamente accanto/al
  posto della small icon per rendere piu' riconoscibile la
  provenienza.

### Known limitations
- Non esiste nessuna API per forzare un testo "nome app" esplicito
  nella card di notifica Wear OS: quello che il sistema mostra (se lo
  mostra) e' sempre e solo l'etichetta del manifest (gia' "Family
  Tracker"), con resa a schermo interamente decisa dalla skin del
  dispositivo. Se anche con l'icona reale non compare alcun nome, e'
  un limite della skin Wear OS di questo Galaxy Watch4, non
  ulteriormente risolvibile lato app.

### Fixed
- **Confermato dall'utente**: popup e riattivazione schermo per le
  notifiche sul watch ora funzionano (v0.28.0/v0.29.0 hanno risolto).
- La notifica sul watch non era cliccabile, il tocco non portava a
  nessuna schermata (mancava un `contentIntent`, stesso bug gia'
  sistemato sulla phone-app). Aggiunto un `PendingIntent` verso
  `MainActivity`: per i messaggi di chat apre direttamente la
  schermata Chat (`EXTRA_OPEN_CHAT`, letto sia a freddo sia ad app
  gia' aperta tramite `onNewIntent` + `launchMode="singleTop"`), per
  le altre notifiche apre la schermata principale.

### Known limitations
- Segnalato dubbio sull'identificazione dell'app nella notifica
  (icona generica, non chiaro da quale app arrivi): l'icona piccola
  e' gia' conforme alle linee guida Android (maschera monocromatica) e
  il nome app (`app_name` = "Family Tracker") dovrebbe comparire di
  default nella card Wear OS — probabile che nello screenshot fosse
  solo tagliato fuori dall'inquadratura (cinturino sopra al bordo).
  Da riverificare con schermo/foto meno raccorciata prima di
  considerarlo un problema reale.

### Fixed
- Anche il nuovo ID canale (v0.28.0) non ha risolto la mancanza di
  vibrazione/popup: confermato dall'utente, la notifica compare ancora
  solo nel pannello. Aggiunta vibrazione esplicita anche a livello di
  singola notifica (`setVibrate`/`setDefaults`) in
  `FcmService.kt` (watch) — ridondante rispetto al canale su Android
  8+, ma alcune skin OEM (incluso Wear OS Samsung) non rispettano
  sempre in modo affidabile solo le impostazioni di canale.

### Known limitations
- Se anche questo non basta, il problema e' quasi certamente
  un'impostazione di sistema sul watch, non risolvibile da codice:
  Non disturbare/Modalita' teatro/Bedtime mode attivi, vibrazione
  disattivata globalmente (Impostazioni > Suoni e vibrazione), o il
  toggle "Vibra" del canale "Messaggi" disattivato a mano in
  Impostazioni > App > Family Tracker > Notifiche sul watch stesso.

## [0.28.0] - 2026-09-10

### Fixed
- Il fix vibrazione della v0.26.0 (canale "messages" del watch) non
  aveva alcun effetto reale, confermato dopo ricompilazione: ancora
  nessuna vibrazione/popup all'arrivo di un messaggio. Causa: su
  Android le impostazioni di un `NotificationChannel` sono immutabili
  dopo la creazione — richiamare `createNotificationChannel()` con lo
  stesso ID (gia' esistente sul dispositivo da build precedenti) non
  aggiorna nulla, il sistema ignora silenziosamente i nuovi parametri.
  Cambiato l'ID del canale da `"messages"` a `"messages_v2"` in
  `TrackerApplication.kt`, cosi' Android ne crea uno nuovo con la
  vibrazione abilitata fin da subito.

## [0.27.0] - 2026-09-10

### Fixed
- **Deploy Vercel bloccato da 3 commit** (5af2c9a, f7da87a, 7a1a84a mai
  andati online): `backend/api/` era arrivato a 13 file, il piano
  Hobby di Vercel ne permette al massimo 12 per deployment ("No more
  than 12 Serverless Functions..."). Accorpati
  `send-message-to-child.js`, `request-location.js`, `cancel-sos.js`
  e `ack-event.js` in un unico `backend/api/parent-command.js`
  (dispatch su un campo "action" nel body) — tornati a 10 file, con
  margine per il futuro. `phone-app/.../data/BackendClient.kt`
  aggiornato per chiamare il nuovo endpoint unico.
- Toccando la notifica di un messaggio dal watch, la phone-app apriva
  la Home (mappa) invece della schermata Chat: `FcmService.kt` non
  impostava alcun `contentIntent` sulla notifica. Aggiunto un
  `PendingIntent` verso `MainActivity` con un extra che ora fa
  navigare subito alla Chat, sia ad app fredda sia gia' aperta
  (`launchMode="singleTop"` + `onNewIntent`).

### Fixed (infrastruttura, non codice)
- **Confermato dall'utente**: la schermata Chat vuota sulla phone-app
  era causata dalle regole Firestore mai pubblicate sul progetto
  Firebase live da quando la sottocollezione `devices/{id}/messages`
  era stata aggiunta (`backend/firestore.rules` v0.4.0) — le regole
  NON si deployano automaticamente ad ogni push (target separato dal
  deploy Vercel, richiede `firebase deploy --only firestore:rules` da
  `backend/`, o incollarle a mano in Console Firebase > Firestore >
  Regole). Ogni lettura della chat falliva quindi in silenzio con
  permission-denied. Risolto ripubblicando le regole dalla Console —
  nessuna modifica di codice necessaria. **Promemoria per il futuro**:
  ogni modifica a `backend/firestore.rules` richiede questo passaggio
  manuale in piu', il repo/Vercel da soli non bastano.

## [0.26.0] - 2026-09-10

### Fixed
- I messaggi di chat ricevuti dal watch comparivano solo come notifica
  di sistema sulla phone-app, mai nella schermata Chat. Causa:
  `backend/api/send-message.js` mandava una push "mista"
  (notification + data) — ad app in background Android consegna la
  parte "notification" al tray di sistema e non invoca
  `onMessageReceived()`, quindi nessun codice app girava per
  aggiornare la chat (dipendeva solo dal listener Firestore). La push
  e' ora solo "data" (stesso pattern gia' in uso verso il watch);
  `FcmService.kt` (phone-app) costruisce la notifica a mano e aggiorna
  subito la chat tramite il nuovo `data/IncomingMessageStore.kt`
  (stesso ruolo dell'invio ottimistico gia' esistente per i messaggi
  in uscita).
- Sul watch, i messaggi arrivavano ma non svegliavano lo schermo, non
  vibravano e non mostravano alcuna card se il watch era sulla home.
  Causa: il canale di notifica "messages" non aveva la vibrazione
  abilitata esplicitamente (`enableVibration(false)` e' il default
  alla creazione, anche per canali `IMPORTANCE_HIGH`) — senza,
  Wear OS tratta la notifica come non abbastanza interruttiva da
  riattivare lo schermo. Aggiunto `enableVibration(true)` + pattern
  esplicito sul canale.

## [0.25.0] - 2026-09-10

### Fixed
- Segnalato: la phone-app doveva restare aperta in primo piano per
  ricevere le notifiche dal watch (chat, SOS, geofence). Con FCM non
  dovrebbe servire (le push "data" svegliano il processo anche in
  background) — causa piu' probabile: Android (specie Samsung, coerente
  con l'uso di un Galaxy Watch4) sospende il processo per risparmio
  batteria e nega la sveglia a un'app "ottimizzata". `MainActivity.kt`
  ora chiede esplicitamente, una tantum all'avvio, l'esenzione dalle
  ottimizzazioni batteria (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

### Known limitations
- Sui telefoni Samsung esiste una seconda lista, separata dalle
  ottimizzazioni batteria standard di Android e non gestibile da
  codice: "Metti in sospensione le app inutilizzate" (Impostazioni >
  Cura del dispositivo > Batteria > Limiti di utilizzo in background).
  Va disattivata manualmente per questa app se il problema persiste
  dopo aver concesso l'esenzione richiesta dall'app.
- Non ancora confermato dall'utente su hardware reale.

## [0.24.0] - 2026-09-10

### Added
- Notifica "Posizione visualizzata" sul watch quando il genitore vede
  sulla phone-app una posizione inviata volontariamente dal bambino
  (SOS o "Invia posizione"): nuovo endpoint `backend/api/ack-event.js`
  (idempotente), chiamato da `MapScreen.kt` quando mostra un evento non
  ancora marcato "acknowledged".

### Changed
- Notifiche sul watch (chat, SOS disattivato, posizione visualizzata)
  ora restano visibili finche' non vengono rimosse esplicitamente
  (`setAutoCancel(false)`, prima sparivano al tocco).
- Priorita' delle notifiche watch alzata a `PRIORITY_MAX` (prima
  `HIGH`) con categoria dedicata (`CATEGORY_MESSAGE` per la chat), per
  favorire la presentazione piu' prominente di Wear OS alla ricezione
  — la resa esatta a schermo resta comunque decisa dal sistema.

## [0.23.0] - 2026-09-10

### Added
- Ricerca indirizzo in `GeofenceScreen.kt` (nuovo `data/GeocodingClient.kt`,
  Nominatim/OpenStreetMap): la mappa partiva sempre centrata su Roma,
  senza modo di spostarsi rapidamente su un indirizzo vero. Selezionare
  un risultato centra la mappa li' e apre direttamente il pannello di
  creazione zona.

### Changed
- Il pannello di creazione/modifica zona e' ora ancorato in basso
  invece che in alto: prima, toccando la mappa vicino alla cima dello
  schermo, il pannello finiva esattamente sopra al punto appena scelto
  nascondendolo.
- Raggio minimo dello slider zona allargato da 50m a 20m (non era un
  limite tecnico, solo il range scelto nel codice).

## [0.22.0] - 2026-09-10

### Added
- Banner "🆘 SOS ATTIVO" sulla schermata principale del watch, e
  notifica "SOS disattivato" quando il genitore lo disattiva da remoto
  (`sos/SosState.kt`, nuovo): prima l'SOS non aveva alcun riscontro
  visivo sul watch dopo la conferma, ne' un modo di sapere se fosse
  ancora attivo o se il genitore lo avesse gia' disattivato.
- Campo "source" ("child" | "parent") su `trigger-event.js` per
  "location_request": la notifica ora distingue "Il bambino ha inviato
  la posizione attuale" da "Posizione aggiornata su tua richiesta"
  invece di mostrare sempre lo stesso testo indipendentemente da chi
  ha avviato l'invio.
- Scroll automatico all'ultimo messaggio nella chat del watch
  (`ChatScreen.kt`): un messaggio in arrivo finiva in fondo alla lista
  senza alcuno scroll automatico, invisibile senza scorrere a mano.
- Invio ottimistico dei messaggi nella chat della phone-app
  (`AppViewModel.kt`): un messaggio appena inviato dal genitore ora
  appare subito, senza aspettare il giro completo scrittura-backend ->
  lettura del listener Firestore (stesso pattern gia' in uso sul
  watch-app).

## [0.21.0] - 2026-09-10

### Added
- Toggle per-zona "Notifica all'ingresso" / "Notifica all'uscita"
  (`GeofenceScreen.kt`): prima un solo switch attiva/disattiva
  copriva entrambe le direzioni insieme.
- Toggle per-zona "Allarme sonoro all'uscita": oltre alla notifica,
  suona e vibra ripetutamente sul telefono del genitore finche' non lo
  si ferma. Nuovo `phone-app/.../alarm/ExitAlarmService.kt`
  (foreground Service, loop audio/vibrazione, cap di sicurezza 5
  minuti) + `ExitAlarmActivity.kt` (schermata a tutto schermo sopra il
  lockscreen). Avviato da un messaggio FCM data-only dedicato
  (`backend/api/trigger-event.js`), necessario per partire anche ad
  app in background/uccisa.
- Modifica di una zona esistente in `GeofenceScreen.kt` (pulsante
  "Modifica" in lista): prima si poteva solo attivare/disattivare o
  cancellare una zona, mai cambiarne nome/raggio/notifiche.

### Fixed
- Bug preesistente scoperto leggendo la config zona per i nuovi
  toggle: il campo "zoneName" mandato dal watch a `trigger-event.js`
  era in realta' sempre stato l'id Firestore della zona (mai il nome
  leggibile) — le notifiche di ingresso/uscita zona mostravano l'id al
  posto del nome fin dall'inizio. Rinominato "zoneId" end-to-end
  (watch -> backend), il nome vero ora e' risolto lato backend
  leggendo il documento zona da Firestore.

## [0.20.0] - 2026-09-10

### Added
- `GeofenceScreen.kt` (phone-app): pulsante "Annulla" per scartare un
  punto scelto per errore prima di salvare la zona; zone gia' salvate
  ora visibili come cerchi anche su questa mappa (prima solo su
  `MapScreen.kt`); anteprima live (cerchio verde) del raggio scelto
  con lo slider, prima di salvare.

### Fixed
- Schermata Zone della phone-app: la mappa occupava solo meta' schermo
  (`Modifier.height(220.dp)` fissa) lasciando il resto bianco, e non
  c'era una barra strumenti per gestire le zone. Riscritta sul pattern
  Box+align gia' in uso in `MapScreen.kt`: mappa a `fillMaxSize()`,
  pannello strumenti flottante in alto, lista zone flottante in basso
  con sfondo opaco (stessa lezione di trasparenza gia' notata per
  `EventsList` in `MapScreen.kt`, applicata qui preventivamente).

## [0.19.0] - 2026-09-10

### Added
- Pulsante "Invia posizione attuale" sul watch + "Aggiorna posizione"
  sulla phone-app (`backend/api/request-location.js`): richiede al
  watch un fix GPS immediato via push FCM, senza aspettare l'upload
  periodico a 15 minuti.
- Switch "Percorso 24h" sulla mappa della phone-app (prima si vedeva
  solo l'ultima posizione).
- Scadenza automatica dello storico chat a 24h (stesso meccanismo
  `expiresAt` + cron GitHub Actions gia' usato per posizioni/quota).
- **SOS ridisegnato**: ora richiede conferma esplicita sul watch prima
  di attivarsi (evita attivazioni accidentali) e, da attivo, invia la
  posizione ogni 30 secondi (`backend/api/sos-heartbeat.js`) finche' il
  genitore non lo disattiva dalla phone-app (banner rosso "SOS ATTIVO"
  + `backend/api/cancel-sos.js`), invece del precedente invio one-shot.
  Notifica push inviata solo al primo evento SOS dell'episodio, non ad
  ogni ping successivo.

### Fixed
- `Modifier.weight` non risolveva a build reale ne' nel watch-app ne'
  nella phone-app ("it is internal in
  androidx.compose.foundation.layout", causa esatta mai isolata con
  certezza): riscritti tutti i layout coinvolti (`MapScreen.kt`,
  `ChatScreen.kt` della phone-app) senza `weight`, pattern Box+align o
  `BoxWithConstraints`.
- SOS e "Invia posizione" scrivevano l'evento ma non aggiornavano
  `devices/figlio.lastLocation`: il pin sulla mappa della phone-app non
  si muoveva fino al prossimo upload periodico.
- Il pulsante "Invia" della chat sulla phone-app ignorava l'esito
  dell'invio (nessun feedback su fallimento) — probabile causa del
  sintomo "i messaggi non partono" riportato dall'utente. Aggiunto un
  Toast di esito e logging esplicito degli errori nei listener
  Firestore di `DeviceRepository` (prima ignorati silenziosamente).

### Known limitations
- Test su hardware reale del ridisegno SOS (conferma + tracking
  continuo) non ancora fatto — solo verificato a livello di build.
- `devices/{id}/events` non ha ancora retention/pulizia automatica
  (a differenza di locations/quota/messages): da aggiungere a
  `cleanup.js` in Fase 2 se il volume cresce.

## [0.18.1] - 2026-09-10

### Fixed
- Nessun bug: primo build reale di `watch-app/` e `phone-app/` in
  Android Studio, entrambi riusciti (sync Gradle + compilazione).
  Aggiornato lo stato in `CONTEXT.md`/README da "non ancora
  compilato/testato" a "build verificato". Ancora da testare su
  hardware reale (Watch4/telefono) e da pushare il backend su Vercel.

## [0.18.0] - 2026-09-10

### Added
- Chat testuale genitore↔watch, consegnata via **push FCM** invece che
  a polling: scelta fatta esplicitamente per il consumo di batteria
  (con FCM il radio del watch resta a riposo tra un messaggio e
  l'altro; il polling lo terrebbe sveglio a intervalli fissi anche
  senza nulla di nuovo).
  - `backend/`: nuovi endpoint `send-message.js` (watch → genitore),
    `send-message-to-child.js` (genitore → watch, auth via ID token
    Firebase invece del token statico del watch), `register-watch-token.js`
    (registra il token FCM del watch), `messages.js` (storico recente,
    usato dal watch che non ha un SDK Firestore completo). Nuova
    sotto-collezione `devices/{id}/messages` (regole: lettura solo
    genitore, scrittura sempre negata al client — ogni messaggio passa
    dal backend perché deve anche innescare la push, stesso motivo di
    `trigger-event.js`). Aggiunta `checkParentAuth` in `_lib/auth.js`.
  - `watch-app/`: registrata una nuova app Android Firebase
    (`com.gwatch.childtracker`, nessuna SHA-1 necessaria — solo
    `firebase-messaging`, niente login Google sul watch).
    `messaging/FcmService.kt` riceve i messaggi (sempre payload
    "data") e li mette in `data/MessageStore.kt`; `ui/ChatScreen.kt`
    li mostra con solo risposte rapide preimpostate e dettatura vocale
    (`RecognizerIntent`) — niente tastiera, impraticabile su un
    display così piccolo per un bambino.
  - `phone-app/`: `ui/ChatScreen.kt` (campo di testo libero, lettura
    via listener Firestore come il resto dell'app). Nuovo
    `data/BackendClient.kt` (OkHttp, unica chiamata REST della
    phone-app) per l'invio, che deve passare dal backend invece che da
    una scrittura Firestore diretta — serve per innescare la push FCM
    nella stessa chiamata (nessun trigger `onDocumentCreated`
    disponibile su Vercel).

### Known limitations
- Non compilato/testato/deployato: backend da pushare su Vercel,
  entrambe le app da aprire in Android Studio per il primo build
  reale (stesso limite di sempre, nessun SDK Android/rete Google
  Maven in questo ambiente).

## [0.17.0] - 2026-09-09

### Added
- `phone-app/`: registrazione reale dell'app Android sul progetto
  Firebase (`child-tracker-7a1f1`) completata via API con la service
  account fornita dall'utente, senza alcun passaggio manuale su
  Console: app registrata (package `com.gwatch.childtracker.phone`),
  `debug.keystore` generato e relativa SHA-1 registrata (necessaria per
  il login Google), `google-services.json` reale scaricato. Entrambi i
  file restano solo in locale (ignorati da git), consegnati
  all'utente.

### Changed
- `phone-app/`: sostituita Google Maps (Maps SDK/Maps Compose) con
  **OpenStreetMap (osmdroid)** per la mappa. Motivo: Google Maps
  Platform richiede una carta di credito collegata al progetto Google
  Cloud e — a differenza di quanto ipotizzato inizialmente — il
  collegamento deve restare **attivo in permanenza** (la fatturazione
  viene verificata a ogni richiesta, non solo alla creazione della
  chiave API), il che va contro la priorità del progetto di non
  richiedere mai una carta se evitabile. L'utente ha scelto
  esplicitamente osmdroid pur sapendo che comporta una mappa meno
  rifinita.
  - `ui/MapScreen.kt` e `ui/GeofenceScreen.kt` riscritti: `MapView` di
    osmdroid incorporato in Compose via `AndroidView`, marker/polyline/
    poligoni al posto delle equivalenti Google Maps Compose,
    `MapEventsOverlay` per il tocco sulla mappa (scelta zona).
  - `TrackerApplication.kt`: inizializzazione osmdroid (user agent
    obbligatorio, cache in `cacheDir` per non richiedere permessi di
    storage esterno).
  - `app/build.gradle.kts`: rimosse le dipendenze Google Maps
    SDK/Maps Compose, aggiunta `org.osmdroid:osmdroid-android`.
  - Rimosso `local.properties.example` (non serve più alcuna chiave
    Maps da configurare).

## [0.16.0] - 2026-09-09

### Added
- `phone-app/`: scaffolding completo dell'app Android per il genitore
  (Kotlin/Compose).
  - `auth/AuthRepository.kt`: login Google classico + Firebase Auth
    (stessa scelta "meno pezzi in movimento" di OkHttp in watch-app).
  - `data/DeviceRepository.kt`: listener Firestore in tempo reale su
    stato dispositivo, storico (48h), geofence, eventi; scrittura
    diretta consentita solo per le geofence (regole di sicurezza).
  - `ui/MapScreen.kt`: mappa (Google Maps Compose) con marker ultima
    posizione, polyline storico, cerchi geofence, card stato
    (batteria/ultimo aggiornamento), lista eventi recenti.
  - `ui/GeofenceScreen.kt`: aggiunta zona per tocco su mappa,
    attiva/disattiva, eliminazione.
  - `messaging/FcmService.kt`: ricezione push SOS/geofence, notifica in
    primo piano, registrazione/aggiornamento token su `parents/{uid}`.
  - Gradle wrapper riusato da `watch-app/` (indipendente dal progetto).

### Known limitations
- Non compilato né testato in questo ambiente, come `watch-app/`.
- Richiede due setup manuali su Firebase/Google Cloud Console non più
  automatizzabili da questa sessione (credenziali service account non
  disponibili in un nuovo container effimero): registrazione app
  Android (`google-services.json` + SHA-1 debug per il login Google) e
  creazione chiave Maps SDK. Istruzioni in `phone-app/README.md`.

## [0.15.0] - 2026-09-09

### Added
- `watch-app/`: scaffolding completo dell'app Wear OS (Kotlin).
  - `location/LocationTrackingService.kt` + `ActivityTransitionReceiver.kt`:
    foreground service con sampling GPS adattivo (10 min da fermo, 1
    min in movimento).
  - `data/PendingLocationStore.kt` + `upload/LocationUploadWorker.kt`:
    buffer locale e upload a batch verso `/api/ingest-location`
    (periodico ogni 15 min, più trigger immediato oltre soglia).
  - `geofence/`: `GeofenceSyncWorker.kt` (sync zone da
    `/api/device-config`), `GeofenceBroadcastReceiver.kt` +
    `GeofenceEventWorker.kt` (transizioni verso `/api/trigger-event`).
  - `sos/SosWorker.kt`: fix posizione ad alta precisione + invio SOS
    come lavoro espedito (bypassa Doze/App Standby).
  - `boot/BootReceiver.kt`: riavvia service e re-registra le geofence
    dopo un riavvio del watch.
  - `ui/MainActivity.kt`: richiesta permessi (inclusa background
    location come step separato, richiesto da Android) + pulsante SOS.
  - `network/BackendClient.kt`: client OkHttp verso i 3 endpoint del
    watch, JSON con `org.json` (nessuna libreria di serializzazione
    aggiuntiva, per minimizzare rischio di incompatibilità di versioni
    non verificabile in questo ambiente).
  - Gradle wrapper generato e incluso nel progetto.

### Known limitations
- Non compilato né testato in questo ambiente: nessun SDK Android,
  nessuna rete verso i repository Google Maven. Richiede Android
  Studio per il primo build reale.

## [0.14.0] - 2026-09-09

### Verified
- Workflow GitHub Actions di pulizia testato con avvio manuale:
  `HTTP 200`, risposta corretta. **Backend MVP chiuso** — prossimo
  passo: watch-app o phone-app.

## [0.13.0] - 2026-09-09

### Verified
- Deploy Vercel confermato funzionante: `/api/cleanup` risponde 401
  senza auth, come atteso. **Backend MVP completo.**

## [0.12.0] - 2026-09-09

### Fixed
- Deploy Vercel ancora bloccato: `vercel.json` aveva sia `"api/*.js"`
  che `"api/cleanup.js"` come pattern separati in `functions`, e
  Vercel assegna ogni file al primo pattern che lo matcha — il
  wildcard "consumava" già `cleanup.js`, lasciando la regola specifica
  senza nulla da matchare. Unificato in un solo pattern `api/*.js` con
  `maxDuration: 30` per tutte le funzioni.

## [0.11.0] - 2026-09-09

### Fixed
- Deploy Vercel bloccato dalla sezione `crons` in `vercel.json` (build
  falliva subito dopo il clone, piano Hobby). Rimossa.

### Changed
- Pulizia storico (`/api/cleanup`) non più invocata da Vercel Cron ma
  da un nuovo workflow **GitHub Actions**
  (`.github/workflows/cleanup-cron.yml`), una volta al giorno,
  avviabile anche manualmente dalla tab Actions del repo.

## [0.10.0] - 2026-09-09

### Added
- `api/_lib/quota.js`: guardia di traffico giornaliera (4.000
  chiamate/giorno per dispositivo) su `ingest-location`,
  `device-config`, `ha-status` e `trigger-event` (tranne i SOS,
  volutamente esenti). Risponde `429` oltre soglia, molto sotto le
  quote gratuite reali di Firestore/Vercel.
- `api/cleanup.js` + Cron Job Vercel (`vercel.json`, una volta al
  giorno): cancella i documenti scaduti in `locations` e `quota`,
  sostituendo la TTL policy nativa di Firestore (che richiederebbe
  Blaze). Autenticato via `CRON_SECRET`.
- Limite di 100 punti per chiamata su `ingest-location` (difesa contro
  batch anomali).
- `firestore.indexes.json`: indici collection-group su `expiresAt`
  (per `locations` e `quota`), necessari alla query di pulizia.
- `devices/{id}/quota/{YYYY-MM-DD}` nel modello dati, esplicitamente
  negato ai client nelle regole Firestore.

### Changed
- Retention storico posizioni: da 48h a **12 mesi** — il consumo di
  storage resta comunque una piccola frazione del GB gratuito
  Firestore anche nello scenario più pesante (vedi CONTEXT.md).

## [0.9.0] - 2026-09-09

### Added
- Firebase Authentication abilitata (provider Google).
- Utenti genitore pre-autorizzati: `cristianozecchi@gmail.com` e
  `benedettagarofalo81@gmail.com`, con relativi documenti
  `parents/{uid}` creati tramite service account.

## [0.8.0] - 2026-09-09

### Added
- Supporto multi-genitore in `firestore.rules`: `isParent()` verifica
  l'esistenza di un documento `parents/{uid}`, non più un UID
  hardcoded — aggiungere un genitore non richiede redeploy.

### Fixed
- **Sicurezza**: impedita la creazione lato client dei documenti
  `parents/{uid}` (`allow create: if false`). Nella prima stesura del
  supporto multi-genitore, un client autenticato con un qualsiasi
  account Google avrebbe potuto creare il proprio documento e
  auto-autorizzarsi alla lettura della posizione del minore. Ora solo
  admin (service account) può pre-creare un genitore autorizzato; il
  genitore può poi solo leggere/aggiornare il proprio documento.

## [0.7.0] - 2026-09-09

### Added
- Backend deployato e verificato su
  https://gwatch-child-tracker.vercel.app: `ha-status` respinge
  richieste senza token o con token errato (401) e risponde
  correttamente con token valido, confermando che la connessione a
  Firestore tramite la service account funziona.

## [0.6.0] - 2026-09-09

### Changed
- **Migrazione backend**: le 4 funzioni spostate da Firebase Cloud
  Functions a **Vercel Functions** (piano Hobby gratuito, nessuna
  carta richiesta), su richiesta esplicita per evitare di collegare
  un metodo di pagamento anche se il costo reale su Blaze sarebbe
  stato 0€. Firestore resta invariato (piano Spark).
- Endpoint rinominati/riorganizzati in `backend/api/`:
  `ingest-location`, `trigger-event` (unisce SOS e geofence
  enter/exit), `device-config`, `ha-status`.
- Il trigger Firestore `onEventCreated` (invio push su nuovo evento)
  è stato eliminato: `trigger-event` scrive l'evento e invia la push
  FCM nella stessa chiamata, senza bisogno di un trigger separato.

### Removed
- `backend/functions/` (Firebase Cloud Functions), sostituito da
  `backend/api/` (Vercel Functions).

## [0.5.0] - 2026-09-09

### Added
- Progetto Firebase reale collegato: `child-tracker-7a1f1`.
- Deploy eseguito: regole Firestore (`firestore:rules`) live sul
  progetto reale; database Firestore di default creato.

### Changed
- Precisazione costi in `CONTEXT.md`: le Cloud Functions richiedono il
  piano **Blaze** (pay-as-you-go), non attivabile restando su Spark —
  vincolo Google (Cloud Build/Artifact Registry), non una nostra
  scelta. Costo reale atteso comunque 0€/mese entro le quote gratuite,
  identiche tra Spark e Blaze.

### Blocked
- Deploy Cloud Functions in attesa che l'utente colleghi la
  fatturazione (Blaze) dalla Console Firebase — passaggio che richiede
  browser/carta, non automatizzabile da qui.

## [0.4.0] - 2026-09-09

### Added
- `backend/`: scaffolding Firebase completo (`firebase.json`,
  `.firebaserc`, `firestore.rules`, `firestore.indexes.json`).
- Cloud Functions MVP in `backend/functions/index.js`:
  - `POST /ingestLocation` — ingest batch posizioni dal watch.
  - `POST /triggerSos` — evento SOS, priorità/latenza separata dal
    flusso posizione normale.
  - `GET /deviceConfig` — geofence attive per il watch.
  - `GET /haStatus` — lettura stato per il polling opzionale di Home
    Assistant.
  - Trigger `onEventCreated` — invio push FCM al genitore su nuovo
    evento (SOS in MVP).
- Modello dati Firestore documentato in `backend/README.md`
  (`devices`, `locations`, `geofences`, `events`, `parents`).
- Retention storico posizioni via TTL policy Firestore nativa (campo
  `expiresAt`, 48h) invece di una Cloud Function di pulizia dedicata.

## [0.3.0] - 2026-09-09

### Changed
- **Correzione** rispetto a v0.2.0: alert batteria scarica, notifiche
  geofence e modalità scuola tornano ad essere funzioni **native
  dell'app** (push FCM), non più delegate a Home Assistant. L'app deve
  restare pienamente funzionante e sicura anche senza Home Assistant
  configurato o raggiungibile.
- L'integrazione Home Assistant è ora esplicitamente documentata in
  `CONTEXT.md` come livello **opzionale e aggiuntivo**, mai come
  dipendenza dell'infrastruttura core.

## [0.2.0] - 2026-09-09

### Added
- Requisito: integrazione con Home Assistant (mappa, automazioni,
  avvisi). Documentata in `CONTEXT.md` la scelta architetturale
  (polling REST da HA verso una Cloud Function, nessuna esposizione di
  HA su internet).

### Changed
- Backlog Fase 2/3: alert batteria scarica, notifiche geofence e
  modalità scuola spostati da "sviluppo custom in app" a "delegati ad
  automazioni Home Assistant", per ridurre lo sviluppo necessario.

## [0.1.0] - 2026-09-09

### Added
- Struttura iniziale del repository: moduli `watch-app/`, `phone-app/`,
  `backend/`.
- `CONTEXT.md`: file di contesto di progetto, da aggiornare ad ogni
  commit significativo.
- `CHANGELOG.md`: questo file.
- `.gitignore` per Android/Gradle/Firebase.
