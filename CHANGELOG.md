# Changelog

Tutte le modifiche rilevanti al progetto sono documentate in questo file.

Formato basato su [Keep a Changelog](https://keepachangelog.com/it/1.0.0/),
versionamento secondo [Semantic Versioning](https://semver.org/lang/it/).

## [Unreleased]

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
