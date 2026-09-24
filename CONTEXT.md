# Contesto di progetto — gwatch-child-tracker

> Questo file va aggiornato ad ogni commit significativo: riflette lo stato
> attuale del progetto, le decisioni prese e il motivo per cui sono state
> prese. Non è uno storico (per quello c'è CHANGELOG.md), è una fotografia
> del "dove siamo e perché".

**Versione contesto:** 0.101.1
**Ultimo aggiornamento:** 2026-09-24

---

## Obiettivo del progetto

App per tracciare la posizione di un figlio minorenne tramite Samsung
Galaxy Watch4 LTE, con app companion sul telefono del genitore. Nasce
perché l'account Google Family Link del minore non è collegabile al
Watch4 (la modalità "Family Link su Wear OS" è supportata solo dal
Galaxy Watch7 LTE in poi) — soluzione: account Google adulto dedicato
sul watch, tracciamento gestito da un sistema proprietario indipendente
da Family Link.

## Decisioni architetturali correnti

- **Priorità dichiarata dall'utente:** risparmio economico. Non privacy
  end-to-end (scartata volontariamente), non progettazione per rivendita
  futura (scartata: vincoli legali/compliance troppo onerosi per il
  ritorno atteso, vedi log decisioni).
- **Backend:** ibrido, per restare a costo zero senza carta collegata
  da nessuna parte.
  - **Firestore** (Firebase, piano Spark) come datastore: gratuito,
    nessuna carta richiesta.
  - **Vercel Functions** (piano Hobby gratuito, nessuna carta) come
    compute al posto delle Cloud Functions di Firebase, che
    richiedono il piano Blaze (pay-as-you-go, serve una carta) —
    vincolo Google per Cloud Build/Artifact Registry, non evitabile
    restando su Cloud Functions. Stesso codice Node.js
    (`firebase-admin`), solo il "contenitore" cambia.
  - Deploy automatico su Vercel ad ogni push (import diretto del repo
    GitHub, root directory `backend`).
  - **GitHub Actions** per la pulizia programmata dello storico
    (`/api/cleanup` chiamato una volta al giorno): i Cron Job nativi
    di Vercel bloccavano il deploy su piano Hobby, GitHub Actions è
    gratuito e senza questa dipendenza.
- **Mappa:** OpenStreetMap via libreria **osmdroid**, non Google Maps
  SDK. Motivo: Google Maps Platform richiede una fatturazione **attiva**
  ad ogni chiamata (non basta crearla una volta e poi scollegare la
  carta — verificano ad ogni richiesta), in conflitto con la scelta
  "mai una carta se evitabile" fatta per tutto il resto dello stack.
  osmdroid non richiede alcuna chiave API né fatturazione. Contropartita
  accettata: mappa meno curata (niente vista satellite/traffico).
- **Auth:** Firebase Authentication, legata all'account Google del
  genitore (non a quello "adulto" del watch, che resta solo login
  tecnico del dispositivo).
- **Chat testuale genitore↔watch:** consegna via **push FCM**, non
  polling. Motivo: con FCM il radio del watch resta a riposo e si
  sveglia solo quando arriva un messaggio; il polling lo terrebbe
  sveglio a intervalli fissi anche senza nulla di nuovo, consumo
  batteria significativo su un dispositivo LTE standalone. Per
  simmetria con `trigger-event.js` (niente trigger Firestore
  `onDocumentCreated` disponibile su Vercel), ogni invio passa da un
  endpoint backend che scrive il messaggio *e* invia la push nella
  stessa chiamata — mai una scrittura diretta su Firestore dal client,
  a differenza delle geofence.
- **Backup storico:** esportazione periodica su Google Drive del
  genitore (sfrutta l'abbonamento Google One già pagato, nessun costo
  aggiuntivo). Da implementare in Fase 2.
- **Distribuzione app:**
  - Sviluppo: ADB via WiFi (Developer options sul watch), nessun cavo.
  - Produzione (watch reale del figlio): Play Console → Internal
    Testing track (account sviluppatore già pagato). App privata,
    aggiornamenti automatici via Play Store, zero cavo dopo il primo
    setup.
- **Integrazione Home Assistant:** pull-based, non push. HA interroga
  (piattaforma `rest`, polling ogni N minuti) l'endpoint
  `/api/ha-status` (funzione Vercel), protetto da token statico, che
  legge l'ultima posizione/batteria da Firestore. Nessuna esposizione di Home Assistant
  su internet richiesta (no VPN/tunnel/port-forwarding), anche se
  l'utente ha comunque HA raggiungibile via Nabu Casa.
  **Vincolo esplicito: è un livello aggiuntivo opzionale.** L'app
  (watch + phone + backend) deve funzionare in autonomia completa anche
  senza Home Assistant configurato o raggiungibile — nessuna funzione di
  sicurezza (SOS, geofence, alert batteria) può dipendere dalla sua
  presenza. HA aggiunge solo una seconda vista/mappa e la possibilità di
  automazioni personalizzate lato utente, in parallelo a quanto l'app
  già fa nativamente.

## Stato attuale (2026-09-24) — leggere per primo dopo una compattazione

Fotografia sintetica dopo la lunga sessione del 23-24/9; il dettaglio e'
nel "Log decisioni" in fondo e in CHANGELOG.md (v0.74.0 → v0.98.0).

**Branch unico di lavoro**: `claude/child-geolocation-smartwatch-dblfrv`
(e' anche quello che Vercel deploya). Versioni correnti: watch-app
**v0.35.0**, phone-app **v0.26.0**, backend `trigger-event.js` **v0.24.0**
(`parent-command.js`/`device-config.js` v0.5.0),
Node.js **24**. Le app Android non si compilano in queste sessioni
(nessun SDK): build e prova le fa l'utente in Android Studio.

**Fatti chiave emersi (non ripetere le indagini):**
- **qwen_plan.md completato** (5 fasi + punto 6 via budget di tempo in
  `cleanup.js`, niente `vercel.json`); CONTEXT.md resta un file unico
  per scelta dell'utente. Migrazione `familyId` eseguita e
  `firestore.rules` v0.7.0 pubblicate (23/9 sera), testate sul telefono.
- **`vercel.json` non tollera un pattern specifico accanto a
  `api/*.js`**: ha rotto il deploy due volte (v0.12.0 e v0.73.0).
- **Causa del crollo dei punti dal 20/9**: sul watch "Migliora
  precisione" (posizione di rete Google) era **spenta** dopo la scarica
  completa del 20/9. Senza, in casa resta solo il GPS e anche le
  geofence non funzionano. Riattivata dall'utente: punti ogni ~5' anche
  in casa. Documentato in `watch-app/README.md` ("Impostazioni
  obbligatorie"). Il codice non aveva regressioni sul GPS.
- Regressione vera della Fase 2: phone-app installata prima della
  migrazione `familyId` → nessuna iscrizione ai topic FCM per qualche
  ora (risolto dalla migrazione).
- Bug vero trovato: gli eventi geofence arrivavano con `battery=null`
  (e lat/lon 0,0) e `trigger-event.js` azzerava batteria/posizione sul
  device → ora aggiorna solo i campi presenti (v0.21.0).

**Aggiunto in questa sessione (tutto verificato dall'utente salvo dove
indicato):** Ricerca GPS con barre satelliti, diagnostica, reset dati
GPS e iniezione A-GPS (`GpsAssist`); tracking da fermo tornato a
priorita' bilanciata (24/9, decisione dell'utente) con interruttore
"Alta precisione da fermo" per bambino nelle Impostazioni della
phone-app (`TrackingMode`, azione `set_tracking_mode`); pulsante "Invia posizione" a colori (barra blu /
verde / rosso); batteria anche senza fix (`status`) con avvisi di
batteria scarica; satelliti visti/agganciati o "GPS non usato" sul
telefono; "adesso" che avanza; verde batteria leggibile; tracking
riavviato dopo aggiornamenti dell'app (`MY_PACKAGE_REPLACED`) e da push
di richiesta posizione (lavoro espedito, funziona ad app chiusa);
telefono che insiste nella richiesta di posizione con barra "in attesa"
/ "nuovo tentativo tra N s" (fino a 20 tentativi); avvisi modalita'
aereo / spegnimento / riaccensione con icona ✈️ ⏻ 📵 sul telefono.

**Da verificare dall'utente (ultime modifiche, non ancora provate):**
phone-app v0.23.0 (barra che sparisce all'arrivo della posizione dopo
una riaccensione); deploy Vercel su Node 24 andato a buon fine; numero
di satelliti visibile con un fix davvero GPS (all'aperto); batteria che
resta dopo un evento zona; tracking continuo per un'intera giornata
(`--hourly`).

**Strumento di diagnostica sul campo** (autorizzato in modo permanente,
vedi CLAUDE.md): `node backend/scripts/diag-device-history.js [giorni]
[--all|--track|--hourly]` — stato del device, storico eventi, posizioni
con precisione, distanze e riepilogo orario, ora italiana, mai
coordinate stampate.

**Aperto/backlog**: satelliti/"status" non inviati dal tracking periodico, solo dai tentativi
manuali/remoti; avvisi di stato del watch dipendono dal servizio di
tracking attivo; backlog Fase 2/3 invariato (sotto).

## Stato implementazione

- **backend/**: **live e verificato**, deployato su
  https://gwatch-child-tracker.vercel.app. Endpoint MVP implementati
  come funzioni Vercel (`ingest-location`, `trigger-event`,
  `device-config`, `ha-status`). Progetto Firebase reale
  (`child-tracker-7a1f1`), Firestore + regole di sicurezza deployati.
  Verifica end-to-end fatta: auth respinta senza token (401), respinta
  con token errato (401), accettata con token corretto e Firestore
  raggiunto correttamente (404 "device non trovato", atteso perché
  nessuna posizione ancora inviata — il watch-app non esiste ancora).
  Multi-genitore supportato: le regole autorizzano chiunque abbia un
  documento in `parents/{uid}` (nessun UID hardcoded), creabile solo
  da admin per evitare auto-autorizzazione da parte di account Google
  arbitrari. I due genitori sono già pre-autorizzati (vedi log
  decisioni). Storico posizioni esteso a 12 mesi (margine ampio nel
  piano gratuito, vedi log decisioni); pulizia automatica via un
  workflow **GitHub Actions** (`/api/cleanup` chiamato una volta al
  giorno) invece della TTL nativa Firestore (richiederebbe Blaze) o
  dei Cron Job Vercel (bloccavano il deploy su piano Hobby). Guardia
  di traffico giornaliera attiva su tutti gli endpoint (SOS escluso).
- **watch-app/**: scaffolding completo (Wear OS, Kotlin) — sampling
  GPS adattivo, upload a batch, geofence, SOS espedito, gestione
  permessi/riavvio, **chat testuale via push FCM** (risposte rapide +
  dettatura vocale, niente tastiera). **Setup Firebase completo**: app
  Android registrata sul progetto reale (`com.gwatch.childtracker`),
  `google-services.json` scaricato via API (nessuna SHA-1 necessaria:
  solo FCM, niente login Google sul watch). **Build verificato in
  Android Studio (2026-09-10)**: sync Gradle e compilazione riusciti
  sulla macchina dell'utente. Ancora da testare su hardware reale
  (Watch4).
- **phone-app/**: scaffolding completo (Android, Kotlin/Compose) —
  login Google (solo genitori pre-autorizzati), mappa OpenStreetMap
  (osmdroid) in tempo reale via listener Firestore (nessun endpoint
  backend dedicato per la lettura), gestione geofence (unica scrittura
  diretta dal client, come da regole di sicurezza), **chat testuale**
  col watch (lettura via listener Firestore, invio via endpoint
  backend per la push FCM), notifiche push su SOS/geofence/chat,
  registrazione token FCM su `parents/{uid}`. **Setup Firebase
  completo**: app Android registrata sul progetto reale
  (`com.gwatch.childtracker.phone`), `google-services.json` scaricato,
  keystore di debug generato e il suo SHA-1 registrato per il login
  Google — nessun passaggio manuale rimasto lato utente (vedi log
  decisioni). **Build verificato in Android Studio (2026-09-10)**: sync
  Gradle e compilazione riusciti sulla macchina dell'utente. Ancora da
  testare login Google/mappa/notifiche su device reale.
- **backend/**: aggiunti 4 endpoint per la chat (`send-message`,
  `send-message-to-child`, `register-watch-token`, `messages`) e la
  sotto-collezione `devices/{id}/messages` (regole: lettura solo al
  genitore, scrittura sempre negata al client). Nuovo meccanismo di
  auth per `send-message-to-child`: ID token Firebase del genitore
  (`checkParentAuth`, verificato con l'Admin SDK), diverso dal token
  statico usato dal watch — non ancora deployato su Vercel (serve un
  push del branch).

## Stato aggiornato al 2026-09-10 (sessione di test su hardware reale)

Prima sessione di build/test reale su Android Studio (utente) dopo lo
scaffolding. Risultato: **backend deployato e verificato su Vercel**
(branch tracking, env vars, indice Firestore `messages/expiresAt`
tutti confermati attivi), **phone-app installata su device reale e
login funzionante**. Bug trovati durante il test e già corretti,
committati e pushati (non ancora ri-verificati dall'utente dopo il
fix):

- `Modifier.weight` non risolveva a build reale sia nel watch-app che
  nella phone-app (stessa causa, mai isolata con certezza — probabile
  disallineamento di versione fra gli artifact Compose fissati in
  `build.gradle.kts`) — riscritti tutti i layout coinvolti senza
  `weight` (pattern Box+align o BoxWithConstraints).
  `androidx.wear.compose.material.Divider` non esisteva nella versione
  di Wear Compose Material fissata — sostituito con un separatore
  disegnato a mano. **Lezione operante da qui in avanti**: in questo
  progetto non fidarsi delle API Compose "note" senza build reale a
  disposizione, preferire componenti già verificati o fallback manuali.
- SOS e "Invia posizione" scrivevano l'evento ma non aggiornavano
  `devices/figlio.lastLocation`: il pin sulla mappa della phone-app non
  si muoveva fino al prossimo upload periodico. Corretto.
- Il pulsante "Invia" della chat sul telefono ignorava l'esito
  dell'invio (nessun feedback su fallimento) — probabile causa del
  sintomo riportato "i messaggi non partono". Aggiunto Toast di
  esito. Aggiunto anche logging esplicito degli errori nei listener
  Firestore di `DeviceRepository` (prima silenziosamente ignorati) per
  poter diagnosticare eventuali permission-denied da logcat.

Funzionalità aggiunte in questa sessione, oltre allo scope MVP
originale:

- Pulsante "Invia posizione attuale" sul watch + pulsante "Aggiorna
  posizione" sulla phone-app (richiede al watch un fix immediato via
  push, senza aspettare l'upload periodico a 15 minuti).
- Switch "percorso ultime 24h" sulla mappa (prima si vedeva solo
  l'ultima posizione, mai la polyline storica su richiesta).
- Storico chat con scadenza automatica a 24h (stesso meccanismo
  `expiresAt` + cron GitHub Actions già usato per posizioni/quota).
- **SOS ridisegnato oltre lo scope MVP originale** ("invio one-shot"):
  ora richiede conferma esplicita sul watch prima di attivarsi, e da
  attivo invia la posizione ogni 30 secondi finché il genitore non lo
  disattiva dalla phone-app (nuovo banner rosso con pulsante
  "Disattiva"). Vedi `backend/api/sos-heartbeat.js` e
  `backend/api/cancel-sos.js`.

Bug UX trovato e corretto in questa stessa sessione (test su device
reale, schermata Zone): mappa a altezza fissa (`Modifier.height(220.dp)`)
che lasciava meta' schermo bianco sotto, e nessuna barra strumenti per
gestire le zone. Riscritta `phone-app/.../ui/GeofenceScreen.kt` sullo
stesso pattern Box+align gia' in uso in `MapScreen.kt`: mappa a
`fillMaxSize()`, pannello "strumenti" flottante in alto (nome/raggio/
Salva/**Annulla**, quest'ultimo nuovo — prima non c'era modo esplicito
di annullare un punto scelto per errore) e lista zone flottante in
basso con sfondo opaco (stessa lezione della trasparenza di
`EventsList` in `MapScreen.kt`, applicata qui subito senza aspettare
di ripetere l'errore). In piu': le zone gia' salvate ora si vedono come
cerchi anche su questa mappa (prima solo su `MapScreen.kt`), e il
raggio scelto con lo slider ha un'anteprima live (cerchio verde) prima
di salvare.

**Nessun limite al numero di zone** codificato nell'app: il solo
vincolo reale e' quello di sistema della Geofencing API Android (max
100 geofence per app), molto oltre il bisogno pratico.

Richiesta successiva dell'utente, implementata nella stessa sessione:
toggle per-zona per notificare solo all'ingresso o solo all'uscita
(prima erano sempre accoppiati su un solo switch attiva/disattiva), e
un toggle "allarme sonoro all'uscita" — suona e vibra ripetutamente sul
telefono del genitore finche' non lo si ferma, non solo una notifica
passiva. Implementazione:
- `GeofenceZone` (watch/phone) guadagna `notifyOnEnter`/`notifyOnExit`
  (default true, comportamento invariato per le zone gia' esistenti) e
  `alarmOnExit` (default false, opt-in esplicito).
- **Bug collaterale trovato e corretto nello stesso giro**: il campo
  che il watch mandava a `trigger-event.js` come "zoneName" era in
  realta' sempre stato il **requestId Firestore** della zona
  (`GeofenceBroadcastReceiver.kt` lo prende da
  `triggeringGeofences.firstOrNull().requestId`, mai il nome
  leggibile) — le notifiche di ingresso/uscita mostravano quindi l'id
  al posto del nome fin dall'inizio. Rinominato in `zoneId` end-to-end
  (watch -> backend) e il backend ora risolve il nome vero leggendo il
  documento zona da Firestore con quell'id — necessario comunque per
  leggere i nuovi toggle, quindi corretto "gratis" nello stesso
  passaggio di codice.
- `backend/api/trigger-event.js` (v0.6.0): per ogni `geofence_enter`/
  `geofence_exit` legge la zona da Firestore (nome + i 3 toggle),
  decide se notificare in base a `notifyOnEnter`/`notifyOnExit`, e se
  `alarmOnExit` e' true manda un **secondo** messaggio FCM **data-only**
  `{type: "exit_alarm", zoneName}` (oltre alla normale notifica, non al
  suo posto) — data-only e non notification+data perche' deve poter
  avviare l'allarme sul telefono anche ad app in background/uccisa, non
  solo quando l'utente tocca una notifica di sistema gia' mostrata.
- Phone-app: nuovo `alarm/ExitAlarmService.kt` (foreground Service,
  suono in loop via `MediaPlayer`/`AudioAttributes.USAGE_ALARM` +
  vibrazione a pattern ripetuto via `VibrationEffect.createWaveform`,
  cap di sicurezza 5 minuti se nessuno lo ferma) e
  `alarm/ExitAlarmActivity.kt` (schermata a tutto schermo sopra il
  lockscreen via full-screen intent dalla notifica, un pulsante "Ferma
  allarme"). `FcmService.kt` smista il messaggio `exit_alarm` PRIMA del
  controllo `message.notification` esistente (che altrimenti scarta
  ogni payload data-only).
- `GeofenceScreen.kt`: il pannello di creazione zona ha 3 switch in
  piu'; aggiunta anche la possibilita' di **modificare** una zona
  esistente (prima si poteva solo attivare/disattivare o cancellare,
  mai cambiare nome/raggio/notifiche) — pulsante "Modifica" in lista
  che precarica il pannello, "Salva" aggiorna lo stesso documento
  invece di crearne uno nuovo.

Bug di test reale (continuazione SOS + chat) trovati e corretti nella
stessa sessione, dopo il primo giro di test sull'SOS ridisegnato:

- **SOS senza riscontro visivo sul watch**: la conferma e l'invio
  funzionavano (Toast "SOS inviato"), ma non c'era alcun modo di
  vedere sul watch se l'SOS fosse attivo, ne' quando il genitore lo
  disattivava da remoto (nessun cambiamento visibile). Aggiunto
  `sos/SosState.kt` (StateFlow osservabile, stesso pattern di
  MessageStore), aggiornato da `SosLocationService` al proprio avvio/
  arresto: un banner rosso "🆘 SOS ATTIVO" compare/scompare sulla
  schermata principale del watch, e una notifica esplicita "SOS
  disattivato" viene mostrata quando arriva la push di cancellazione
  dal genitore.
- **Notifica "Posizione aggiornata" ambigua**: quando il genitore
  premeva "Aggiorna posizione" dalla phone-app, la notifica diceva
  sempre "Il bambino ha inviato la posizione attuale" — stesso identico
  evento di quando era davvero il bambino a premere il pulsante sul
  watch, perche' i due percorsi (LocationRequestWorker invocato dal
  pulsante locale o dalla push "location_request") non erano
  distinguibili lato backend. Aggiunto un campo "source" ("child" |
  "parent") end-to-end: la notifica ora dice "Posizione aggiornata su
  tua richiesta" quando e' stato il genitore a chiederla.
- **Messaggi della chat "non si vedono"**: sul watch, un messaggio in
  arrivo finiva in fondo alla LazyColumn (dopo pulsanti azione e
  intestazione storico) senza scroll automatico — invisibile se non si
  scorreva a mano fino in fondo. Aggiunto scroll automatico all'ultimo
  messaggio (stesso pattern gia' in uso nella phone-app). Sulla
  phone-app, un messaggio appena inviato dal genitore compariva solo
  dopo il giro completo scrittura-backend -> lettura del listener
  Firestore (a differenza del watch, che ha gia' un invio ottimistico):
  aggiunto lo stesso pattern ottimistico in `AppViewModel.kt`
  (`_optimisticMessages`, combinato col listener Firestore e
  deduplicato quando il messaggio reale arriva).

Ulteriore giro di feedback su `GeofenceScreen.kt`, tre punti:

1. **Ricerca indirizzo**: la mappa partiva sempre centrata su Roma
   (coordinate fisse nel codice), nessun modo di spostarsi rapidamente
   su un indirizzo vero. Aggiunto `data/GeocodingClient.kt` — chiama
   **Nominatim** (OpenStreetMap), lo stesso servizio gratuito senza
   chiave API dei tile della mappa (coerente con la scelta osmdroid di
   v0.17.0, nessuna fatturazione Google). Barra di ricerca in alto:
   selezionare un risultato centra la mappa li' e apre direttamente il
   pannello di creazione zona su quel punto, come un tocco diretto.
2. **Pannello di creazione zona che copriva il punto appena scelto**:
   prima era ancorato in alto a schermo fisso — se si toccava la mappa
   vicino alla cima, il pannello finiva esattamente sopra, nascondendo
   marker e anteprima del raggio appena posizionati. Spostato in basso
   (sostituisce la lista zone in quel momento, i due non possono stare
   nello stesso posto e mentre si piazza una zona la lista non serve).
3. **Raggio minimo 50m non era un limite tecnico**: ne' della
   Geofencing API di Android ne' di osmdroid, solo il range scelto nel
   codice dello Slider. Allargato a 20-2000m. Sotto ai 30-50m circa
   aumenta il rischio di falsi ingressi/uscite per il solo rumore del
   GPS (mitigato in parte dal loitering delay di 30s sull'uscita), quindi
   il minimo resta comunque non-zero per default.

Confermato dall'utente: le notifiche sono gia' abilitate sul watch
(impostazioni di sistema) — il dubbio aperto sul perche' un messaggio
in arrivo non mostri comunque una notifica (vedi voce precedente nel
log decisioni) NON e' quindi un problema di permesso mancante, resta
un punto da rivalutare con piu' attenzione se il sintomo si ripete dopo
il fix dello scroll automatico appena fatto.

Ulteriore richiesta sulle notifiche del watch, due parti:

1. **Notifiche persistenti**: dovevano restare visibili finche' non
   vengono rimosse esplicitamente, invece di sparire da sole al tocco.
   `postNotification()` (nuovo helper condiviso in `FcmService.kt`,
   fattorizzato da chat/sos_cancel/location_seen) ora usa
   `setAutoCancel(false)` su tutte.
2. **Card "a comparsa" piu' grande/centrata**: l'unica leva che l'app
   ha verso il renderer di sistema di Wear OS per la presentazione
   interattiva di una notifica in arrivo e' importanza del canale (gia'
   `IMPORTANCE_HIGH`) + priorita' della notifica + categoria. Alzata la
   priorita' a `PRIORITY_MAX` (prima `HIGH`) e aggiunta
   `CATEGORY_MESSAGE` per la chat (`CATEGORY_STATUS` per le altre) —
   **limite onesto**: la resa finale a schermo (dimensione/posizione
   esatta) resta decisa dal sistema operativo, non e' un parametro
   impostabile pixel per pixel dall'app; questi sono i segnali
   corretti/standard per chiedere il trattamento piu' prominente, non
   una garanzia assoluta del risultato visivo esatto.

**Nuova funzione**: notifica "Posizione visualizzata" sul watch quando
il genitore vede sulla phone-app una posizione inviata volontariamente
dal bambino (SOS o pulsante "Invia posizione" — non una richiesta
remota del genitore stesso, non avrebbe senso). Nuovo endpoint
`backend/api/ack-event.js` (idempotente, stesso pattern
`wasSosActive` di trigger-event.js per non spammare la notifica se
richiamato piu' volte per lo stesso evento): la phone-app lo chiama da
`MapScreen.kt` (`LaunchedEffect(events)`) appena mostra un evento
"sos"/"location_request" (source "child") non ancora marcato
`acknowledged`. "Visto" qui significa "la schermata mappa con
quell'evento e' stata composta", non richiede un tocco esplicito del
genitore.

### Aperto/da fare (non ancora chiuso)

- **Test su hardware reale del watch-app non ancora confermato**: la
  phone-app è installata e loggata su device reale, ma non risulta
  ancora conferma che il watch-app sia stato installato/avviato sul
  Galaxy Watch4 fisico. Finché non succede, SOS/geofence/chat/sampling
  adattivo restano verificati solo a livello di build, non di
  comportamento reale.
- Tutte le correzioni di questa sessione (weight, lastLocation, chat
  send, SOS) sono state pushate ma **non ancora ricompilate/ritestate**
  dall'utente dopo il fix — da fare al prossimo giro di test.
- `devices/{id}/events` non ha retention/pulizia automatica (a
  differenza di locations/quota/messages): cresce senza limite nel
  tempo. Impatto reale basso nel breve termine (storage trascurabile
  nel piano gratuito), ma da aggiungere alla pulizia di
  `cleanup.js` in Fase 2 se il volume di eventi cresce (SOS/geofence/
  richieste posizione ora più frequenti con le nuove funzioni).
- Backlog Fase 2 invariato (vedi sotto) — nessuna voce ancora
  iniziata: backup Drive, alert batteria scarica, modalità scuola,
  check-in volontario, alert "watch offline", riepilogo
  giornaliero/settimanale.

## Confronto con servizi esistenti sul mercato

Rivalutazione richiesta dall'utente: perché una soluzione custom
invece di un prodotto già pronto? Confronto sulle dimensioni che
contano per questo caso d'uso (bambino con Galaxy Watch4 LTE già
posseduto, priorità dichiarata: risparmio economico).

| Soluzione | Costo ricorrente | Richiede nuovo hardware? | SOS/geofence/chat | Dati/privacy | Nota |
|---|---|---|---|---|---|
| **Questa app (custom)** | 0€ (piani gratuiti Firebase Spark + Vercel Hobby + GitHub Actions) | No, riusa il Watch4 già posseduto | Sì, tutte e tre native | Dati propri, su progetto Firebase personale | Costo reale: solo tempo di sviluppo/manutenzione; nessun SLA, nessun supporto |
| **Google Family Link su Wear OS** | 0€ | **Sì** — supportato solo da Galaxy Watch7 LTE in poi, non su Watch4 | Sì (tramite ecosistema Google) | Dati su infrastruttura Google | Motivo stesso per cui esiste questo progetto: incompatibile con l'hardware già posseduto |
| **Life360** | Gratis con limiti, piani a pagamento ~5-25$/mese per le funzioni avanzate (geofence multiple, driving reports, storico esteso) | No (app su smartphone) | Geofence sì, SOS sì (piani a pagamento), chat no | Dati su infrastruttura Life360 (ha avuto casi noti di vendita dati di localizzazione a data broker, poi sospesa dopo scrutinio pubblico) | Pensato per smartphone, non per un watch standalone senza telefono al seguito |
| **Watch per bambini "chiavi in mano"** (GizmoWatch/Verizon, TickTalk, Xplora e simili) | Abbonamento dati dedicato ~5-15$/mese *oltre* al costo del dispositivo | **Sì**, dispositivo proprietario dedicato (il Watch4 già posseduto non è riusabile) | Sì, tutte native (spesso anche chiamate vocali) | Dati sull'infrastruttura del produttore, opaca, nessun controllo | Esperienza più "finita"/rifinita, ma doppia spesa (device + abbonamento) e nessuna possibilità di personalizzazione |
| **Jiobit / tracker dedicati** | Abbonamento ~5-15$/mese | Sì, tracker dedicato separato dal watch | Solo geofence/posizione, no chat/SOS nel senso pieno | Dati sull'infrastruttura del produttore | Pensato per tracciamento puro, non sostituisce un watch indossabile con funzioni proprie |

**Conclusione (invariata rispetto alla decisione già presa in avvio
progetto):** nessuna soluzione pronta copre il caso "Galaxy Watch4 LTE
già posseduto, zero spesa ricorrente, nessuna carta collegata in modo
permanente". La soluzione custom è l'unica a costo zero che riusa
l'hardware esistente; il prezzo pagato è l'assenza di
supporto/manutenzione professionale e il tempo di sviluppo/testing,
esattamente il trade-off già accettato in partenza (vedi "Decisioni
architetturali correnti" sopra: priorità dichiarata = risparmio
economico, non privacy end-to-end né commercializzazione).

## Scope MVP (v1 — in sviluppo ora)

- Posizione in tempo reale su richiesta
- Ultima posizione nota + batteria + timestamp
- Geofence base: casa + scuola, notifica ingresso/uscita
- Sampling GPS adattivo (risparmio batteria: intervallo lungo da fermo,
  breve in movimento, via ActivityRecognitionClient)
- SOS: pulsante watch → posizione immediata + push al genitore
- Storico spostamenti minimo (ultime 24-48h) su mappa nel telefono

## Backlog Fase 2 (dopo MVP)

- Backup automatico storico su Google Drive
- Alert batteria scarica del watch (nativo in app, push FCM)
- Modalità scuola (silenzia/limita notifiche in fasce orarie, nativo
  in app)
- Check-in volontario ("sto bene")
- Alert "watch offline"
- Riepilogo giornaliero/settimanale luoghi visitati
- Multi-genitore (secondo account che vede lo stesso watch)
- Endpoint REST (Cloud Function) per polling da Home Assistant
  (posizione/batteria correnti, autenticato con token statico) — vedi
  "Integrazione Home Assistant" sopra

## Integrazione Home Assistant (opzionale, aggiuntiva — non sostitutiva)

Tutte le funzioni sotto sono già coperte nativamente dall'app (vedi
Fase 2). Chi ha Home Assistant può *in più*, se vuole, ricostruirle
come automazioni proprie usando i dati esposti dall'endpoint REST:
mappa/person entity, notifiche di ingresso/uscita zona, automazioni
personalizzate. Nessuna di queste sostituisce l'equivalente nativo
dell'app: se HA è spento o non configurato, sicurezza e alert dell'app
continuano a funzionare senza alcuna degradazione.

## Backlog Fase 3 (eventuale)

- Messaggi rapidi preimpostati verso contatti approvati
- Chiamate/messaggi whitelist
- Widget posizione sulla home del telefono
- Escalation SOS con reinvio automatico se non confermato

### Complicazione watch (stato SOS/messaggi/invio) — IN STANDBY

Richiesta utente (2026-09-18): integrare stato SOS, invio posizione,
messaggio inviato/ricevuto in una watchface. **Deliberatamente NON
avviata ora** — piano scritto e messo in backlog su richiesta esplicita
("lasciamo il punto in standby per una fase successiva"), da
riprendere in una fase futura dopo aver stabilizzato i bug GPS/
notifiche attualmente in corso.

**Approccio scelto: Complicazione, non una watchface custom.** Una
watchface sostitutiva (WatchFaceService, rendering canvas proprio,
gestione manuale della modalità ambient/basso consumo) è un
sottosistema enormemente più grosso e costringerebbe l'utente ad
abbandonare il quadrante che già usa. Una **complicazione**
(`androidx.wear.watchface.complications.datasource.ComplicationDataSourceService`)
è invece un piccolo riquadro dati che l'utente aggiunge al SUO
quadrante attuale (se lo supporta), stesso meccanismo di "batteria"/
"passi" già familiare su Wear OS.

**Limite intrinseco**: una complicazione mostra un solo stato alla
volta (icona + testo breve, tipo `SHORT_TEXT`), non 4 notifiche
distinte in contemporanea. Priorità proposta (il primo stato vero
vince):
1. SOS attivo (icona rossa, "SOS attivo")
2. Messaggio non letto dal genitore (icona busta, nome mittente o
   conteggio)
3. Esito ultimo invio posizione/SOS (✓ inviato / in corso / GPS
   assente — riusa lo stato già esposto da `GpsAvailability`/gli esiti
   dei worker)
4. Altrimenti: stato "a riposo" (nessuna icona particolare, o ultima
   posizione inviata)

Se il quadrante dell'utente ha più slot liberi, si può eventualmente
duplicare su 2 slot (es. uno per SOS/messaggi, uno per stato invio) —
da valutare in fase di implementazione reale, non bloccante per il
disegno.

**Componenti da aggiungere (watch-app)**:
- Nuovo `ComplicationDataSourceService` (servizio, non Activity) —
  risponde a `onComplicationRequest` restituendo il tipo `SHORT_TEXT`
  con icona+testo secondo la priorità sopra, letto da uno stato locale
  cache (non da una query Firestore sincrona: il sistema chiama questo
  servizio on-demand e si aspetta una risposta rapida).
- Lo stato cache va aggiornato dagli stessi punti che già esistono:
  `SosState` (SOS attivo/disattivo), un nuovo stato "messaggi non
  letti" (da agganciare a `FcmService`/`ChatScreen.kt`, non ancora
  tracciato oggi in nessun singleton), l'esito dei worker
  (`LocationRequestWorker`/`SosWorker`, già esposto in parte da
  `GpsAvailability`). Dopo ogni cambiamento, chiamare
  `ComplicationDataSourceUpdateRequester` per dire al sistema di
  richiedere subito un refresh (altrimenti la complicazione si
  aggiorna solo ai refresh periodici schedulati dal sistema, non in
  tempo reale).
- Tap sulla complicazione: apre l'app sulla schermata pertinente
  (chat se il trigger è un messaggio, schermo principale altrimenti) —
  `PendingIntent` standard, stesso pattern già usato per le notifiche
  push esistenti (`FcmService.kt`).
- Dichiarazione in `AndroidManifest.xml` (nuovo `<service>` con
  intent-filter `android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST`
  + metadata XML che elenca i tipi di dato supportati) — componente
  mai usato finora in questo progetto, quindi zero rischio di
  regressione su codice esistente, ma anche zero esperienza pregressa
  da riusare: la prima implementazione andrà verificata su hardware
  reale più del solito (nessun modo di simulare le complicazioni da
  questa sessione, che non ha un Android SDK).

**Passo manuale utente (non automatizzabile)**: aggiungere la
complicazione al proprio quadrante è un'azione che l'utente deve fare
lui stesso dalle impostazioni del quadrante sul watch (Wear OS non
permette a un'app di auto-installarsi come complicazione attiva) —
va documentato chiaramente al momento del rilascio, incluso il fatto
che funziona solo su quadranti che supportano complicazioni
personalizzate (non tutti i quadranti preinstallati Samsung lo fanno).

**Stima rischio/sforzo**: medio — nessuna riscrittura di codice
esistente, ma un componente Android interamente nuovo per questo
progetto. Da avviare solo a bug GPS/notifiche correnti chiusi.

## Struttura repo

```
/watch-app    Wear OS app (Kotlin) — installata sul Galaxy Watch4
/phone-app    App Android (Kotlin) — usata dal genitore
/backend      Funzioni Vercel (api/), regole/indici Firestore, scripts/
              (migrate-family-ids.js, diag-device-history.js)
.github/      Workflow: pulizia notturna storico, test backend (Node 24)
CLAUDE.md     Istruzioni per Claude (documentazione, diagnostica)
CONTEXT.md    Questo file
CHANGELOG.md  Storico versioni
```

## Log decisioni (cronologico, sintetico)

- 2026-09-09: Scartato l'approccio "login Family Link sul watch" —
  Watch4 non supportato (solo Watch7 LTE+). Adottato account Google
  adulto dedicato + tracciamento custom.
- 2026-09-09: Confermato che l'account Google One (storage/VPN)
  dell'utente non riduce i costi Firebase/GCP (fatturazione separata),
  ma è utile come storage gratuito per il backup storico su Drive.
- 2026-09-09: Deciso di NON progettare per una futura vendita
  commerciale: i vincoli legali per un prodotto di child-tracking
  (GDPR-K/COPPA, policy Play Store per app "child-directed", audit di
  sicurezza) sono sproporzionati rispetto a un MVP personale. Rivalutare
  come progetto separato se in futuro serve.
- 2026-09-09: Scaffolding iniziale del repository (v0.1.0).
- 2026-09-09: Aggiunta integrazione Home Assistant (mappa, automazioni,
  avvisi) come requisito. Scelto approccio pull via REST (HA interroga
  un endpoint Cloud Function) invece di push/MQTT, per non dover
  esporre Home Assistant su internet. Diverse voci di Fase 2/3 (alert
  batteria, notifiche geofence, modalità scuola) spostate da "da
  sviluppare in app" a "delegate ad automazioni HA", per ridurre lo
  sviluppo custom (v0.2.0).
- 2026-09-09: **Corretto** il punto precedente — l'utente ha HA
  raggiungibile via Nabu Casa ma non vuole che l'infrastruttura di
  sicurezza dipenda da HA. Alert batteria, geofence e modalità scuola
  tornano ad essere funzioni native dell'app (push FCM), non delegate.
  L'integrazione HA resta come livello aggiuntivo opzionale in
  parallelo, mai come dipendenza (v0.3.0).
- 2026-09-09: Implementato lo scaffolding funzionante del backend
  Firebase: 4 Cloud Functions HTTPS (`ingestLocation`, `triggerSos`,
  `deviceConfig`, `haStatus`), un trigger Firestore per le push SOS,
  regole di sicurezza, e la retention storico via TTL Firestore
  (gratuita, nessuna Cloud Function schedulata dedicata). Documentato
  il setup manuale richiesto in `backend/README.md` (v0.4.0).
- 2026-09-09: Progetto Firebase reale creato (`child-tracker-7a1f1`).
  Regole Firestore deployate con successo tramite service account
  dedicata. Deploy Cloud Functions **bloccato**: richiedono piano
  Blaze (pay-as-you-go), non disponibile su Spark — vincolo Google,
  non evitabile restando su Cloud Functions. Costo reale atteso
  comunque 0€/mese (stesse quote gratuite di Spark), in attesa che
  l'utente colleghi la fatturazione dalla Console (passaggio che
  richiede browser, non automatizzabile) (v0.5.0).
- 2026-09-09: L'utente preferisce evitare del tutto una carta
  collegata, anche se il costo reale sarebbe 0€. Migrate le 4
  funzioni da Firebase Cloud Functions a **Vercel Functions** (piano
  Hobby gratuito, nessuna carta): stesso codice Node.js/firebase-admin,
  Firestore resta invariato su Spark. Il trigger Firestore automatico
  su nuovo evento (non disponibile fuori da Firebase Functions) è
  stato sostituito unendo scrittura evento + invio push FCM nella
  stessa chiamata dell'endpoint `trigger-event` (v0.6.0).
- 2026-09-09: Deploy backend su Vercel completato e verificato
  end-to-end (auth + connessione Firestore funzionanti) su
  https://gwatch-child-tracker.vercel.app (v0.7.0).
- 2026-09-09: Richiesto supporto multi-genitore (padre + madre).
  Riscritte le regole Firestore: `isParent()` ora controlla
  l'esistenza di un documento `parents/{uid}` invece di un UID
  hardcoded, così aggiungere un genitore non richiede più redeploy.
  **Corretto un problema di sicurezza** introdotto da questo cambio
  nella prima stesura: la creazione del documento `parents/{uid}` è
  vietata dal client (`allow create: if false`), possibile solo da
  admin — altrimenti chiunque avesse un account Google potrebbe
  auto-crearsi il documento e ottenere accesso alla posizione del
  minore. Il genitore può solo leggere/aggiornare il proprio documento
  una volta pre-creato. Deployato (v0.8.0).
- 2026-09-09: Abilitata Firebase Authentication (Console, provider
  Google) — richiedeva un click una tantum via browser, la via API
  automatica portava a un percorso a pagamento (Identity Platform) da
  evitare. Creati i due utenti genitore (`cristianozecchi@gmail.com`,
  `benedettagarofalo81@gmail.com`) e i relativi documenti
  `parents/{uid}` tramite service account (v0.9.0).
- 2026-09-09: Richiesto di avere uno storico più lungo delle 48h
  restando gratis, e di codificare nel programma limiti di traffico
  invalicabili verso Firestore/Vercel. Calcolato che anche nello
  scenario più pesante lo storage resta una piccola frazione del GB
  gratuito: retention estesa da 48h a **12 mesi**. Aggiunta una guardia
  di quota giornaliera (4.000 chiamate/giorno per dispositivo, molto
  sotto le soglie gratuite reali) su tutti gli endpoint tranne l'SOS
  (volutamente esente, funzione di sicurezza critica). Scoperto che
  anche la **TTL policy nativa di Firestore richiede Blaze**: sostituita
  con un Cron Job Vercel giornaliero (`/api/cleanup`, gratuito su
  Hobby) che cancella i documenti scaduti. Indici collection-group
  necessari deployati senza problemi di billing (v0.10.0).
- 2026-09-09: Il Cron Job Vercel bloccava il deploy su piano Hobby
  (build falliva subito dopo il clone, nessun log — probabile
  restrizione/eleggibilità dell'account su `crons` in `vercel.json`).
  Rimosso `crons` da `vercel.json`; la pulizia programmata
  (`/api/cleanup`) è ora invocata da un **workflow GitHub Actions**
  (`.github/workflows/cleanup-cron.yml`, una volta al giorno, anche
  avviabile a mano) — gratuito, nessuna dipendenza da eleggibilità
  Vercel (v0.11.0).
- 2026-09-09: Il deploy falliva ancora (nuovo errore, non più su
  `crons`): `vercel.json` aveva sia `"api/*.js"` che `"api/cleanup.js"`
  come pattern separati in `functions`. Vercel assegna ogni file al
  primo pattern che lo matcha, quindi il wildcard consumava già
  `cleanup.js`, lasciando la regola specifica successiva "senza nulla
  da matchare" → build fallita. Unificato in un solo pattern
  (`api/*.js`, `maxDuration: 30` per tutte le funzioni) (v0.12.0).
- 2026-09-09: Deploy confermato funzionante (`/api/cleanup` risponde
  401 come atteso). **Backend MVP completo e verificato end-to-end**
  (v0.13.0).
- 2026-09-09: Workflow GitHub Actions testato con avvio manuale:
  `HTTP 200`, `{"ok":true,"locationsDeleted":0,"quotaDeleted":0}`.
  Pulizia programmata confermata funzionante end-to-end. **Backend
  MVP chiuso**, si passa a watch-app/phone-app (v0.14.0).
- 2026-09-09: Scritto lo scaffolding completo di watch-app (Wear OS,
  Kotlin): foreground service con sampling adattivo, buffer locale +
  upload a batch, sync/gestione geofence, SOS come lavoro espedito,
  richiesta permessi (inclusa background location come step separato),
  riavvio dopo boot. Gradle wrapper generato a parte (la rete
  dell'ambiente non arriva ai repository Google Maven, necessari per
  risolvere il plugin Android) e poi copiato nel progetto. **Non
  compilato/testato qui** — nessun SDK Android disponibile; test reale
  rimandato a stasera su Android Studio + Watch4 (v0.15.0).
- 2026-09-09: Scritto lo scaffolding completo di phone-app (Android,
  Kotlin/Compose): login Google (Firebase Auth), mappa in tempo reale
  con Google Maps Compose (marker, storico 48h come polyline, cerchi
  geofence), gestione zone con scrittura diretta su Firestore, servizio
  FCM per le push SOS/geofence, registrazione token su `parents/{uid}`.
  Riusato il Gradle wrapper già generato per watch-app (indipendente
  dal progetto). **Scoperto un limite pratico**: la sessione precedente
  aveva accesso a una service account Firebase (per creare utenti,
  deployare regole, ecc.), ma questo ambiente è un container effimero —
  le credenziali caricate in sessione non sopravvivono a un nuovo
  container, quindi non posso più registrare l'app Android su Firebase
  Console (serve per `google-services.json`) né creare la chiave Maps
  al posto dell'utente. Documentati in `phone-app/README.md` i due
  passaggi manuali richiesti (registrazione app + SHA-1 debug per il
  login Google, chiave Maps SDK). **Non compilato/testato** — stesso
  limite di watch-app (v0.16.0).
- 2026-09-09: L'utente ha fornito una nuova chiave service account.
  Usata per registrare via API l'app Android
  (`com.gwatch.childtracker.phone`) sul progetto Firebase reale,
  generare un keystore di debug fisso nel progetto e registrarne il
  SHA-1 per il login Google, scaricare `google-services.json` — tutto
  senza bisogno di alcun passaggio manuale da browser. **Scoperto un
  conflitto con la priorità "mai una carta"**: Google Maps Platform
  richiede una fatturazione attiva ad *ogni chiamata* dell'API, non
  solo alla creazione della chiave (scollegare la carta dopo rompe la
  mappa) — a differenza di Firestore/Auth (piano Spark) e di tutto il
  resto dello stack, qui non c'è modo di restare a "zero carta" restando
  su Google Maps. Chiesto esplicitamente all'utente, che ha scelto di
  **sostituire Google Maps con OpenStreetMap (libreria osmdroid)**:
  nessuna chiave API, nessuna fatturazione, mai. Riscritte
  `ui/MapScreen.kt` e `ui/GeofenceScreen.kt` con `MapView` di osmdroid
  incorporato in Compose via `AndroidView` (marker, polyline storico,
  poligoni-cerchio per le geofence, tocco su mappa per sceglierne il
  centro). Rimossa ogni dipendenza da Google Maps SDK/Play Services Maps
  e la chiave `local.properties` (non più necessaria). Credenziali
  service account cancellate dallo scratchpad subito dopo l'uso, come
  da prassi. **Non ancora compilato/testato** — stesso limite di sempre
  (v0.17.0).
- 2026-09-10: Richiesta chat testuale genitore↔watch. Chiesto
  esplicitamente se conveniva riusare il polling già esistente
  (BackendClient/WorkManager) o passare a push FCM: differenza reale
  sul consumo batteria (radio a riposo con FCM, sveglia a intervalli
  fissi col polling), l'utente ha scelto **FCM**. Registrata via API
  (stessa service account, poi ricancellata) una nuova app Android
  Firebase per il watch (`com.gwatch.childtracker`, pacchetto separato
  da `phone-app`) — nessuna SHA-1 richiesta, solo `firebase-messaging`
  (niente Firestore/Auth completi sul watch, coerente con la scelta
  "meno pezzi in movimento" già fatta per OkHttp). Aggiunti 4 endpoint
  backend (`send-message`, `send-message-to-child`,
  `register-watch-token`, `messages`) e `devices/{id}/messages` alle
  regole Firestore (lettura solo genitore, scrittura sempre negata al
  client — ogni messaggio passa dal backend perché deve anche
  innescare la push, stesso motivo di `trigger-event.js`). Aggiunta
  `checkParentAuth` (verifica ID token Firebase via Admin SDK) per
  l'endpoint chiamato dalla phone-app, diverso dal token statico del
  watch. UI: `ChatScreen` su entrambe le app — sul watch solo risposte
  rapide preimpostate + dettatura vocale (niente tastiera, impraticabile
  su un display così piccolo per un bambino), sul telefono un campo di
  testo libero. **Non ancora compilato/testato/deployato** — stesso
  limite di sempre, backend da pushare su Vercel (v0.18.0).
- 2026-09-10: Prima sessione di build/test reale su Android Studio.
  Backend deployato e verificato su Vercel (branch tracking, env vars,
  indice `messages/expiresAt` tutti confermati attivi). Phone-app
  installata su device reale, login funzionante. Trovati e corretti in
  diretta diversi bug emersi solo a build reale (`Modifier.weight` non
  risolveva ne' nel watch-app ne' nella phone-app, `Divider` non
  esisteva nella versione di Wear Compose Material del progetto,
  SOS/"Invia posizione" non aggiornavano il pin sulla mappa, il
  pulsante "Invia" della chat sul telefono ignorava silenziosamente i
  fallimenti). Aggiunte funzioni oltre lo scope MVP originale su
  richiesta esplicita: "Invia posizione"/"Aggiorna posizione" on-demand,
  switch percorso 24h sulla mappa, scadenza automatica storico chat
  (24h), e un ridisegno dell'SOS (conferma prima dell'attivazione +
  tracking continuo ogni 30s finche' non disattivato dal genitore,
  invece del precedente invio one-shot). Ripetuto il confronto con
  servizi di mercato equivalenti (Family Link su Wear OS, Life360,
  watch "chiavi in mano" tipo GizmoWatch/TickTalk/Xplora, Jiobit):
  nessuno copre il caso "Watch4 gia' posseduto, zero spesa ricorrente,
  nessuna carta collegata in modo permanente" — confermata la scelta
  della soluzione custom. Test su hardware reale del watch-app ancora
  da fare/confermare (v0.19.0).
- 2026-09-10: Feedback utente dopo test reale sulla schermata Zone
  della phone-app: mappa a meta' schermo (altezza fissa hardcoded),
  nessuna barra strumenti per disegnare/cancellare zone. Riscritta
  `GeofenceScreen.kt` sul pattern Box+align gia' validato in
  `MapScreen.kt` (mappa fillMaxSize, pannello strumenti flottante in
  alto con pulsante "Annulla" nuovo, lista zone flottante in basso con
  sfondo opaco). Aggiunte anche zone esistenti visibili come cerchi
  sulla mappa di questa schermata e anteprima live del raggio scelto
  con lo slider (miglioramenti collaterali, stessa causa del bug
  originale: mappa poco utilizzabile). Confermato via revisione del
  codice (non ancora richiesto ma verificato in previsione): nessun
  limite al numero di zone nell'app (solo il tetto di sistema Android,
  100 geofence/app), notifiche ingresso/uscita gia' complete
  end-to-end, un solo switch attiva/disattiva per zona che copre
  entrambe le direzioni insieme (v0.20.0).
- 2026-09-10: Richiesti toggle separati notifica ingresso/notifica
  uscita per zona, e un allarme sonoro/vibrazione ripetuto sul telefono
  per l'uscita. Nel leggere la config della zona lato backend per questi
  toggle, scoperto un bug preesistente mai notato: il campo "zoneName"
  mandato dal watch era sempre stato l'id Firestore della zona, non il
  nome (le notifiche di ingresso/uscita mostravano l'id). Corretto nello
  stesso passaggio (rinominato "zoneId", nome vero risolto lato
  backend). Allarme uscita implementato come foreground Service sul
  telefono (suono in loop + vibrazione + schermata a tutto schermo sopra
  il lockscreen), avviato da un messaggio FCM data-only dedicato (serve
  data-only per partire anche ad app in background/uccisa). Aggiunta
  anche la modifica di una zona esistente in GeofenceScreen.kt, prima
  impossibile (v0.21.0).
- 2026-09-10: Secondo giro di test reale sull'SOS ridisegnato e sulla
  chat. Trovati e corretti: nessun riscontro visivo sul watch dello
  stato SOS attivo/disattivo (aggiunto SosState.kt + banner + notifica
  di disattivazione); la notifica "Aggiorna posizione" dal genitore
  diceva sempre "il bambino ha inviato la posizione" (aggiunto un campo
  "source" end-to-end per distinguere richiesta locale/remota); i
  messaggi chat non erano visibili senza scroll manuale sul watch
  (aggiunto scroll automatico) e comparivano con ritardo sulla
  phone-app (aggiunto invio ottimistico, stesso pattern del watch)
  (v0.22.0).
- 2026-09-10: Terzo giro di feedback su GeofenceScreen.kt: aggiunta
  ricerca indirizzo (Nominatim/OpenStreetMap, GeocodingClient.kt —
  prima la mappa partiva sempre da Roma), spostato il pannello di
  creazione zona dall'alto al basso (prima copriva il punto appena
  toccato quando vicino alla cima dello schermo), allargato il raggio
  minimo dello slider da 50m a 20m (non era un limite tecnico, solo il
  range scelto nel codice). Confermato che le notifiche sono gia'
  abilitate sul watch — il dubbio sulla mancata notifica di un
  messaggio in arrivo resta aperto, non e' un problema di permesso
  (v0.23.0).
- 2026-09-10: Chiesto se le geofence potessero avere forme diverse dal
  cerchio (rettangolo/forma libera). Spiegato il vincolo: le zone
  circolari usano la Geofencing API nativa di Android
  (`setCircularRegion`), che supporta solo cerchi ed e' il motivo del
  risparmio batteria (valutazione dei confini a livello OS, GPS non
  tenuto acceso a controllare di continuo). Poligoni/rettangoli
  richiederebbero un controllo "punto nel poligono" calcolato dall'app
  sul sampling GPS gia' attivo, meno efficiente del check nativo.
  Opzioni proposte: solo cerchio (invariato), poligono solo per le
  nuove zone che lo richiedono (cerchi restano nativi), o tutto a
  poligono (sconsigliata, perde il risparmio batteria ovunque). Scelto
  restare **solo cerchio** — nessuna modifica al codice, decisione
  registrata qui per non doverla rivalutare da capo in futuro (v0.23.1).
- 2026-09-10: Richieste due modifiche alle notifiche del watch (restare
  visibili finche' non rimosse esplicitamente; la card a comparsa piu'
  grande/centrata invece che un peek in basso) e una nuova funzione
  (notifica al watch quando il genitore ha visto la posizione inviata
  dal bambino). Implementate: setAutoCancel(false) + PRIORITY_MAX +
  categoria su tutte le notifiche watch (fattorizzate in un helper
  condiviso in FcmService.kt), nuovo endpoint ack-event.js (idempotente)
  chiamato da MapScreen.kt quando mostra un evento sos/location_request
  (source "child") non ancora marcato. Chiarito che la resa esatta a
  schermo della card a comparsa resta decisa da Wear OS, non e'
  impostabile pixel per pixel dall'app (v0.24.0).
- 2026-09-10: Segnalato che la phone-app doveva restare aperta in
  primo piano per ricevere le notifiche dal watch. Con FCM (push
  "data") non dovrebbe servire: il processo viene svegliato anche in
  background. Causa piu' probabile individuata per esclusione: Android
  (specie Samsung, coerente con l'uso di un Galaxy Watch4) sospende il
  processo per risparmio batteria e nega la sveglia FCM a un'app
  "ottimizzata" — nessuna richiesta di esenzione era mai stata fatta.
  Aggiunta `requestIgnoreBatteryOptimizations()` in MainActivity.kt,
  eseguita una tantum all'avvio (Toast esplicativo + intent di sistema
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, richiede conferma
  esplicita dell'utente, non e' auto-concessa). Segnalato all'utente
  che sui Samsung esiste anche una seconda lista distinta e non
  gestibile da codice ("Metti in sospensione le app inutilizzate", Cura
  del dispositivo > Batteria) da disattivare a mano se il problema
  persiste dopo l'esenzione (v0.25.0).
- 2026-09-10: Due bug distinti sulla chat, segnalati insieme. (1) Sulla
  phone-app i messaggi ricevuti dal watch comparivano solo come
  notifica di sistema, mai nella schermata Chat, mentre quelli inviati
  dal telefono si vedevano regolarmente (grazie all'invio ottimistico
  gia' presente). Causa trovata: `send-message.js` mandava una push
  "mista" (notification + data) — ad app in background Android
  consegna la parte "notification" al tray di sistema senza invocare
  `onMessageReceived()`, quindi la chat (che dipende solo dal listener
  Firestore, mai toccato in quel percorso) non veniva mai aggiornata
  finche' non arrivava un aggiornamento del listener per altra via.
  Sistemato allineando `send-message.js` allo stesso pattern
  "solo data" gia' usato verso il watch, e aggiungendo un
  `IncomingMessageStore` sulla phone-app (stesso ruolo dell'invio
  ottimistico ma per i messaggi in entrata), popolato da
  `FcmService.kt` alla ricezione. (2) Sul watch i messaggi arrivavano
  (visibili aprendo il pannello notifiche) ma senza vibrazione, senza
  svegliare lo schermo, senza alcuna card se il watch era sulla home.
  Causa: il canale "messages" non aveva la vibrazione abilitata
  esplicitamente (il default alla creazione e' `enableVibration(false)`
  anche per canali `IMPORTANCE_HIGH`), e Wear OS non considera
  abbastanza interruttiva una notifica cosi' configurata per riattivare
  lo schermo da sola. Aggiunto `enableVibration(true)` + pattern
  esplicito sul canale in `TrackerApplication.kt` (v0.26.0).
- 2026-09-10: L'utente ha condiviso lo screenshot del pannello Vercel:
  "Build Failed — No more than 12 Serverless Functions can be added to
  a Deployment on the Hobby plan". `backend/api/` era a 13 file
  (superato quando ack-event.js si e' aggiunto agli altri 12) — il
  deploy falliva da 3 commit (5af2c9a, f7da87a, 7a1a84a) senza che
  nessuno se ne accorgesse: NESSUNA delle modifiche backend di quei
  commit era mai realmente andata online, incluso il fix "chat
  data-only" appena fatto per il bug precedente. Causa root della
  segnalazione "il messaggio ricevuto dal watch non appare in chat" —
  quella era solo un sintomo, la vera causa era che il backend live
  era ancora la versione vecchia. Risolto accorpando
  send-message-to-child.js, request-location.js, cancel-sos.js e
  ack-event.js in un unico backend/api/parent-command.js (dispatch su
  "action" nel body) — tornati a 10 file. **Lezione per il futuro:
  ogni nuovo file in backend/api/ e' una Serverless Function separata
  sul piano Hobby (limite 12) — prima di aggiungerne uno nuovo,
  valutare se accorparlo a un endpoint esistente invece di crearne
  uno nuovo.**
  Insieme, altri due bug segnalati: (1) toccare la notifica di un
  messaggio apriva la Home invece della Chat (FcmService.kt non
  impostava contentIntent) — aggiunto PendingIntent + gestione
  dell'extra in MainActivity.kt (a freddo e via onNewIntent). (2) La
  Chat sulla phone-app parte sempre vuota, a differenza del watch che
  mostra lo storico completo. Ipotesi forte non verificabile da questa
  sessione: le regole Firestore (backend/firestore.rules) si
  deployano SOLO manualmente (`firebase deploy --only firestore:rules`
  da backend/, vedi backend/firebase.json), mai automaticamente sui
  push — se la regola per devices/{id}/messages non e' mai stata
  pubblicata sul progetto Firebase live, ogni lettura fallisce in
  silenzio (permission-denied, solo loggato) restituendo lista vuota,
  spiegando esattamente il sintomo. Segnalato all'utente di
  ripubblicare le regole e verificare (v0.27.0).
- 2026-09-10: **Confermato dall'utente**: ripubblicate le regole
  Firestore dalla Console (copia-incolla manuale, senza CLI — l'utente
  non aveva Firebase CLI installato), la chat sulla phone-app ora
  mostra lo storico completo. Causa quindi definitivamente accertata:
  le regole non erano mai state pubblicate sul progetto live da quando
  la sottocollezione `messages` era stata aggiunta. **Promemoria
  strutturale**: le regole Firestore sono un deploy target separato,
  NON coperto dal deploy automatico Vercel ne' da nessuna pipeline CI
  di questo progetto — ogni modifica a `backend/firestore.rules` va
  ripubblicata a mano (Console Firebase > Firestore > Regole > incolla
  > Pubblica, oppure `firebase deploy --only firestore:rules` da
  `backend/` per chi ha la CLI) o resta silenziosamente senza effetto.
  Nessuna azione di codice richiesta per questo fix.
- 2026-09-10: Confermato che tap-notifica ora apre la Chat
  correttamente (v0.27.0 verificato su hardware reale). Ma il fix
  vibrazione watch (v0.26.0) risultava ancora senza effetto dopo
  ricompilazione: nessuna vibrazione/popup all'arrivo di un messaggio.
  Causa reale trovata: su Android le impostazioni di un
  `NotificationChannel` (vibrazione, suono, importanza) sono
  **immutabili una volta creato** — richiamare
  `createNotificationChannel()` con lo stesso ID non aggiorna nulla su
  un dispositivo dove quel canale esisteva gia' da build precedenti
  (qui "messages", creato ben prima di questo fix). L'unico modo per
  applicare le nuove impostazioni e' un ID canale nuovo, forzando
  Android a crearne uno da zero. ID cambiato a "messages_v2" in
  `TrackerApplication.kt` (watch). **Promemoria per il futuro**: ogni
  volta che si cambiano le impostazioni di un `NotificationChannel`
  gia' esistente su dispositivi reali, serve un nuovo ID canale,
  altrimenti il fix e' silenziosamente inefficace (v0.28.0).
- 2026-09-10: Confermato dall'utente (dopo domanda diretta via
  AskUserQuestion per distinguere i due scenari possibili): anche col
  nuovo ID canale la notifica compare ANCORA solo nel pannello, senza
  vibrazione/popup — il problema non era (solo) l'immutabilita' del
  canale. Aggiunta vibrazione esplicita anche sulla singola notifica
  (setVibrate/setDefaults in FcmService.kt), ridondante rispetto al
  canale su Android 8+ ma non sempre rispettata in modo affidabile da
  alcune skin OEM. **Le leve lato codice sono ormai esaurite**: canale
  IMPORTANCE_HIGH + vibrazione+pattern+luci sul canale + vibrazione
  ridondante sulla notifica + PRIORITY_MAX + CATEGORY_MESSAGE. Se il
  problema persiste dopo questo fix, e' quasi certamente
  un'impostazione di sistema sul watch (Non disturbare/Modalita'
  teatro/Bedtime mode, vibrazione disattivata globalmente in
  Impostazioni > Suoni e vibrazione, o il toggle "Vibra" del canale
  "Messaggi" disattivato a mano in Impostazioni > App > Family Tracker
  > Notifiche sul watch) — da verificare direttamente sul dispositivo,
  non piu' diagnosticabile da qui (v0.29.0).
- 2026-09-10: Chiarito dall'utente il sintomo esatto: la notifica
  arriva regolarmente (visibile nel pannello), ma Wear OS non mostra
  mai la card interruttiva a schermo intero ne' accende lo schermo se
  spento. Confermato che siamo al limite di quanto controllabile da
  codice — indicato il checklist di verifica sul dispositivo, in
  ordine di probabilita': (1) **Risparmio energetico** attivo
  (sospetto principale: su Samsung disattiva esplicitamente i
  popup/wake per risparmiare batteria), (2) Non disturbare/Modalita'
  teatro, (3) toggle popup separato da "vibra" in Impostazioni > App >
  Family Tracker > Notifiche, (4) eventuale opzione "Riattiva schermo
  per notifiche" in Display/Avanzate. Nessuna ulteriore modifica di
  codice fatta in questo giro: serve conferma dall'utente su quale di
  queste impostazioni fosse la causa, prima di considerare il problema
  chiuso.
- 2026-09-10: **Confermato dall'utente con screenshot**: popup e
  riattivazione schermo sul watch ora funzionano — chiuso il problema
  aperto dai due giri precedenti (v0.28.0/v0.29.0 hanno risolto).
  Dallo screenshot emergono pero' due cose nuove: (1) la notifica non
  e' cliccabile (nessuna azione al tocco) — stesso bug gia' sistemato
  sulla phone-app (v0.27.0), mancava il contentIntent qui sul watch.
  Aggiunto PendingIntent verso MainActivity con lo stesso pattern
  (EXTRA_OPEN_CHAT + onNewIntent + launchMode singleTop) — per i
  messaggi apre la Chat, per le altre notifiche la Home. (2) Dubbio
  sull'icona/nome app poco riconoscibile: verificato che l'icona
  piccola e' gia' corretta (maschera monocromatica, come richiesto da
  Android) e il nome app dovrebbe comparire di default nella card Wear
  OS — nessuna modifica fatta, probabile che fosse solo tagliato fuori
  dalla foto (cinturino sopra al bordo dello schermo nello
  screenshot); da riverificare (v0.30.0).
- 2026-09-10: **Confermato dall'utente con secondo screenshot** (foto
  meno raccorciata): tap ok, "Apri app" funziona. Ma confermato anche
  che il nome app NON e' un artefatto della foto precedente — la card
  e' genuinamente senza alcun testo identificativo, solo icona
  generica (fumetto) + titolo + orario + corpo + pulsante. Aggiunto
  `setLargeIcon()` con l'icona reale dell'app (mipmap/ic_launcher) per
  renderla piu' riconoscibile visivamente. **Limite architetturale
  documentato**: non esiste nessuna API Android/Wear OS per forzare un
  testo "nome app" esplicito nella card — cio' che il sistema mostra
  (se lo mostra) e' sempre solo l'etichetta del manifest, con resa
  interamente decisa dalla skin del dispositivo; se anche con l'icona
  reale non compare alcun nome, e' un limite della skin Wear OS di
  questo Galaxy Watch4, non ulteriormente risolvibile lato app —
  chiudere il punto dopo la prossima verifica, qualunque sia l'esito
  (v0.31.0).
- 2026-09-10: **Confermato dall'utente**: icona reale + tap ok, notifica
  watch chiusa come capitolo. Nuova richiesta: migliorare la
  visualizzazione della chat su entrambe le app replicando lo stile
  WhatsApp (mittente/destinatario), documentandosi prima sul pattern
  reale di WhatsApp. Implementato su entrambe: bolle colorate
  allineate a destra/sinistra (verde per i propri messaggi, bianco o
  grigio scuro per i ricevuti secondo il tema — sul watch sempre
  variante scura, essendo Wear OS sempre a sfondo nero), angolo
  "a fumetto" meno arrotondato dal lato del mittente, nome del
  mittente in grassetto colorato sopra il testo solo per i messaggi
  ricevuti. Nome hardcoded ("Genitore" sul watch, "Bambino" sul
  telefono) perche' il modello dati attuale non distingue quale
  genitore specifico abbia scritto un messaggio nel caso multi-genitore
  — se in futuro si aggiunge quel tracciamento, la stessa etichetta
  passerebbe a mostrare il nome vero (v0.32.0).
- 2026-09-10: **Confermato dall'utente**: "tutto perfetto" sulla chat
  WhatsApp-style. Nuova richiesta: nelle Zone, cliccando "Modifica" su
  una zona la mappa deve centrarsi automaticamente sulla sua posizione
  (`onEdit` aggiornava solo lo stato Compose del form, non la camera
  della mappa — bug minore ma reale, gia' emerso in passato come
  pattern per i risultati della ricerca indirizzo, `pickSearchResult()`,
  mai applicato pero' al percorso "Modifica zona esistente"). Corretto
  riusando la stessa chiamata (`animateTo` + zoom 17) dentro `onEdit`
  (v0.33.0).
- 2026-09-10: Richiesta utente: pubblicare le app su **Play Console →
  Internal Testing** per far provare l'app anche alla mamma (secondo
  genitore, gia' pre-autorizzato in `parents/{uid}`), e chiesto se i
  messaggi dal watch notificano entrambi i genitori. **Verificato nel
  codice** (`backend/api/send-message.js`): si', la push FCM alla
  ricezione di un messaggio dal watch va a *tutti* i documenti in
  `parents/` (multicast su tutti gli `fcmTokens` registrati), non solo
  a chi ha inviato l'ultimo comando — quindi funziona gia' oggi per
  entrambi, a condizione che anche la mamma abbia fatto login sulla
  phone-app col proprio account pre-autorizzato (cosi' il suo token
  FCM viene registrato). **Predisposizione pubblicazione** (v0.34.0):
  aggiunta `signingConfig` "release" opzionale (letta da
  `local.properties`, mai un keystore/password nel repo) a entrambe le
  app, e una pagina statica `backend/privacy.html` (richiesta da
  Google Play anche per test privati quando l'app usa la posizione).
  **Limite esplicito**: l'attivazione vera e propria (generare il
  keystore, compilare l'`.aab` firmato in Android Studio, creare le
  app su Play Console, compilare il Data Safety form, aggiungere
  l'email della mamma come tester) resta fuori dalla portata di questo
  ambiente — nessun accesso a Play Console/Android SDK qui, sempre
  fatto lato utente come per ogni build finora in questo progetto.
- 2026-09-11: Segnalato un logout inatteso sul telefono premendo
  "Esci". **Causa verificata nel codice** (`MapScreen.kt`): era un
  `TextButton` nella barra in alto, affiancato a "Messaggi"/"Zone",
  che chiamava `onSignOut()` immediatamente al tocco — nessuna
  conferma, facile da premere per sbaglio puntando uno degli altri due
  pulsanti. Aggiunto un `AlertDialog` di conferma (v0.35.0). Stesso
  messaggio menzionava anche un prompt "accesso con account genitore"
  visto a volte all'avvio **sul watch**: verificato che la watch-app
  non ha alcuna UI di login Google nel codice (solo device token via
  FCM, decisione gia' presa e documentata sopra) — quasi certamente un
  prompt di sistema Wear OS/Google Play Services legato all'account
  Google "adulto" del device, non risolvibile lato app; da confermare
  con uno screenshot se ricapita.
- 2026-09-11: Richiesta utente, quattro parti. **Implementate (v0.36.0)**:
  1) MapScreen: "Logout" spostato in un menu hamburger (prima non era
     chiaro fosse un logout, nonostante il dialog di conferma v0.35.0);
     switch "Percorso 24h" salito in TopAppBar accanto al menu; "Aggiorna
     posizione" spostato sulla stessa riga della card di stato; testo
     "Aggiornato X min fa" rinominato "Ultima posizione ricevuta X min fa".
  2) Watch ChatScreen: scroll automatico in fondo ora solo aprendo la
     chat dalla notifica di un nuovo messaggio, non piu' dal Chip
     "Messaggi" del menu principale.
  **Rimandata, serve una decisione di scope prima di implementare**:
  3) "campo nickname per se stessi e per i bambini, chat unica per i
     genitori e due bambini" — tocca l'architettura corrente, dove
     device/DEVICE_ID e' hardcoded a un solo figlio ("figlio",
     backend/api/*.js) e ChatMessage.sender e' solo "parent"/"child"
     (nessun ID di chi specificamente). Supportare davvero due bambini
     richiede un secondo device Wear OS con la propria identita' e uno
     schema messaggi/eventi che la porti fino in fondo (backend,
     Firestore, entrambe le app) — molto piu' di un campo impostazioni
     cosmetico. Chiesto all'utente se per ora basta un nickname
     "cosmetico" (etichette configurabili al posto di "Genitore"/
     "Bambino" hardcoded, ancora un solo watch) o se serve gia' da
     subito il supporto reale a due dispositivi/bambini distinti.
- 2026-09-11: **Richiesta grande, in corso a fasi** (piano approvato in
  plan mode, salvato — vedi sessione — dopo tre giri di precisazioni
  dell'utente): supporto a N bambini/watch reali (non 2 fissi), nickname
  per genitori e bambini, chat dove il genitore sceglie il destinatario,
  zone su una sola mappa con toggle di assegnazione per bambino.
  **Fase 1/4 completata (v0.37.0), solo backend**: registro bambini
  dinamico (`devices/{childId}`, id auto-generato tranne il primo
  `"figlio"`); auth watch passata da un token statico globale
  (`checkDeviceToken`) a un hash-lookup per-device
  (`resolveDeviceId`, `_lib/auth.js`) con **migrazione automatica** del
  device esistente (se l'hash non combacia ma il vecchio
  `DEVICE_TOKEN` sì, il device "figlio" viene aggiornato al volo —
  nessun passaggio manuale per non rompere il watch già installato);
  `parent-command.js` guadagna `create_child` (genera id+token,
  restituisce il token in chiaro una volta sola, salva solo l'hash) e
  `set_nickname`; le azioni esistenti richiedono `childId` esplicito;
  messaggi chat portano `senderId`/`senderName`; notifiche push
  includono il nome del bambino. **Nessuna modifica a
  firestore.rules**: i nuovi campi sono già coperti dalle regole
  esistenti (wildcard su `devices/{deviceId}`). Retrocompatibile: con
  un solo bambino il comportamento visibile non cambia.
  **Prossime fasi** (non ancora iniziate): geofence come collezione
  radice con `childIds[]` + toggle UI; phone SettingsScreen (nickname +
  "Aggiungi bambino") + mappa singola multi-bambino; chat con selettore
  destinatario su entrambe le app + `CHILD_ID` sul watch.
- 2026-09-11: **Fase 2/4 completata (v0.38.0)**: geofence trasformate
  da subcollection per-device (`devices/{childId}/geofences`) a
  collezione radice condivisa `geofences/{zoneId}` con campo
  `childIds: string[]`, per permettere a una zona di essere assegnata
  a uno o più bambini (richiesta esplicita dell'utente nel giro di
  correzione del piano: "sulla mappa zone lascia una sola mappa e
  aggiungi a ogni zona un toggle per ogni bambino a cui assegnare
  quella zona"). `device-config.js` ora interroga con
  `array-contains` (query a singolo campo, nessun indice composito) e
  filtra `active` in memoria. **Migrazione dati**: le zone create
  prima di questa versione restavano nella vecchia posizione — gestita
  con lo stesso pattern di auto-migrazione già in uso per il token
  legacy (`_lib/auth.js`): al primo poll di `device-config.js` per
  ciascun bambino, se `devices/{childId}.geofencesMigrated` non è
  ancora true, le zone vengono copiate nella nuova collezione radice
  e il flag impostato, per non ripetere la copia ai poll successivi.
  Nessun passaggio manuale richiesto, nessuna interruzione per il
  watch già installato. Lato phone-app, `GeofenceScreen.kt` ha ora un
  selettore "Assegna a: [bambino]" (toggle per bambino, riusa lo
  stesso composable sia nel pannello di creazione/modifica sia nella
  lista zone) alimentato da un nuovo `DeviceRepository.observeChildren()`
  (query live su `devices`, già leggibile da qualunque genitore con le
  regole attuali). Per continuità con l'unico bambino di oggi, una
  zona nuova parte con tutti i bambini conosciuti già selezionati.
  **Prossime fasi**: fase 3 (SettingsScreen con nickname/"Aggiungi
  bambino", mappa singola multi-bambino con marker/status-card per
  ciascuno) e fase 4 (chat con selettore destinatario, bolle per
  identità invece che per ruolo, `CHILD_ID` sul watch) — vedi
  `/root/.claude/plans/clever-roaming-moth.md` per il piano completo
  approvato.
- 2026-09-11: **Fase 3/4 completata (v0.39.0)**: nuova `SettingsScreen.kt`
  (nickname proprio, nickname per bambino, "Aggiungi bambino" con token
  mostrato una volta sola) e `MapScreen.kt` riscritta per mostrare TUTTI
  i bambini su una sola mappa (marker multipli, riga scorrevole di
  status-card, lista banner SOS) invece di un singolo bambino fisso —
  come richiesto esplicitamente dall'utente ("una sola mappa", non uno
  switcher. `AppViewModel` passa da stato singolare
  (deviceState/history/events) a mappe per-bambino
  (deviceStates/historyByChild/eventsByChild) via flatMapLatest+combine
  sulla lista bambini. **Bug corretto**: `BackendClient.kt` non mandava
  ancora `childId` nel body delle quattro azioni che `parent-command.js`
  lo richiedono esplicitamente dalla fase 1 (message/request_location/
  cancel_sos/ack_event) — erano quindi già rotte in produzione dal
  deploy della fase 1 (rispondevano 400), solo non ancora notato perché
  nessuno aveva ancora ricompilato/testato la phone-app nel frattempo.
  Corretto aggiungendo il parametro mancante a tutte e quattro.
  **Prossima fase (4/4, ultima)**: chat con selettore destinatario sul
  telefono ("Scrivi a: ..."), bolle allineate per identità
  (`senderId`) invece che per ruolo su entrambe le app, `CHILD_ID` sul
  watch (nuova chiave `local.properties`/`BuildConfig`, parallela a
  `device.token`) per sapere quale sia il proprio thread. A quel punto
  il piano approvato in plan mode
  (`/root/.claude/plans/clever-roaming-moth.md`) sarà completo.
- 2026-09-11: **Fase 4/4 completata (v0.40.0) — piano N bambini chiuso**:
  chat con selettore destinatario. Phone-app: `ChatScreen.kt` mostra
  "Scrivi a: [bambino] ▾" (solo con >1 bambino registrato), bolle
  allineate per `senderId == proprio uid` (non più sul solo ruolo
  "parent"/"child", necessario perché due genitori condividono lo
  stesso thread `devices/{childId}/messages`). Watch-app:
  `ChatMessage.kt`/`FcmService.kt`/`ChatScreen.kt` mostrano ora
  `senderName` (nickname del genitore) sopra i messaggi ricevuti,
  invece dell'etichetta fissa "Genitore" (mantenuta come fallback).
  **Deviazione dal piano approvato**: niente `BuildConfig.CHILD_ID` sul
  watch — ogni watch ha già un thread dedicato a un solo bambino
  (`devices/{childId}/messages`), quindi `sender=="child"` basta da
  solo per sapere "chi sono io" senza ambiguità; aggiungere un
  `CHILD_ID` esplicito sarebbe stata un'astrazione in più non
  giustificata dal problema reale. Nessun'altra modifica strutturale al
  watch (geofence/location/eventi, già parametrizzati per childId dalle
  fasi precedenti). Il piano
  (`/root/.claude/plans/clever-roaming-moth.md`) è ora interamente
  implementato lato codice; resta da fare solo la parte manuale
  dell'utente (pubblicare `firestore.rules` aggiornate se non già
  fatto per la fase 2, poi build/test in Android Studio).
- 2026-09-18: **Primo test reale (v0.41.0)** — l'utente ha attivato la
  eSIM sul Watch4 e dato il device al figlio, primo invio chat da
  telefono con "Invio fallito" pur essendo il piano N-bambini già
  completo lato codice. Causa trovata leggendo `parent-command.js`: i 4
  handler (message/request_location/cancel_sos/ack_event) chiamavano
  `getMessaging().send()` senza try/catch — un token FCM del watch non
  più valido faceva rispondere 500 al genitore anche se la scrittura su
  Firestore (il messaggio, lo stato SOS, l'ack) era già andata a buon
  fine: bug pre-esistente al piano N-bambini, solo mai emerso prima
  perché mai testato su hardware reale con un token FCM realmente in
  gioco. Corretto con `sendPushSafe()`: la push è ora "best effort"
  (mai fatale per la richiesta), con auto-pulizia del token se
  Firebase segnala che non è più registrato. Nessuna migrazione dati
  richiesta.
- 2026-09-18: **Review di un'altra AI locale pushata su GitHub
  (v0.42.0)**. L'utente aveva fatto girare un'AI in locale che ha
  pushato direttamente su questo stesso branch, bypassando questa
  sessione.
  Richiesto di controllare cosa avesse fatto. Trovati diversi bug
  gravi, alcuni bloccanti: **tutti i 10 endpoint backend** avevano una
  parentesi di chiusura mancante attorno a un nuovo `wrapHandler(...)`
  (errore di sintassi, funzioni non caricabili — backend completamente
  giù), **la phone-app non compilava affatto**
  (`TrackerApplication.kt` aveva uno statement fuori da qualunque
  funzione), e `MapScreen.kt` (nuovo indicatore batteria colorato)
  aveva graffe sbilanciate più un `Modifier.weight()` che reintroduceva
  esattamente il vincolo Compose già documentato altrove nel progetto.
  Un commit intermedio (7232da8) aveva anche `_lib/errors.js`
  completamente vuoto — se quel commit e' stato live su Vercel prima
  del successivo, il backend è stato giù per errore di caricamento
  modulo per l'intera finestra. Tutto corretto e verificato
  (`node -c` su tutti i file backend, `npm test` con tutti i 10 test
  verdi dopo `npm ci`; il Kotlin non è compilabile in questa sessione,
  nessun SDK Android disponibile, verificato a mano riga per riga).
  **Non fatto un revert totale**: mescolate ai bug c'erano idee valide
  già ben eseguite altrove (confronto costant-time sui token statici
  con test dedicati, push ai genitori via topic FCM invece di
  leggere/iterare l'intera collezione parents ad ogni evento, pulizia
  mancante del gruppo "events" in cleanup.js, bump compileSdk/targetSdk
  a 35 — requisito Play Store ormai imminente) — tenute, corrette dove
  necessario, il resto (bug bloccanti + un mix-up di retention in
  `_lib/quota.js`, 7→365 giorni per un riuso sbagliato di una costante)
  riparato. Lezione per il futuro: se un'altra AI continua a pushare
  direttamente su questo branch senza passare da qui, ogni ripresa di
  lavoro deve iniziare con un `git fetch`/diff contro l'ultimo commit
  noto — non si può più assumere che lo stato remoto rifletta solo le
  modifiche fatte in questa sessione.
- 2026-09-18: **Primo build reale phone-app in Android Studio, fallito
  (v0.43.0)**: `AppViewModel.kt:289` "Missing '}'" + "Unclosed comment".
  Causa un bug mio, non dell'AI locale — un KDoc a riga singola con la
  prosa `"devices/* e' scrivibile..."`: quel `/*` letterale nel testo
  apre un commento annidato (Kotlin annida i block comment), il `*/`
  di fine riga chiude quello annidato invece di quello esterno, e il
  KDoc resta aperto fino a EOF, inghiottendo tutto il codice dopo.
  Bug della fase 3/4, mai emerso prima perche' questa sessione non ha
  mai avuto un SDK Android per compilare davvero — solo lettura
  manuale del codice. Corretto, e controllato l'intero codebase Kotlin
  (script Python che tokenizza // e /* */ correttamente, gestendo
  l'annidamento) per lo stesso pattern altrove: nessun altro caso.
  Promemoria per il futuro: mai usare un asterisco letterale dopo uno
  slash in un commento Kotlin (es. path Firestore con wildcard tipo
  "devices/*") — scrivere "devices/{childId}" o descriverlo a parole.
- 2026-09-18: **Watch non raggiungibile / invio posizione senza
  conferma (v0.44.0)**. Segnalati insieme: "Aggiorna posizione" dalla
  phone-app risponde "watch non raggiungibile" (backend: 404, nessun
  fcmToken salvato su devices/{childId}); il pulsante "Invia posizione"
  sul watch non mostra mai né successo né fallimento. Analisi del
  codice: nessun bug trovato in `LocationRequestWorker`/`SosWorker` —
  `Result.retry()` su un fallimento di rete o di fix GPS e' voluto
  (mai perdere un tentativo, ritenta in background, per questo il
  Toast "fallito" non compare mai, solo "successo" o un fallimento
  permanente come permesso mancante). Trovato pero' un buco reale:
  `watch-app/.../network/BackendClient.kt` non loggava MAI un esito
  negativo, a differenza del client identico lato phone-app — reso
  quindi impossibile diagnosticare da Logcat se il problema fosse rete
  assente, un 401 (token sbagliato), o altro. Aggiunto logging
  (`Log.w`, tag "BackendClient") coerente con la phone-app.
  **Diagnosi non chiusa** (serve accesso al device reale, non
  disponibile in questa sessione): le due ipotesi piu' probabili sono
  (a) il token del device compilato in `local.properties` non
  corrisponde a quello salvato su Firestore per quel bambino (401 su
  ogni chiamata — coerente col fatto che ANCHE la registrazione del
  token FCM, che passa dallo stesso header X-Device-Token, non risulta
  mai andata a buon fine), oppure (b) il piano dati della eSIM del
  watch non include traffico dati generico verso host arbitrari
  (comune sui piani eSIM per smartwatch bambini, spesso limitati a
  voce/SMS/localizzazione via backend del produttore) — il watch
  potrebbe risultare "in rete" (chiamate/SMS) senza avere accesso
  HTTPS libero verso Vercel. Prossimo passo per l'utente: Logcat via
  ADB WiFi (vedi watch-app/README.md) filtrato su tag "BackendClient"
  dopo aver aperto l'app watch e premuto "Invia posizione" — il codice
  HTTP (o l'assenza totale di risposta, sintomo di (b)) restringe
  immediatamente il campo.
- 2026-09-18: **Diagnosi "watch non invia posizione/SOS" ristretta
  (v0.45.0)**, grazie ai log runtime di Vercel controllati direttamente
  dall'utente (dashboard → tab Logs): `register-watch-token`,
  `device-config`, `send-message` tutti 200 — backend/auth/token del
  device confermati sani, deploy confermati "Ready" su tutte le
  release. **Zero chiamate a `/api/trigger-event`** nonostante due
  test espliciti (Invia posizione + SOS): il blocco è quindi PRIMA
  della chiamata di rete, dentro `LocationRequestWorker`/`SosWorker`
  — o il permesso `ACCESS_FINE_LOCATION` non è concesso, o il fix GPS
  non arriva mai (indoor, GPS a freddo, location di sistema disattivata
  sul watch). Nessuno dei due casi lasciava traccia in Logcat prima
  d'ora (`Result.failure()`/`Result.retry()` silenziosi, stesso
  pattern del buco già trovato in BackendClient.kt). Aggiunto `Log.w`
  su entrambi i casi in entrambi i worker. **Prossimo passo
  dell'utente**: Logcat (tag `LocationRequestWorker` o `SosWorker`)
  durante un nuovo test, idealmente all'aperto con cielo libero, dopo
  aver verificato Impostazioni watch → Posizione (ON) e i permessi
  della app (posizione concessa). Il log dirà esattamente quale dei
  due casi si sta verificando.
- 2026-09-18: **`versionCode`/`versionName` fermi a `2`/"0.2.0" su
  entrambe le app (v0.46.0)** — segnalato dall'utente durante la
  diagnosi in corso ("la versione della app è sempre ferma a 0.2").
  Verificato via `git log -p` sui due `build.gradle.kts`: un solo bump
  storico (0.1.0→0.2.0), mai più aggiornato nonostante decine di
  commit successivi (multi-bambino, chat, geofence, SDK 35...).
  Verificato anche che il codice del percorso SOS/Invia-posizione
  (`LocationRequestWorker.kt`, `SosWorker.kt`, `MainActivity.kt`,
  `AndroidManifest.xml`) non è stato toccato da nessun commit del
  lavoro multi-bambino — la regressione riportata dall'utente ("prima
  del multiwatch l'orologio inviava tutto") va quindi cercata altrove:
  candidati principali il bump compileSdk/targetSdk 35 (commit
  dd57b35, comportamento permessi/foreground service cambiato da
  Android) o, meno probabile, le modifiche di auth backend (gli altri
  endpoint che usano la stessa autenticazione rispondono 200 nei log
  Vercel). Portati entrambi i versionCode/versionName a `3`/"0.3.0" —
  utile di per sé anche a prescindere dalla diagnosi in corso, per
  poter sempre verificare da Impostazioni app quale build è davvero
  installata. Convenzione stabilita: bump ad ogni release d'ora in
  poi. Utente ha confermato separatamente che il permesso
  ACCESS_FINE_LOCATION risulta concesso sul watch (controllato a
  mano nelle impostazioni) — non ancora confermato via Logcat, cui
  l'utente non sapeva accedere; fornite istruzioni per farlo dal tab
  "Logcat" di Android Studio invece che da riga di comando adb.
- 2026-09-18: **Numero di versione in app + conferma tracking
  automatico (v0.47.0)**. Richiesta utente: mostrare la versione
  accanto al nome app (telefono e watch) — aggiunto
  `BuildConfig.VERSION_NAME` sotto/accanto al titolo in
  `phone-app/.../ui/MapScreen.kt` (TopAppBar) e
  `watch-app/.../ui/MainActivity.kt` (schermata principale). Verificato
  con lo stesso tokenizer Python usato in precedenza (nessun compilatore
  disponibile in sessione): bilanciamento graffe/parentesi/commenti OK
  su entrambi i file.
  Richiesta utente: "l'orologio deve comunicare la posizione in
  automatica ogni 30 minuti" — verificato che esiste già ed è più
  frequente del richiesto: `LocationTrackingService.kt` chiede un fix
  ogni 10' da fermo / 1' in movimento (sampling adattivo via
  ActivityRecognition), bufferizza in `PendingLocationStore` e carica
  verso `/api/ingest-location` ogni 15' (`LocationUploadWorker`,
  periodico schedulato in `MainActivity.kt`) o subito se il buffer
  supera 15 punti. Nessuna modifica al codice necessaria — ma dipende
  dallo stesso `FusedLocationProviderClient` del bug "fix GPS non
  disponibile" in diagnosi (v0.45.0/0.46.0): se il GPS non si aggancia,
  anche questo tracking periodico resta silenzioso, non solo
  l'invio manuale/SOS. Utente sta provando un riavvio del watch per
  vedere se risolve il mancato fix GPS — esito non ancora riportato.
- 2026-09-18: **GPS assente: SOS sempre attivo, "Invia posizione"
  disabilitato con messaggio esplicito, conferma più affidabile
  (v0.48.0)**. Decisione discussa con l'utente: proposto io di NON
  disabilitare mai SOS (è la funzione di sicurezza più critica, e il
  caso "GPS assente" — es. al chiuso — è spesso proprio quello in cui
  serve di più), utente d'accordo. Introdotto `GpsAvailability`
  (singleton condiviso, stesso pattern di `SosState`), aggiornato da
  `LocationTrackingService` (tracking periodico, ogni 10'/1') e dai
  worker `LocationRequestWorker`/`SosWorker` (invio manuale/SOS) —
  nessun nuovo polling GPS, riusa i fix già richiesti da queste tre
  fonti esistenti. `MainScreen` in `MainActivity.kt` disabilita il
  Chip "Invia posizione" quando `GpsAvailability.available == false`,
  mostrando "Posizione non disponibile, segnale GPS assente" al posto
  dell'etichetta normale.
  Richiesta utente aggiuntiva: "deve esserci una comunicazione quando
  il fix viene ottenuto e la posizione inviata" — la conferma
  (`Toast`) esisteva già ma era legata al `work.id` della singola
  pressione (`getWorkInfoByIdLiveData`), quindi al ciclo di vita di
  quella specifica istanza di Activity: con un GPS assente per
  minuti/ore (caso reale confermato via Logcat), chiudere/riaprire
  l'app nel frattempo poteva far perdere la conferma finale.
  Sostituita con un unico observer per nome univoco del lavoro
  (`getWorkInfosForUniqueWorkLiveData`, registrato una sola volta in
  `onCreate`, con dedup su `lastNotified*WorkId` per non ripetere lo
  stesso Toast ad ogni riapertura): riaprendo l'app si vede comunque
  l'esito reale. Limite noto (documentato in CHANGELOG, non risolto
  qui): se il processo watch viene terminato del tutto dal sistema
  mentre il GPS è ancora assente, non c'è comunque un Toast
  retroattivo alla riapertura — richiederebbe una notifica di sistema
  invece di un Toast legato all'Activity, fuori scope per questa
  iterazione.
- 2026-09-18: **Zone geofence "scomparse" + status-card grande senza
  batteria, segnalati dall'utente (v0.49.0)**. Indagine sulle zone:
  confermato via `git show`/`grep` che `device-config.js` (letto dal
  watch) marcava `devices/{childId}.geofencesMigrated=true` in modo
  permanente al primo utilizzo — se in quel momento la vecchia
  subcollection `devices/{id}/geofences` era vuota per un qualunque
  motivo transitorio, la copia verso la nuova collezione radice
  `geofences` non veniva mai più ritentata, e le zone restavano
  invisibili alla query letta da `DeviceRepository.observeGeofences()`
  sulla phone-app. Indizio a favore di questa ipotesi: gli eventi
  "Entrato in casa" nello storico (visibili nello screenshot
  dell'utente, oggi 18/09) provano che il backend risolve ancora un
  nome zona valido per il watch — quindi la config arriva al watch,
  ma probabilmente da un'altra fonte/stato residuo, non
  necessariamente dalla nuova collezione radice letta dalla
  phone-app. Rimosso il flag booleano: migrazione ora idempotente
  per-documento, ritentata (a basso costo) ad ogni chiamata.
  Sull'altro problema (status-card enorme, batteria non mostrata):
  riletto `MapScreen.kt`/`StatusCard` a HEAD — il codice risulta
  corretto (nessun `Modifier.weight()`, batteria mostrata se
  `state.battery != null`, larghezza fissa 260dp per card). Elemento
  chiave: lo screenshot dell'utente NON mostra il numero di versione
  appena aggiunto sotto "Dov'è" (atteso "v0.47.0" o successivo, commit
  a9e8223) — segno concreto che la build della phone-app attualmente
  installata è precedente a quel commit, quindi probabilmente anche
  al fix del 2026-09-18 della StatusCard stessa (commit 03a5e82).
  Non ho potuto verificare direttamente lo stato di Firestore da
  questa sessione (nessun accesso diretto). Prossimo passo per
  l'utente: ricompilare/reinstallare pulita la phone-app (non solo il
  watch) e verificare che "v0.3.0" (o superiore) compaia sotto
  "Dov'è" prima di ricontrollare entrambi i problemi.
- 2026-09-18: **Piano complicazione watch (stato SOS/messaggi/invio)
  scritto e messo in standby (v0.50.0)**, su richiesta esplicita
  dell'utente ("fai un piano e lasciamo il punto in standby per una
  fase successiva") — nessun codice toccato, solo pianificazione in
  Backlog Fase 3. Approccio scelto in fase di discussione con
  l'utente: una Complicazione Wear OS (si aggiunge al quadrante che
  l'utente già usa) invece di una watchface custom sostitutiva (molto
  più costosa e costringerebbe a cambiare quadrante). Dettaglio
  tecnico completo (priorità fra i 4 stati, componenti nuovi lato
  watch-app, passo manuale utente per attivarla) nella sezione
  "Complicazione watch" sotto "Backlog Fase 3". Da riprendere solo
  dopo aver chiuso i bug GPS/notifiche attualmente in diagnosi.
- 2026-09-18: **Build phone-app fallita, `Unresolved reference:
  BuildConfig` in `MapScreen.kt` (v0.51.0)**, segnalato dall'utente
  con screenshot di Android Studio. Causa: `buildFeatures.buildConfig`
  non era impostato a `true` in `phone-app/app/build.gradle.kts` — a
  differenza del watch-app (dove serviva già da prima per
  `DEVICE_TOKEN`/`BACKEND_BASE_URL`), sul phone-app non era mai stato
  necessario finché non si è iniziato a leggere
  `BuildConfig.VERSION_NAME` per mostrare il numero di versione
  (v0.47.0). Con AGP 8+ questa generazione non è più automatica.
  Aggiunta la flag: nessun altro cambiamento di codice necessario,
  l'import/uso di `BuildConfig` in `MapScreen.kt` era già corretto.
- 2026-09-18: **StatusCard confermata alta meta' schermo su build
  aggiornata (v0.52.0)** — l'utente ha inviato uno screenshot con
  "v0.3.0" gia' visibile in `TopAppBar` (fix v0.51.0), quindi l'ipotesi
  precedente di build stale (v0.49.0/v0.50.0) e' smentita: il bug e'
  reale. Causa trovata: `StatusCardRow` usa una `LazyRow` senza alcun
  vincolo di altezza, mentre `EventsList` — nella stessa schermata,
  poco sotto — usa gia' correttamente `heightIn(max = 160.dp)` per lo
  stesso motivo. Una `LazyRow`/`LazyColumn` non vincolata in altezza si
  espande a riempire tutto lo spazio verticale disponibile lasciato dal
  `Box(Modifier.fillMaxSize())` genitore invece di adattarsi al
  contenuto: la `Card` al suo interno riceve percio' vincoli "tight" e
  viene stirata. Aggiunto `heightIn(max = 140.dp)` alla `LazyRow`,
  stesso pattern gia' in uso in `EventsList`. La visualizzazione della
  batteria (altro problema segnalato insieme in v0.49.0) risultava
  invece gia' corretta nello stesso screenshot ("Batteria watch: 89%"
  in verde) — nessun fix necessario, confermava la lettura del codice
  gia' fatta in precedenza.
- 2026-09-18: **Chiarimento nickname/accoppiamento watch + causa
  probabile "zone ancora assenti" dopo il fix v0.52.0**. Il nickname
  (Impostazioni telefono) è solo un'etichetta su un `childId` già
  esistente — l'accoppiamento vero con un watch fisico avviene tramite
  il **token** salvato in `local.properties`/`BuildConfig.DEVICE_TOKEN`
  di quella specifica build watch (vedi `_lib/auth.js`,
  `resolveDeviceId`): per il bambino già esistente ("figlio", ora
  rinominato "andrea") l'accoppiamento è quello fatto manualmente ben
  prima dell'introduzione dei nickname, nessuna azione richiesta; per
  un nuovo bambino, "Aggiungi bambino" genera un nuovo token mostrato
  una sola volta, da incollare in `local.properties` del build per
  quel watch (vedi `SettingsScreen.kt`).
  Sulle zone ancora assenti: il fix della migrazione
  (`device-config.js`, v0.49.0/commit a6cb924) scatta solo quando il
  **watch** chiama `GET /api/device-config`
  (`GeofenceSyncWorker.doWork()`), non quando la phone-app legge la
  collezione `geofences` (lettura diretta Firestore, indipendente).
  `MainActivity.onCreate` accoda quel lavoro one-shot con
  `ExistingWorkPolicy.KEEP` (vedi riga ~259): se un lavoro con lo
  stesso nome univoco esiste già nel database di WorkManager (anche se
  completato molto tempo fa, prima del fix), riaprire semplicemente
  l'app **non** forza una nuova chiamata — serve o il prossimo giro
  periodico (ogni 6h) o un riavvio del watch, dove `BootReceiver` usa
  invece `ExistingWorkPolicy.REPLACE` (riga 32) e quindi forza sempre
  una chiamata fresca. Suggerito all'utente di riavviare di nuovo il
  watch (ora che il fix backend è live) per forzare subito la
  migrazione, invece di aspettare fino a 6 ore. Nessun codice cambiato
  per questo — comportamento di WorkManager voluto (evitare sync
  ridondanti ad ogni apertura app), non un bug; annotato qui solo come
  nota diagnostica per non ripetere l'indagine in futuro.
- 2026-09-18: **Causa reale (e fix) delle zone ancora assenti +
  allarme SOS bypass silenzioso + StatusCard a tutta larghezza
  (v0.54.0)**. La causa ipotizzata nella voce precedente era corretta
  ma il fix restava "aspetta o riavvia" — troppo fragile, l'utente ha
  ritestato e le zone erano ancora assenti. Risolto alla radice:
  `MainActivity.onCreate` (watch) ora accoda il one-shot
  `GeofenceSyncWorker` con `ExistingWorkPolicy.REPLACE` invece di
  `KEEP` (allineato a `BootReceiver`, che usava gia' `REPLACE`) — ogni
  apertura dell'app forza una sync fresca, non serve piu' aspettare il
  giro periodico (6h) o riavviare il watch.
  Allarme SOS: richiesto dall'utente ("puo' bypassare la modalita'
  silenziosa e far suonare il cellulare?") — la risposta era si',
  l'app lo fa gia' per l'allarme di uscita zona
  (`ExitAlarmService`/`ExitAlarmActivity`, `AudioAttributes.
  USAGE_ALARM`). Riusato lo stesso pattern per un nuovo
  `SosAlarmService`/`SosAlarmActivity`, innescato da una nuova push
  data-only `sos_alarm` che `trigger-event.js` manda al primo SOS di
  un episodio (stesso gate anti-spam di `shouldNotify`). A differenza
  dell'allarme di uscita zona (opt-in per-zona, `alarmOnExit`), l'SOS
  suona sempre — e' la funzione di sicurezza piu' critica dell'app.
  StatusCard: l'utente ha segnalato che restava non soddisfacente
  anche dopo v0.52.0 ("allargalo a tutto schermo e abbassalo") e che
  il colore soglia della batteria copriva anche l'etichetta "Batteria
  watch:", non solo il valore (un solo `Text` con l'intera stringa
  `battery_format`). Ridisegnata: `StatusCardRow` non e' piu' una
  `LazyRow` (v0.52.0 l'aveva solo vincolata con `heightIn`, non
  eliminata come causa) ma una `Column` + `forEach` — con pochi
  bambini non serve virtualizzazione, e una `Column` normale si adatta
  sempre al proprio contenuto senza il rischio v0.52.0. Ogni
  `StatusCard` e' ora una riga singola larga quanto lo schermo
  (nome, "X min fa · batteria%", pulsante), con etichetta e valore
  batteria in due `Text` separati cosi' solo il valore e' colorato.
  Domande dell'utente su "altri valori da condividere" e accesso a
  temperatura/segnale LTE del watch: risposto in chat (non ancora
  implementato, nessuna decisione presa) — temperatura batteria
  accessibile via `ACTION_BATTERY_CHANGED`/`EXTRA_TEMPERATURE` (nessun
  permesso speciale), segnale LTE via `TelephonyManager` (richiede
  `READ_PHONE_STATE`); da valutare in una fase successiva se
  richiesto esplicitamente.
- 2026-09-18: **Temperatura batteria + stato di carica (v0.55.0)**,
  richiesti dall'utente. Nuovo `BatteryInfo.kt` (watch-app, package
  `location`) legge percentuale/temperatura/carica in un solo punto
  tramite l'intento sticky `ACTION_BATTERY_CHANGED` (nessun permesso
  ne' sensore aggiuntivo, il sistema tiene gia' in cache l'ultimo
  intento di questo tipo) — sostituisce 4 copie duplicate di
  `currentBatteryPercent()` (solo percentuale) in
  `LocationTrackingService`, `LocationRequestWorker`, `SosWorker`,
  `SosLocationService`. Propagato fino alla phone-app: `LocationPoint`
  (watch+backend), `ingest-location.js`, `trigger-event.js` (SOS/
  "Invia posizione" — non `sos-heartbeat.js`, il ping ogni 30" durante
  un SOS attivo, dove temperatura/carica non cambiano abbastanza da
  giustificare la cadenza), `Models.kt`/`DeviceRepository.kt`/
  `StatusCard` (phone-app) sulla stessa riga compatta di v0.54.0.

  **Domande dell'utente su rilevamento cadute Wear OS e velocita'**
  (risposte in chat, nessuna implementazione — nessuna decisione
  presa, solo valutazione tecnica):
  - Il "rilevamento cadute" nel menu di sistema del Galaxy Watch4 e'
    una funzione proprietaria Samsung, non esposta da nessuna API
    pubblica Android/Wear OS: la nostra app non puo' ne' leggerne il
    trigger ne' condizionarla (es. "non scattare se la velocita' e'
    da bici"). Se attivata, chiama il flusso SOS/contatti *di
    Samsung*, indipendente dal nostro sistema (la posizione NON
    arriverebbe alla phone-app via il nostro backend). Verosimilmente
    gira su un coprocessore a basso consumo (come il contapassi), quindi
    impatto batteria atteso modesto, ma non verificabile da qui — solo
    Samsung ha questi numeri. Se Samsung espone gia' una tolleranza
    "durante attivita' sportiva" per ridurre i falsi positivi in
    bici, e' nelle sue impostazioni native, non nostre.
    Costruire un rilevamento cadute NOSTRO (accelerometro +
    euristica caduta libera/impatto, integrato con il nostro SOS)
    sarebbe fattibile in linea di principio, gate-abile con
    l'ActivityRecognition gia' in uso (ON_BICYCLE) per sopprimere falsi
    positivi in bici, ma e' un lavoro consistente (taratura soglie,
    rischio concreto di falsi positivi/negativi, consumo batteria
    continuo per il campionamento accelerometro) — da valutare solo se
    la funzione di sistema si rivela insufficiente (es. serve che
    l'allarme arrivi *al genitore tramite la nostra app*).
  - Mostrare la velocita' (gia' presente gratis in ogni fix GPS,
    `Location.getSpeed()`) non ha di per se' nessun costo aggiuntivo
    di batteria al ritmo di campionamento attuale (10 min da fermi,
    1 min in movimento) — il costo vero e' quello del GPS stesso, gia'
    pagato. Diventerebbe costoso solo se si volesse una velocita'
    "fluida"/quasi in tempo reale, che richiederebbe campionamento GPS
    molto piu' frequente.
- 2026-09-18: **Velocita' mostrata sulla mappa (v0.56.0)**, richiesta
  dall'utente dopo aver chiesto se il costo batteria di mostrarla
  fosse alto (risposta data la sessione precedente: no, e' gia'
  inclusa gratis in ogni fix GPS). Aggiunto `speedMps` a
  `LocationPoint` (watch-app) e al body di `triggerEvent`
  (`Location.getSpeed()`, popolato sia dal tracking periodico sia da
  SOS/"Invia posizione"), nuovo campo "speed" in
  `ingest-location.js`/`trigger-event.js` (stesso pattern di
  battery/batteryTemp/charging), `DeviceState.speedMps` (phone-app).
  Mostrata in `StatusCard` convertita in km/h (unica conversione
  lato UI, il dato resta in m/s ovunque altro) e solo sopra 2 km/h,
  per non mostrare rumore GPS come "velocita'" quando il watch e'
  fermo.
- 2026-09-18: **Zone ancora assenti (nuova ipotesi) + fix banner SOS
  fantasma ad ogni avvio (v0.57.0)**, entrambi dallo stesso screenshot
  dell'utente.

  Zone: lo screenshot mostra un evento "Entrato in casa" nello storico
  — prova che la zona "casa" esiste GIA' correttamente nella
  collezione radice `geofences` letta dal backend (Admin SDK, bypassa
  le regole Firestore): se non ci fosse, `trigger-event.js` userebbe
  il default `zoneName = "zona"`, non il nome vero. Il fix di
  migrazione (v0.49.0) e quello di sincronizzazione (v0.54.0) stanno
  quindi funzionando lato server. Ma la phone-app legge la stessa
  collezione con il Firestore CLIENT SDK, soggetto alle regole di
  sicurezza (`backend/firestore.rules`) — regole che nel repository
  sono corrette (`match /geofences/{zoneId} { allow read, write: if
  isParent(); }`, aggiunto in v0.6.0/commit 2f21641) ma la cui
  pubblicazione sul progetto Firebase reale e' un passo MANUALE (era
  gia' elencato come tale nel piano originale della fase 2/4), mai
  automatizzabile da questa sessione (nessun accesso a Firebase
  Console/CLI). Se le regole live sono ancora quelle precedenti al
  supporto multi-bambino, il client verrebbe bloccato in silenzio
  (snapshot con errore -> lista vuota) nonostante i dati esistano —
  spiega perfettamente il pattern osservato (dati OK lato
  watch/backend, invisibili lato phone-app). Comunicato chiaramente
  all'utente come prossimo passo di verifica: ripubblicare
  `backend/firestore.rules` (Console Firebase → Firestore → Regole, o
  `firebase deploy --only firestore:rules`).

  Banner SOS fantasma: "all'avvio dell'app sul watch compare il
  banner SOS inviato ma in realta' non arriva sul cellulare" —
  causa trovata in `observeWorkOutcomes()` (MainActivity.kt watch,
  v0.7.0): `getWorkInfosForUniqueWorkLiveData` emette SUBITO, alla
  sottoscrizione in `onCreate`, lo stato piu' recente gia' presente
  nel database di WorkManager, anche se concluso ore/giorni prima (un
  SOS di test passato). Il dedup `lastNotifiedSosWorkId` era una
  variabile in memoria, azzerata ad ogni riavvio dell'app: quella
  prima emissione "vecchia" superava sempre il controllo e
  ri-mostrava il Toast come fosse un esito nuovo — nessun SOS reale
  veniva rimandato, coerente con "non arriva sul cellulare". Aggiunto
  un controllo di baseline per lavoro (`sosBaselineChecked`/
  `locationBaselineChecked`): la primissima emissione dopo l'apertura
  dell'app, se gia' in stato finale, si registra come "gia' vista"
  senza Toast; se invece e' ancora in corso (es. RETRY per GPS
  assente, sopravvissuto a un riavvio), il comportamento v0.7.0 resta
  invariato.
- 2026-09-18: **Fix pulsante "Aggiorna posizione" schiacciato +
  notifica chat col nome del bambino (v0.58.0)**, dallo stesso giro di
  feedback dell'utente su screenshot v0.7.0.

  Pulsante schiacciato: la riga dettagli di `StatusCard` (nome, ultimo
  visto, batteria, temperatura, carica, velocita') era cresciuta nel
  tempo (v0.56.0/v0.57.0) fino a occupare quasi tutta la larghezza
  disponibile nella stessa `Row` del pulsante "Aggiorna posizione",
  che veniva compresso in una colonna strettissima. Non essendo
  possibile usare `Modifier.weight()` in questa versione di Compose
  (vincolo gia' noto e documentato in `SettingsScreen.kt`), la card e'
  stata ristrutturata in due `Row` impilate in una `Column`: la prima
  con solo nome + pulsante (entrambi corti, mai in conflitto per lo
  spazio), la seconda con la sola riga dettagli, libera di andare a
  capo su piu' righe se necessario invece di rubare spazio al
  pulsante.

  Notifica "messaggio da XX": richiesto dall'utente — il titolo era
  il generico "Messaggio dal watch", poco utile ora che il sistema
  supporta piu' bambini. Verificato che il backend
  (`send-message.js`, gia' da v0.4.0) mandava gia' `senderName` nel
  payload data della push; bastava quindi un cambiamento lato client,
  `FcmService.kt`, per usarlo nel titolo (`"Messaggio da {nickname}"`),
  con fallback sul vecchio testo fisso per robustezza payload.

  Sul fronte "non ci sono i nuovi dati" (temperatura/carica/velocita'
  assenti sulla card nonostante il codice li gestisca dalla v0.6.0/
  v0.7.0): nessun bug trovato — le chiavi JSON `batteryTemp`/
  `charging`/`speed` sono coerenti end-to-end (watch → backend →
  phone-app). Ipotesi comunicata all'utente: probabile che sia stato
  ricompilato/reinstallato solo il phone-app finora (confermato a
  v0.7.0 dallo screenshot), mentre il watch-app (dove risiede il
  codice che LEGGE ed INVIA questi valori, `BatteryInfo.kt` e le
  estensioni di `LocationPoint`/`triggerEvent`) resta su un build piu'
  vecchio — richiede un rebuild/reinstall separato sul watch fisico.
- 2026-09-18: **Regole Firestore ripubblicate, confermato dall'utente
  (screenshot Console) + tre fix UI su GeofenceScreen (v0.59.0)**.

  L'utente ha controllato la cronologia di pubblicazione delle regole
  nella Firebase Console: l'ultima pubblicazione live risultava del 10
  settembre alle 22:17, mentre il commit che aggiunge la regola radice
  `geofences/{zoneId}` (2f21641) e' dell'11 settembre alle 16:26 — cioe'
  DOPO l'ultima pubblicazione, confermando esattamente l'ipotesi gia'
  scritta in v0.57.0. Fornito il testo completo delle regole via chat
  (l'utente era da cellulare, non riusciva a copiare da terminale/repo)
  per la pubblicazione manuale. Dopo la pubblicazione le zone sono
  ricomparse correttamente sulla phone-app — bug chiuso, nessun codice
  da cambiare (le regole nel repo erano gia' corrette, mancava solo il
  deploy).

  Con le zone finalmente visibili, l'utente ha potuto testare
  `GeofenceScreen` per la prima volta e ha segnalato tre problemi UI,
  tutti corretti nella stessa sessione:
  1. Campo di ricerca indirizzo troppo alto (label fluttuante + riga
     separata col pulsante "Cerca" sotto) — sostituito con placeholder
     + lente di ingrandimento come icona dentro il campo stesso
     (`trailingIcon` dell'`OutlinedTextField`), eliminando la riga
     pulsante.
  2. Righe della lista zone troppo alte, nessun indizio visivo che ce
     ne fossero altre fuori schermo — padding verticale ridotto e
     aggiunta una scrollbar verticale disegnata a mano con
     `Modifier.drawWithContent` sopra la `LazyColumn` (Compose
     Material3 in questa versione non offre uno scrollbar nativo per
     Android, solo per desktop via `rememberScrollbarAdapter`; nessuna
     libreria esterna aggiunta).
  3. Switch "Allarme sonoro all'uscita" disallineato/tagliato a bordo
     schermo nel pannello di creazione/modifica zona — stessa causa
     radice gia' vista per il pulsante "Aggiorna posizione" schiacciato
     in `MapScreen.kt` (v0.58.0): `Modifier.weight()` non disponibile
     in questa versione di Compose, quindi una Column di larghezza non
     vincolata (label+hint) nella stessa Row di un altro elemento a
     larghezza fissa (lo Switch) puo' spingerlo fuori dai margini
     quando il testo e' lungo. Stessa soluzione: due righe impilate
     invece di una sola, applicata qui alla `ToggleRow` condivisa da
     tutti i toggle di `GeofenceScreen` (notifiche, allarme, assegna a
     bambino).
- 2026-09-18: **StatusCard riformattata a una riga per informazione,
  valori in grassetto; rimossa soglia minima velocita' (v0.60.0)**.
  Richiesto dall'utente un formato fisso: "Ultima posizione: xx minuti
  fa" / "Batteria: xx%" / "Temperatura batteria: xx°C" / "Velocita': xx
  km/h", ciascuno su riga propria con solo il valore in grassetto
  (nuovo composable `InfoLine`, etichetta + valore come due `Text`
  separati nella stessa `Row`, `fontWeight = FontWeight.Bold` solo sul
  secondo). `formatRelativeTime()` (util/TimeFormat.kt) e' stata
  spogliata del prefisso "Ultima posizione ricevuta" che aveva da
  prima — sarebbe stato duplicato con la nuova etichetta di riga — ora
  ritorna solo la parte relativa.

  L'utente ha segnalato che la velocita' "per ora non compare": la
  soglia minima di 2 km/h sotto cui la riga spariva del tutto
  (introdotta in v0.56.0 per filtrare il "rumore" GPS di un watch
  fermo) e' stata rimossa — con un formato a righe fisse non ha piu'
  senso nascondere il dato quando disponibile. Comunicato pero'
  all'utente che se la riga resta assente anche dopo questo fix, la
  causa piu' probabile e' che `Location.hasSpeed()` lato watch sia
  ancora `false` (nessun fix GPS con velocita' valida — serve un
  minimo di movimento reale, non e' un bug ma un limite fisiologico
  del GPS Android), non un problema di soglia UI.
- 2026-09-18: **DND automatico per zona (v0.61.0)**, richiesto
  dall'utente: "aggiungi alle zone un flag per attivarlo e disattivarlo
  all'ingresso e uscita dalla stessa, cosi' quando arriva a scuola va
  in dnd in automatico". Decisione di design principale: **un solo
  flag** (`dndOnZone`) invece di due toggle indipendenti "attiva
  all'ingresso"/"disattiva all'uscita" — con due flag separati sarebbe
  possibile configurare solo "attiva all'ingresso" senza il simmetrico
  "disattiva all'uscita", lasciando il DND acceso per sempre dopo che
  il bambino lascia la zona (bug di configurazione silenzioso, non
  ovvio da notare). Un solo toggle rende impossibile quello stato:
  ingresso -> DND on, uscita -> DND off, sempre insieme.

  Dove risolvere l'azione (watch vs backend): il cambio DND e'
  un'impostazione di SISTEMA LOCALE del watch
  (`NotificationManager.setInterruptionFilter`), quindi deve avvenire
  sul watch. Ma la CONOSCENZA di quali zone hanno `dndOnZone` attivo
  vive in Firestore (scritta dalla phone-app). Anziche' sincronizzare
  questo flag al watch tramite `/api/device-config` (che il watch
  legge per registrare le geofence sulla Geofencing API di Android, e
  che quindi il watch consulterebbe comunque doversi ricordare
  localmente quale zona ha il flag), si e' scelto di riusare la
  chiamata che il watch fa GIA' ad ogni transizione geofence
  (`POST /api/trigger-event`, usata per notificare ingresso/uscita al
  genitore): il backend, che deve comunque leggere il documento della
  zona per nome/notifyOnEnter/notifyOnExit/alarmOnExit, legge anche
  `dndOnZone` e restituisce nella STESSA risposta HTTP un campo `dnd`
  (`true`/`false`/assente) — zero chiamate di rete aggiuntive, zero
  stato da sincronizzare/persistere sul watch. `GeofenceEventWorker.kt`
  applica subito il cambio se `dnd` non e' null.

  Permesso di sistema: `ACCESS_NOTIFICATION_POLICY` non e' concedibile
  via codice (a differenza dei permessi runtime standard) — va
  autorizzato una tantum dall'utente in Impostazioni > Accesso
  speciale > Non disturbare, toccando fisicamente il watch. Se manca,
  `DndController.setDnd()` non fallisce in silenzio (il genitore
  vedrebbe una zona configurata che pero' non fa mai nulla, senza
  sapere perche'): mostra una notifica con un tasto diretto a quella
  schermata di sistema.

  Scelta del filtro: `INTERRUPTION_FILTER_PRIORITY` (lo stesso del
  "Non disturbare" standard di Android, silenzia le notifiche normali)
  invece di `INTERRUPTION_FILTER_NONE` (silenzio totale, blocca anche
  le sveglie) — coerente con l'obiettivo "niente distrazioni a
  scuola", senza gli effetti collaterali piu' aggressivi del silenzio
  totale. Nessun canale dell'app e' stato marcato `bypassDnd(true)`:
  di default anche le notifiche dell'app stessa (chat, sos_cancel,
  ecc.) vengono silenziate mentre il DND e' attivo — comportamento
  atteso e comunicato all'utente, non un bug.

- **2026-09-19 — Notifiche automatiche batteria scarica watch (10%/5%/2%).**
  Richiesto dall'utente: notifica al genitore quando la batteria del
  watch scende al 10% e di nuovo al 5%; al 2% anche richiesta forzata
  della posizione e invio automatico al watch di un messaggio in chat
  con testo fisso "non hai più batteria, aspettami dove sei.".

  Vincolo architetturale di partenza: Vercel Hobby permette al massimo
  12 Serverless Function per deployment (vedi header di
  `parent-command.js`), gia' a 10 file in `backend/api/`. Niente nuovo
  endpoint: la logica vive in un nuovo helper condiviso,
  `_lib/batteryAlerts.js` (sotto `_lib/`, escluso dal conteggio per la
  convenzione Vercel dei file con underscore), richiamato dai due soli
  endpoint che gia' scrivono lo stato batteria su
  `devices/{childId}` — `ingest-location.js` (tracking periodico) e
  `trigger-event.js` (SOS/geofence/location_request) — subito dopo il
  loro `batch.commit()` gia' esistente, senza aggiungere scritture
  Firestore extra al percorso principale.

  Dedup per "episodio": nuovo campo `devices/{childId}.batteryAlertLevel`
  (null | 10 | 5 | 2) tiene la soglia piu' severa gia' notificata, per
  non rimandare la stessa notifica ad ogni singolo campione mentre la
  batteria resta bassa. Reset (isteresi) solo quando il watch torna in
  carica o la batteria risale sopra il 15% — una soglia di reset più
  alta della più bassa soglia di allarme (10%) evita che un valore che
  oscilla proprio li' intorno (10%/11%/10%) riapra un "nuovo episodio"
  ad ogni giro.

  Notifica 10%/5%: push FCM `notification`+`data` (`type: battery_low`)
  sul topic `parents`, stesso schema gia' in uso per SOS/geofence in
  `trigger-event.js` — non serve nessun codice nuovo lato phone-app: il
  fallback generico gia' presente in `FcmService.kt`
  (`message.notification` senza un `type` riconosciuto ->
  `postNotification()`) la mostra automaticamente, sia in foreground sia
  (via tray di sistema) in background.

  Azioni al 2%: riusano meccanismi gia' esistenti invece di
  inventarne di nuovi — `location_request` e' lo stesso data-only gia'
  mandato dal pulsante "Aggiorna posizione" (`handleRequestLocation` in
  `parent-command.js`); il messaggio automatico scrive su
  `devices/{childId}/messages` con lo stesso schema di
  `handleMessage()` (`sender/senderId/senderName/text`, mittente
  "gWatch"/`senderId: "system"` per non impersonare un genitore vero) e
  manda la stessa push data-only `type: chat` — compare quindi nella
  chat del watch come un messaggio normale, non solo come notifica di
  sistema passeggera. Nessun codice nuovo lato watch-app: `FcmService.kt`
  (watch) gestisce gia' `chat` e `location_request` per gli stessi
  motivi.

  **Aggiornamento 2026-09-19 (vedi voce successiva)**: l'autonomia
  residua in ore, qui sopra solo analizzata, e' stata poi implementata
  chiedendola al sistema operativo del watch invece che stimandola da
  uno storico — vedi "Autonomia residua batteria (ore)" sotto.

- **2026-09-19 — Autonomia residua batteria (ore), chiesta al sistema
  operativo del watch invece che stimata da uno storico.** L'utente ha
  chiesto esplicitamente di valutare se il dato si potesse ottenere
  dal sistema operativo invece di ricostruirlo lato app — si puo':
  Android espone via `BatteryManager` due proprieta' hardware,
  `BATTERY_PROPERTY_CHARGE_COUNTER` (capacita' residua, µAh) e
  `BATTERY_PROPERTY_CURRENT_NOW` (corrente istantanea, µA, negativa in
  scarica). `ore = capacita' residua / corrente di scarica`
  (µAh / µA = h) e' lo stesso dato grezzo che il sistema usa per le
  proprie stime di batteria in Impostazioni — niente storico da
  accumulare (a differenza dell'approccio "velocita' di scarica da due
  campioni recenti" analizzato in precedenza, scartato: piu' complesso
  E meno accurato di chiedere direttamente al sistema), niente
  chiamata di rete aggiuntiva, il valore e' gia' pronto ad ogni
  lettura e riflette l'uso reale (schermo/GPS/LTE) invece di
  un'estrapolazione lineare.

  Rischio noto e gestito: non tutti i kernel/dispositivi espongono
  queste proprieta' in modo affidabile (`getIntProperty` ritorna
  `Int.MIN_VALUE` se non supportata; bug noti su alcuni kernel che
  riportano `CURRENT_NOW` nell'unita' sbagliata, es. nanoampere invece
  di microampere). `BatteryInfo.readHoursRemaining()` (watch-app)
  applica percio' un controllo di plausibilita' (0.1h–100h): un
  risultato fuori scala diventa `null` (nessuna stima mostrata)
  invece di un numero chiaramente sbagliato in app. Calcolato solo
  quando il watch non e' in carica (in carica avrebbe senso semmai un
  "tempo alla carica completa", non richiesto).

  Percorso dato: `BatteryInfo.kt` (watch, nuovo campo
  `hoursRemaining` su `BatterySnapshot`) -> `LocationPoint`/
  `BackendClient.triggerEvent()` (stesso pattern di batteryTemp/
  charging/speed) -> `devices/{childId}.batteryHoursRemaining`
  (scritto da `ingest-location.js`/`trigger-event.js`, non salvato
  nello storico `locations`: e' gia' una stima diretta, non serve
  riguardarla nel tempo) -> `StatusCard` (phone-app), nuova riga
  "Autonomia residua: ~Xh Ymin" sotto la percentuale batteria, visibile
  solo quando il valore e' disponibile (assente in carica o su
  hardware che non lo espone in modo affidabile).

- **2026-09-19 — Fix: cron di pulizia backend falliva ogni notte (HTTP
  500).** Segnalato dall'utente (screenshot GitHub Actions,
  `cleanup-cron.yml` job "cleanup" in errore da almeno una notte).
  Diagnosi: rilanciato manualmente il workflow (`workflow_dispatch`)
  sull'ultimo commit del branch per escludere che fosse un residuo del
  commit "rogue" del Gradle wrapper della notte precedente — falliva
  identico anche li', quindi non c'entrava. I log del job mostrano solo
  `HTTP 500 {"ok":false,"error":"Internal server error"}`: il messaggio
  generico di `wrapHandler` (`_lib/errors.js`), che inghiotte
  volutamente il dettaglio dell'errore per non esporlo al chiamante —
  nessun accesso ai log reali della funzione Vercel da questa sessione,
  quindi causa root-cause andata cercata per esclusione nel codice.

  `node --check` su tutti i file di `backend/api/` non ha trovato
  errori di sintassi (esclusa quella classe di problema, gia' vista in
  passato con un push diretto della IA locale). Trovato invece: la
  v0.4.0 di `cleanup.js` (16/9) ha aggiunto `purgeExpired(db, "events")`
  — una query `collectionGroup("events").where("expiresAt", "<=", ...)`
  — ma non il corrispondente override in `firestore.indexes.json`
  (presente solo per `locations`/`quota`/`messages`, mai esteso a
  "events"). Firestore richiede un indice esplicito a scope "Collection
  group" per una query di range su un collection group; senza,
  rifiuta la query con un errore che `Promise.all()` propaga e
  `wrapHandler` trasforma nel 500 generico visto nei log. Stessa classe
  di bug gia' capitata con le regole di sicurezza (una modifica di
  codice che presuppone una configurazione Firestore mai effettivamente
  pubblicata) — vedi voce piu' in alto sulle zone che non comparivano.

  Fix: aggiunto l'override mancante in `firestore.indexes.json`. **Il
  file nel repo da solo non risolve nulla**: gli indici Firestore vano
  ripubblicati esplicitamente (Console: Firestore Database → Indexes →
  Single field → Add index, collection "events", campo "expiresAt",
  scope "Collection group", ordine Ascending; oppure da riga di comando
  con `firebase deploy --only firestore:indexes` da `backend/`, che
  legge lo stesso `firestore.indexes.json` — `backend/firebase.json` e'
  gia' configurato per questo). Fino alla ripubblicazione, il cron
  continuera' a fallire ogni notte (fallimento innocuo: nessun dato
  viene perso, solo la pulizia dello storico scaduto non avviene finche'
  l'indice non e' attivo).

  **Aggiornamento 2026-09-22**: indice pubblicato dall'utente su
  Firestore Console (tab "Campo singolo", non il wizard "Crea indice"
  generico — quello e' per indici compositi multi-campo e rifiuta da
  solo un indice a campo singolo con questo messaggio: "this index is
  not necessary, configure using single field index controls").
  Verificato rilanciando il workflow a mano (`workflow_dispatch`):
  verde.

- **2026-09-22 — Fix: "lastSeen" del device poteva regredire
  all'indietro nel tempo.** Segnalato dall'utente: la StatusCard
  mostrava "ultima posizione 5 ore fa" mentre lo storico ("Percorso
  24h", che legge `devices/{childId}/locations`) aveva gia' punti molto
  piu' recenti — la discrepanza tra le due viste era il primo indizio
  che uno storico corretto e uno stato "attuale" sbagliato potessero
  divergere.

  Causa: sia `ingest-location.js` che `trigger-event.js` scrivevano lo
  stato "attuale" del device (`lastLocation`/`lastSeen`/batteria/ecc.)
  con un `batch.set(deviceRef, {...}, {merge:true})` **incondizionato**
  — l'ultimo dato di QUESTA chiamata vinceva sempre, senza controllare
  se fosse davvero piu' recente di quanto gia' salvato. Con
  connettivita' instabile (es. dentro un edificio scolastico) due
  upload possono restare "in volo" insieme: il tracking periodico
  automatico e l'upload "immediato" innescato al superamento soglia
  buffer (`LocationTrackingService.kt`, `UPLOAD_TRIGGER_THRESHOLD`)
  usano nomi di unique work DIVERSI (nessuna mutua esclusione tra
  loro), e se quella con dati piu' vecchi (es. rimasta a ritentare per
  un po' con successo tardivo) completa DOPO quella con dati piu'
  freschi, la sua scrittura vince e regredisce "lastSeen" indietro nel
  tempo — pur restando tutti i singoli punti storici corretti (ogni
  punto e' un documento a se' in `locations`, mai sovrascritto, per
  questo "Percorso 24h" mostrava sempre il dato giusto).

  Fix: lo stato "attuale" ora si scrive dentro una **transazione
  Firestore** che legge il `lastSeen` gia' salvato e scrive il nuovo
  stato solo se il timestamp in arrivo e' strettamente piu' recente —
  altrimenti la chiamata e' un no-op su quel fronte (lo storico
  `locations`/l'evento restano comunque scritti sempre, incondizionati:
  non c'e' un "piu' vecchio" da proteggere quando ogni voce e' un
  documento a se'). Le notifiche di batteria scarica (10%/5%/2%) sono
  guardate allo stesso modo: non vengono valutate su un dato che si e'
  appena scoperto essere piu' vecchio di quanto gia' noto. Eccezione
  deliberata in `trigger-event.js`: `sosActive` va sempre marcato
  `true` su un evento "sos", indipendentemente dalla freschezza del fix
  di posizione — e' un flag di sicurezza (far scattare/mantenere
  l'allarme), non un dato di posizione da proteggere da regressioni
  temporali, e non deve mai poter essere "perso" per una race di rete.

  Non toccato deliberatamente: la causa a monte lato watch (le due
  unique work "location-upload-periodic"/"location-upload-oneshot" che
  possono correre in parallelo) non e' stata unificata — la garanzia va
  comunque tenuta lato backend (un client non puo' mai essere l'unica
  fonte di verita' sull'ordine di arrivo delle proprie richieste di
  rete), e centralizzarla li' protegge automaticamente anche da futuri
  path di scrittura simili, non solo da questi due.

- **2026-09-22 — Richiesta posizione automatica all'apertura app +
  data/ora assolute in "Ultima posizione".** Entrambe richieste
  dall'utente. La prima riusa `AppViewModel.requestLocation` (stessa
  funzione del pulsante "Aggiorna posizione") per ogni bambino noto,
  innescata da un `LaunchedEffect(children)` in `MapScreen.kt` con un
  flag `hasAutoRequestedLocation` per farla scattare una sola volta per
  apertura (children arriva vuoto al primo istante e si popola via
  listener Firestore, quindi non basta `LaunchedEffect(Unit)`). Nessuna
  modifica backend: e' la stessa identica chiamata gia' esistente.
  La seconda aggiunge `formatLastSeen()` in `MapScreen.kt`, che
  affianca al relativo gia' esistente (`formatRelativeTime`, util/
  TimeFormat.kt) la data/ora assolute nello stesso formato "dd/MM
  HH:mm" gia' in uso in `EventsList` — utile perche' un dato vecchio di
  ore da solo ("5 h fa") non dice "di che giorno" quando si riapre
  l'app dopo un po'.

- **2026-09-22 — Rimossi i riferimenti nominali all'AI locale
  dell'utente dal repo.** Richiesto dall'utente. Riguardava le due
  identita' comparse nell'incidente del 2026-09-18 (v0.42.0, vedi voce
  sopra): commenti "corretto da qwen3.8-Flash-Next il 16-9-26"/"Bug
  (qwen3.8turbo-coder, ...)" sparsi in backend, phone-app e watch-app
  (circa 30 occorrenze), il file `PLAN-qwen3.8turbo-coder.md` (piano
  interamente relativo a quella sessione, eliminato: nessuna
  informazione li' non gia' coperta da questo file) e le due righe di
  attribuzione in `TESTING-E2E.md` (contenuto della procedura di test
  mantenuto inizialmente, essendo l'unico dei due file non ridondante
  con CONTEXT.md). In ogni commento tecnico e' stata tenuta la
  spiegazione (perche' quel cambio, cosa correggeva) e tolta solo
  l'attribuzione nome+data — le decisioni stesse (confronto
  costant-time, push via topic FCM, retention eventi, bump SDK 35, fix
  del battery indicator) non sono state toccate, solo la loro
  attribuzione testuale. Le voci storiche di CHANGELOG.md/CONTEXT.md
  sull'incidente restano come promemoria dei fatti realmente accaduti,
  col nome dell'AI anonimizzato ("un'altra AI locale") anziche' rimosse
  del tutto: coerente col mandato di CLAUDE.md che questi due file
  siano un log di decisioni accurato, non solo un elenco di feature.

  **Commit concorrente dell'utente durante questa sessione**: al
  momento del push di questa pulizia, `git push` e' stato respinto
  ("fetch first") — un commit `0c29ad9` era arrivato nel frattempo
  sullo stesso branch (autore ancora taggato `qwen3.8turbo-coder`, il
  suo strumento locale), che cancella interamente `TESTING-E2E.md` (lo
  stesso file appena ripulito qui sopra dalle sole righe di
  attribuzione). L'utente ha confermato di aver cancellato il file
  personalmente: non e' un'azione autonoma dell'AI locale, solo un
  commit fatto tramite quello strumento mentre questa sessione era
  ancora al lavoro sullo stesso branch. Cancellazione accettata in
  fase di merge (il file era ormai comunque privo di scopo distintivo
  dopo la rimozione dell'attribuzione). Promemoria pratico, non
  legato all'incidente v0.42.0: modifiche dirette dell'utente sullo
  stesso branch durante una sessione attiva sono normali, quindi un
  `git push` puo' essere respinto anche senza che sia successo nulla
  di anomalo — basta un fetch/merge prima di ripushare.

- **2026-09-22 — Review di sicurezza `qwen_plan.md` (locale, mai
  pushata su GitHub) e isolamento tra famiglie diverse (v0.68.0).**
  L'utente ha chiesto di leggere una nuova analisi della sua AI locale
  (tag "qwen3.8-27B-UD-IQ4_XS", diverso dai tag "qwen3.8turbo-coder"/
  "qwen3.8-Flash-Next" dell'incidente v0.42.0 — stessa AI, versione/
  quantizzazione diversa) e valutare se le idee fossero fondate prima
  di implementarle, avendo gia' visto quella stessa AI produrre in
  passato sia idee valide sia esecuzioni bacate. Verificato ogni punto
  sul codice reale (non sulla sola lettura del documento) prima di
  costruirci sopra un piano: la maggior parte dei punti critici era
  confermata e alcuni erano *piu'* gravi di come descritti (vedi sotto
  e CHANGELOG.md v0.68.0); un paio di punti minori erano invece stale
  o gia' risolti (retry SOS gia' espedito via setExpedited(),
  GeofenceSyncWorker gia' segnato come scelta deliberata dalla stessa
  review, tre voci di "igiene documentazione" — placeholder in
  CHANGELOG.md, entry [0.33.1] vuota, IMPROVEMENT_PLAN.md — inesistenti
  sul branch attuale) — scartati dal piano.

  **Ownership genitore->bambino (il gap piu' critico trovato).**
  `checkParentAuth()` verificava solo che `parents/{uid}` esistesse,
  mai *quali* bambini quel genitore potesse vedere/comandare: con una
  seconda famiglia sullo stesso progetto Firebase, ogni genitore
  avrebbe potuto leggere posizione/storico/chat di bambini non suoi e,
  via `parent-command.js`, silenziare un SOS altrui. Stesso buco lato
  regole Firestore (`isParent()` non dipendeva dal device) e lato push
  FCM (topic globale "parents", non ancora corretto in questa fase —
  vedi Fase 2 non ancora implementata sotto). Prima di implementare ho
  dovuto decidere un punto di design non deducibile dal codice: come
  deve funzionare l'accesso di un SECONDO genitore della stessa
  famiglia quando il primo crea un bambino. Chiesto esplicitamente
  all'utente (due opzioni: "e' sempre stata una sola famiglia,
  familyId condiviso tra tutti i parents/* esistenti" vs "famiglie
  davvero isolate con un flusso di invito esplicito") — scelta la
  seconda, piu' corretta per il caso reale (piu' famiglie sullo stesso
  deployment) anche se piu' lavoro della prima.

  Implementato: campo `familyId` su `parents/{uid}` (mai scrivibile
  dal client — solo `create_child`/`accept_family_invite` in
  `parent-command.js`, altrimenti un genitore autenticato potrebbe
  auto-assegnarsi la famiglia di un altro indovinando/forzando l'id),
  `devices/{childId}` e `geofences/{zoneId}`. `firestore.rules`
  v0.7.0: `myFamilyId()` (un `get()` sul proprio doc parents) confrontato
  col `familyId` del documento richiesto, per lettura su
  devices/locations/events/messages (le subcollection non hanno un
  proprio familyId, lo leggono dal device padre con un secondo `get()`)
  e per read/create/update/delete su geofences (in creazione il valore
  dichiarato dal client deve combaciare col proprio, non e' piu'
  modificabile dopo). `parent-command.js` v0.4.0:
  `verifyChildOwnership()` chiamata per ogni azione con un `childId`
  esplicito, unificando anche `set_nickname` (prima un caso a parte
  senza nessun controllo) sotto lo stesso gate. `create_child` assegna
  il familyId al nuovo bambino, generandone uno nuovo al volo
  (`ensureFamilyId`, self-heal) se il genitore non ne ha ancora uno —
  stesso stile della migrazione legacy del token in `_lib/auth.js`.
  Nuove azioni `create_family_invite` (genera un codice a singolo uso,
  TTL 24h, in una nuova collezione `familyInvites` backend-only, stesso
  trattamento di quota/messages nelle regole) e
  `accept_family_invite` (associa il genitore chiamante al familyId
  del codice; rifiuta se il genitore ha gia' una propria famiglia CON
  bambini, per non perdere per sbaglio l'accesso a quelli entrando in
  un'altra famiglia per errore) — nuova sezione "Genitori" in
  `SettingsScreen.kt` (phone-app), stesso pattern "genera, mostra in un
  dialogo, copia" gia' in uso per il token di un nuovo bambino.

  **Bug di migrazione geofence trovato durante la verifica, non solo
  nel documento originale**: `ensureGeofencesMigrated` (device-config.js)
  scriveva `childIds: [childId]` con `merge:true` — su un campo array
  il merge di Firestore SOSTITUISCE il valore invece di unirlo — E la
  subcollection legacy non viene mai svuotata dopo la copia, quindi la
  funzione si ripete a OGNI sync del watch, non solo alla prima: ogni
  volta azzerava i `childIds` di una zona gia' migrata, staccandola da
  altri bambini a cui fosse stata assegnata nel frattempo dalla
  phone-app. La review originale descriveva solo il rischio teorico di
  due subcollection con lo stesso id zona; il meccanismo reale
  (rieseguita ad ogni chiamata) e' piu' subdolo e piu' probabile.
  Sostituito con `FieldValue.arrayUnion(childId)`; approfittata la
  stessa funzione per stampare anche il nuovo `familyId` (letto dal
  device) sulle zone migrate.

  **Richiesta dell'utente sull'attribuzione**: a differenza della
  pulizia di riferimenti del giro precedente (rimozione totale), qui
  l'utente ha chiesto di taggare esplicitamente questi fix come
  "individuato da qwen3.8-27B-UD-IQ4_XS implementato da Sonnet 5" —
  applicato nei commenti/Storico versioni di ogni file toccato e in
  questa voce, coerente con lo stile "chi ha trovato cosa" gia' in uso
  nel progetto (es. "segnalato dall'utente").

  **Non ancora fatto** (resta nel piano concordato con l'utente, non
  implementato in questo giro): Fase 2 (topic FCM per-bambino invece
  del topic globale "parents" — stesso genere di leak cross-famiglia,
  ma lato notifiche push invece che lato dati Firestore), Fase 3 (race
  upload worker sul watch, conteggio quota per scrittura invece che
  per chiamata), Fase 4 (fix minori: maxDuration cleanup.js, range
  lat/lon, OkHttpClient singleton sul watch, ecc.), Fase 5 (job CI
  `npm test`). Ordine di priorita' invariato rispetto al piano
  originale in chat.

- **2026-09-23 — Evento "uscito da casa" mai arrivato una mattina:
  diagnosi e fix del timestamp sui retry geofence (v0.69.0).**
  L'utente ha segnalato lo stesso giorno due sintomi: nessuna
  notifica di batteria scarica (watch sceso al 9%) e nessun evento
  geofence per tutta la mattina, poi un'improvvisa raffica di eventi
  "entrato/uscito scuola" concentrata tra le 13:23 e le 13:49 (screenshot
  mappa/lista eventi). Ho verificato nel codice che `checkBatteryAlerts()`
  gira SOLO dentro una chiamata backend gia' riuscita
  (ingest-location.js/trigger-event.js): niente avviso batteria e
  nessun evento nello stesso arco orario sono quindi la stessa causa,
  non due bug distinti — il watch non ha completato nessuna chiamata al
  backend per ore, non solo il tracking periodico ma nemmeno la
  geofence di uscita da "Casa" (confermata dall'utente come zona
  esistente, attiva, con notifica di uscita abilitata — quindi non un
  problema di configurazione).

  Non e' stato possibile risalire alla causa ultima (nessun accesso a
  log del watch da questa sessione, l'utente non sapeva dire se
  l'orologio avesse connettivita' LTE stamattina): resta una fra
  "nessuna rete per ore", "transizione non rilevata dal sistema" o
  "invio fallito e mai davvero ritentato" — tre cause diverse con lo
  stesso sintomo osservabile. Suggerito all'utente, per una prossima
  occorrenza, `adb logcat` (anche via WiFi debugging) filtrato su
  `GeofenceBroadcastReceiver|GeofenceEventWorker|LocationTrackingService`
  per distinguerle in tempo reale.

  Durante l'indagine e' emerso pero' un bug concreto e indipendente,
  confermato e corretto: `GeofenceEventWorker.kt` non passava mai
  l'orario della transizione a `triggerEvent()` — il default del
  client (`System.currentTimeMillis()` al momento della CHIAMATA)
  datava l'evento all'istante in cui un eventuale RETRY andava a buon
  fine, non al passaggio di confine reale. Questo significa che anche
  i tre eventi delle 13:23-13:49 di oggi potrebbero non essere avvenuti
  esattamente a quell'ora (potrebbero essere ritentativi riusciti di
  transizioni piu' vecchie) — motivo in piu' per il fix, che rende
  affidabile la diagnosi la prossima volta. Fix: `GeofenceBroadcastReceiver.kt`
  cattura `System.currentTimeMillis()` al momento del rilevamento (non
  ha un timestamp piu' preciso a disposizione da `GeofencingEvent`) e
  lo passa al worker tramite un nuovo campo `KEY_TIMESTAMP` nel
  `Data` di WorkManager; il worker lo inoltra a `triggerEvent()` invece
  di lasciare il default. watch-app portato a v0.11.0.

  Attribuzione: NON taggato "qwen3.8-27B-UD-IQ4_XS" nei commenti come i
  fix di v0.68.0 — a differenza di quelli, questo bug non viene dal
  documento di review, e' stato trovato in questa sessione
  diagnosticando dal vivo la segnalazione dell'utente. Una prima
  stesura di questi commenti aveva quel tag per abitudine (la
  convenzione chiesta dall'utente per il filone "qwen_plan.md" era
  ancora fresca), corretta subito dopo: un tag che dice "individuato da
  X" quando non e' vero e' esattamente il tipo di imprecisione che la
  pulizia dei riferimenti qwen di questa stessa sessione (v0.67.0) era
  nata per evitare.
- 2026-09-23: L'utente ha chiesto quale versione della phone-app fosse
  effettivamente in produzione, non essendone certo. Verificando
  `phone-app/app/build.gradle.kts` e' emerso che il commit della Fase 1
  (isolamento famiglie, 22/09, v0.68.0) aveva aggiunto codice reale alla
  phone-app (nuova sezione "Genitori" in `SettingsScreen.kt`, invito/
  accettazione famiglia, relative modifiche a `AppViewModel.kt`/
  `BackendClient.kt`/`DeviceRepository.kt`) SENZA incrementare
  versionCode/versionName — rimasti fermi a 13/"0.13.0" come prima di
  quella modifica. Un rebuild della Fase 1 sarebbe stato indistinguibile
  da uno precedente, stesso identico rischio di diagnosi gia' risolto
  per watch-app in v0.5.0/versionCode ("utente non riusciva a verificare
  se l'APK appena compilato fosse davvero quello installato"). Corretto
  portando phone-app a versionCode 14/"0.14.0" (v0.70.0). Nota: questa
  sessione non ha visibilita' su quale APK sia davvero installato sul
  telefono dell'utente — puo' solo garantire che il numero di versione
  nel repo rifletta correttamente il codice sorgente attuale.
- 2026-09-23: Implementata la Fase 2 di qwen_plan.md (individuato da
  qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5): topic FCM
  per-bambino ("child-<childId>", nuovo backend/api/_lib/fcmTopics.js)
  al posto del topic globale "parents" a cui si iscriveva ogni
  phone-app ancor prima del login — SOS/geofence/chat/batteria di un
  bambino arrivavano a QUALSIASI telefono di QUALSIASI famiglia sullo
  stesso progetto Firebase, allarme sonoro incluso. trigger-event.js/
  send-message.js/_lib/batteryAlerts.js mandano ora sul topic del
  bambino coinvolto; AppViewModel.kt tiene le iscrizioni allineate a
  "children" con un collector indipendente dalla UI (le iscrizioni FCM
  devono restare valide anche ad app in background); TrackerApplication.kt
  non si iscrive piu' al topic globale, anzi se ne disiscrive una
  tantum per migrare le installazioni esistenti. Aggiunto anche il
  "minimo sindacale" del piano: FcmService.onMessageReceived() scarta
  un messaggio il cui childId non e' tra i propri figli (nuova
  KnownChildrenCache, SharedPreferences), seconda barriera oltre al
  topic. signOut() disiscrive tutto e pulisce la cache, per non
  lasciare un secondo genitore che fa login sullo stesso telefono
  iscritto ai topic della famiglia precedente.

  Lavorando su questo e' emerso un bug bloccante nella Fase 1, NON dal
  documento di review: DeviceRepository.observeChildren()/
  observeGeofences() interrogavano le collezioni "devices"/"geofences"
  senza alcun where(), ma le regole v0.7.0 richiedono
  myFamilyId()==resource.data.familyId per ogni documento — una query
  Firestore non provabilmente vincolata da quella stessa condizione
  viene rifiutata IN BLOCCO (non filtrata documento per documento).
  Invisibile finche' l'utente non pubblica firestore.rules v0.7.0
  (passaggio manuale ancora in sospeso), ma una volta pubblicate la
  lista bambini/zone si sarebbe svuotata per chiunque. Corretto
  aggiungendo whereEqualTo("familyId", ...) a entrambe le query
  (DeviceRepository.kt v0.8.0), col familyId preso da
  AppViewModel.ownFamilyId. phone-app portata a v0.15.0 (v0.71.0).
- 2026-09-23: Implementata la Fase 3 di qwen_plan.md (individuato da
  qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5): due interventi.
  (1) Race condition reale nell'upload posizioni del watch —
  LocationUploadWorker gira sotto due nomi di lavoro WorkManager
  distinti (periodico + one-shot), che possono eseguire in parallelo;
  il vecchio peekBatch() (non distruttivo) + removeOldest() separato
  lasciava una finestra in cui due esecuzioni concorrenti potevano
  uploadare lo stesso batch e la seconda rimuovere punti piu' recenti
  mai uploadati. Trovato qui, non dal documento di review: ogni
  chiamante crea una propria istanza di PendingLocationStore, e
  @Synchronized in Kotlin sincronizza sull'istanza — il vecchio lock
  non proteggeva affatto le chiamate tra istanze diverse. Sostituiti
  peekBatch()/removeOldest() con claimBatch() (atomico: legge e
  rimuove insieme) + requeue() (rimette in coda se l'upload fallisce),
  sincronizzati su un lock di companion object condiviso da tutte le
  istanze.
  (2) La quota giornaliera (_lib/quota.js) contava "1" per ogni
  chiamata, ma ingest-location.js scrive fino a 100 documenti in una
  sola chiamata: sottostimava di molto le scritture Firestore reali
  con piu' bambini attivi. Aggiunto un parametro "weight" (default 1)
  a checkAndConsumeQuota; ingest-location.js lo valorizza col numero
  di punti del batch. Aggiunti test.
  Nota: il punto 6 del piano (maxDuration di cleanup.js) resta in
  Fase 4 come gia' concordato, non toccato in questo giro.
  watch-app portata a v0.12.0 (v0.72.0).
- 2026-09-23: Implementata la Fase 4 di qwen_plan.md (individuato da
  qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — sei fix minori di
  robustezza dalla tabella "🟡 Minori":
  (6) vercel.json: maxDuration:60 specifico per api/cleanup.js (era
  30 come tutte le altre funzioni, rischiava di far morire il cron su
  uno storico grande).
  (7) ingest-location.js: range-check lat/lon (isValidPoint) + peso
  quota e "received" ora sul conteggio dei punti VALIDI, non quello
  grezzo del body; un batch senza punti validi e' ora 400.
  (8) ha-status.js: get() del device spostato PRIMA della guardia di
  quota — un childId inesistente non la consuma piu' per poi
  rispondere comunque 404.
  (9) phone-app FcmService.postNotification(): id notifica da
  System.currentTimeMillis().toInt() (collisioni nello stesso ms) a
  contatore atomico. Aggravante trovata qui, non dal piano: il
  PendingIntent condivideva un requestCode fisso — col nuovo id
  univoco, due notifiche coesistenti avrebbero aperto la destinazione
  sbagliata; corretto usando lo stesso id come requestCode.
  (10) watch-app BackendClient.kt: OkHttpClient spostato da proprieta'
  di istanza (nuovo pool ad ogni worker) a companion object condiviso.
  (11) _lib/errors.js: validateConfig() spostato dentro wrapHandler
  (girava solo su 3 endpoint su 9 con lo stesso blocco duplicato).
  Verificato senza modifiche: (12) SosWorker.kt e' gia' lavoro espedito
  (setExpedited), il retry aggressivo del piano per l'SOS era gia' in
  vigore; (13) GeofenceSyncWorker.kt resta una scelta deliberata gia'
  documentata (remove+re-add ad ogni sync), non un bug.
  watch-app a v0.13.0, phone-app a v0.16.0 (v0.73.0).
- 2026-09-23: Implementata la Fase 5 di qwen_plan.md, ultima del piano
  (individuato da qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5):
  nuovo `.github/workflows/backend-test.yml` — i test del backend
  esistevano ma nessun job CI li eseguiva (solo il cron di pulizia gira
  automaticamente, e chiama l'endpoint gia' deployato, non i test).
  Gira `npm ci && npm test` su ogni push/PR che tocca `backend/`.
  Aggiunta una sezione "Test" in `backend/README.md`.

  Verificate (nessuna modifica necessaria) le due segnalazioni di
  igiene del piano: il placeholder/entry vuota `[0.33.1]` descritti per
  `CHANGELOG.md` non esistono piu' nella copia attuale (verificato con
  grep mirato, probabilmente gia' risolti in un giro precedente);
  `IMPROVEMENT_PLAN.md` non esiste piu' nel repository. Entrambe
  segnalazioni non piu' attuali dello stesso tipo gia' visto in Fase 1
  (SOS retry, GeofenceSyncWorker) — il documento di review descrive lo
  stato del repo al momento della sua stesura, non quello attuale.

  Lasciata aperta (segnalata, non eseguita) la terza voce di igiene:
  `CONTEXT.md` e' cresciuto a ~137 KB (~108 KB alla stesura del piano).
  Spezzarlo per componente (backend/watch/phone) e' una decisione
  strutturale — la voce del piano stesso la definisce "da valutare",
  non un'azione da eseguire d'iniziativa; lasciata all'utente.

  **Con questa voce il piano di qwen_plan.md (5 fasi, tutte le
  priorita' 1-6 elencate nel documento) e' completato.**
- 2026-09-23: Configurato l'accesso autonomo di Claude a Firebase per i
  due passi manuali rimasti aperti dopo la Fase 5 (migrazione
  `familyId` + pubblicazione `firestore.rules` v0.7.0). Nessuna
  modifica di codice. Verificato che la sessione cloud non aveva ne'
  credenziali (`FIREBASE_SERVICE_ACCOUNT_B64` assente, nessuna env var
  Firebase/GCP) ne' CLI (`firebase`/`gcloud` non installate) — motivo
  per cui in precedenza questi due passi erano rimasti manuali.
  L'utente ha generato una nuova chiave per una service account
  dedicata gia' esistente nel progetto e impostato il contenuto in
  base64 come variabile d'ambiente `FIREBASE_SERVICE_ACCOUNT_B64` a
  livello di **environment** Claude Code (Settings dell'environment,
  non nel repository — letta automaticamente da ogni sessione futura;
  dettagli della credenziale intenzionalmente non riportati qui, per
  non lasciarli nella cronologia del repository). La chiave non e' mai
  stata scritta su disco ne' processata da Claude in questa sessione:
  il classificatore di sicurezza ha bloccato correttamente il
  tentativo di leggerla/codificarla via Bash; la codifica base64 e'
  stata fatta dall'utente in locale. Impostata a sessione gia'
  avviata: non visibile nell'ambiente corrente (le env var si caricano
  solo all'avvio), serve una sessione nuova perche' venga letta. Da
  una sessione che la vede gia' in ambiente, i due passi restano da
  eseguire nell'ordine documentato (migrazione prima, altrimenti
  finestra di lockout per la famiglia attuale una volta pubblicate le
  nuove regole); il deploy delle regole richiede in aggiunta
  l'installazione on-demand di `firebase-tools` (assente in questa
  sessione), autenticabile in modo non interattivo via
  `GOOGLE_APPLICATION_CREDENTIALS` derivato dalla stessa variabile
  (v0.75.0).
- 2026-09-23: **Eseguiti i due passaggi manuali rimasti in sospeso da
  v0.68.0** per chiudere l'isolamento famiglie (Fase 1 di qwen_plan.md):
  script one-time `backend/scripts/migrate-family-ids.js` (credenziali
  service account disponibili in questo ambiente via
  `FIREBASE_SERVICE_ACCOUNT_B64`) — assegnato il `familyId`
  `5bebbb52-eff2-4897-8ee5-718591e0fa30` a tutti i documenti legacy
  della famiglia esistente (2/2 `parents`, 1/1 `devices`, 4/4
  `geofences`, tutti privi del campo) — poi `firestore.rules` v0.7.0
  pubblicato sul progetto reale (`child-tracker-7a1f1`) via
  `firebase deploy --only firestore:rules --non-interactive`, usando
  la stessa service account come Application Default Credentials
  (`GOOGLE_APPLICATION_CREDENTIALS` puntata a un file temporaneo nello
  scratchpad, cancellato subito dopo il deploy). Deploy confermato
  riuscito dall'output della CLI ("released rules firestore.rules to
  cloud.firestore"). Con questo si chiude la finestra di rischio
  descritta in v0.68.0/v0.69.0 (regole attive che richiedono
  `familyId` senza che i documenti esistenti lo avessero ancora) e il
  bug collaterale di v0.71.0 (query `devices`/`geofences` senza
  `where("familyId", ...)`, che sarebbe stata rifiutata in blocco da
  Firestore non appena le regole fossero state effettivamente attive)
  e' ora effettivamente risolto anche lato server, non solo nel codice
  client. **Da confermare dall'utente al prossimo avvio reale della
  phone-app**: bambini/zone devono restare visibili come prima (nessun
  comportamento nuovo atteso, solo la rimozione della finestra di
  rischio silenziosa). Eseguito con l'accesso configurato nella voce
  precedente, da una sessione parallela partita dallo stesso commit
  (entrambe avevano numerato v0.75.0: rinumerata questa a v0.76.0 in
  fase di merge) (v0.76.0).
- 2026-09-23: Verifica richiesta dall'utente: qwen_plan.md risulta
  implementato per intero (punti 1-13, CI, igiene). Controllato il tag
  di attribuzione alla AI locale ("individuato da qwen3.8-27B-UD-IQ4_XS,
  implementato da Sonnet 5") file per file sui commit delle Fasi 1-5:
  aggiunto dove mancava (TrackerApplication.kt, MainActivity.kt della
  phone-app, backend/test/quota.test.js). Segnalato un rischio non
  verificabile da qui: `vercel.json` di Fase 4 ha di nuovo un pattern
  specifico `api/cleanup.js` accanto a `api/*.js`, la stessa forma che
  in v0.12.0 aveva rotto il build Vercel — da confermare sulla
  dashboard Vercel (v0.77.0).
- 2026-09-23: Confermato dall'utente che il deploy Vercel e' fallito
  dopo la Fase 4, come sospettato in v0.77.0. Rimossa la voce
  `api/cleanup.js` da `backend/vercel.json` (tornato alla forma di
  v0.12.0, un solo pattern `api/*.js`). **Lezione ripetuta, da non
  dimenticare**: in questo progetto `vercel.json` non tollera un
  pattern specifico accanto al wildcard che gia' copre lo stesso file.
  Il punto 6 di qwen_plan.md (timeout cleanup) torna aperto; eventuale
  soluzione futura senza toccare `vercel.json`: ridurre il lavoro per
  singola esecuzione in `cleanup.js` (v0.78.0).
- 2026-09-23: Confermato dall'utente: dopo la rimozione della voce
  `api/cleanup.js` il deploy Vercel e' tornato a buon fine. Branch
  `claude/hopeful-cori-bqpuvm` cancellato dall'utente (era allo stesso
  commit): da qui il branch di lavoro unico e' di nuovo
  `claude/child-geolocation-smartwatch-dblfrv` (v0.78.1).
- 2026-09-23: **Test su telefono reale riuscito** (confermato
  dall'utente): con regole v0.7.0 attive e documenti migrati, la
  phone-app mostra bambini/zone e tutte le modifiche di qwen_plan.md
  funzionano. Isolamento famiglie chiuso anche sul campo. Restano
  aperti: timeout di `cleanup.js` con storico molto grande (punto 6 del
  piano, riaperto in v0.78.0) e l'eventuale suddivisione di CONTEXT.md
  (decisione dell'utente) (v0.78.2).
- 2026-09-23: L'utente ha scelto di **mantenere CONTEXT.md intero** (non
  spezzarlo per componente): voce di igiene di qwen_plan.md chiusa come
  "non si fa". Chiuso anche il punto 6 (timeout di `cleanup.js`) senza
  toccare `vercel.json`: budget di 20s per esecuzione + campo `more`
  nella risposta, e il workflow GitHub richiama l'endpoint finche'
  `more` e' true (max 10 volte/notte). Scelta rispetto ad alternative:
  ridurre solo `MAX_BATCHES_PER_RUN` avrebbe rallentato lo smaltimento
  anche quando c'e' tempo; un budget di tempo si adatta alla velocita'
  reale di Firestore. **Da confermare** al primo giro notturno (o
  avvio manuale dalla tab Actions): risposta con `"more": false` e
  workflow verde. Con questa voce **qwen_plan.md non ha piu' punti
  aperti** (v0.79.0).
- 2026-09-23: Segnalato dall'utente: sul watch "Invia posizione" era
  sostituito da "Posizione non disponibile, segnale GPS assente". Non
  causato dalle modifiche di qwen_plan.md (LocationRequestWorker/
  GpsAvailability/MainActivity non toccati). Difetto di disegno della
  v0.48.0: il pulsante disabilitato dopo un fix fallito tornava attivo
  solo con un fix del tracking automatico, quindi poteva restare
  bloccato a lungo; reso piu' frequente dalla richiesta automatica di
  posizione all'apertura della phone-app (v0.66.0). **Decisione**: il
  pulsante non si disabilita piu', l'avviso GPS resta solo come testo
  ("GPS assente, tocca per riprovare"). Cambia la scelta fatta in
  v0.48.0 su richiesta dell'utente (pulsante disabilitato); lo scopo
  di allora (rendere visibile l'assenza di segnale) resta rispettato.
  watch-app v0.14.0, da ricompilare e testare sul watch (v0.80.0).
- 2026-09-23: Confermato dall'utente su watch reale: v0.14.0 installata,
  "Invia posizione" resta premibile con GPS assente. Richiesta nuova in
  valutazione: mostrare dettagli della ricerca GPS (barre dei satelliti
  "stile TomTom"), non ancora avviata (v0.80.1).
- 2026-09-23: Implementata la schermata "Ricerca GPS" richiesta
  dall'utente (barre dei satelliti stile TomTom), watch-app v0.15.0,
  nuovo `ui/GpsSearchScreen.kt`. Scelte: (1) si apre solo dal pulsante
  "Invia posizione" quando il GPS risulta assente, non e' una voce di
  menu fissa; (2) GPS acceso solo a schermata aperta e al massimo 3
  minuti — `GnssStatus` emette dati solo con il GPS attivo, quindi la
  schermata deve accenderlo da se' (`LocationManager.GPS_PROVIDER`,
  non il fused provider usato altrove); (3) al primo fix riusa
  l'invio gia' esistente (`sendLocationNow()`/LocationRequestWorker)
  invece di un secondo percorso di invio; (4) barre disegnate con
  Box/Row a larghezza fissa, niente `Modifier.weight` (lezione del
  progetto). **Non compilato qui**: primo build e prova su Watch4 da
  fare (v0.81.0).
- 2026-09-23: Primo test della Ricerca GPS su Watch4 reale (build ok):
  le prime due pressioni di "Invia posizione" hanno mostrato "invio in
  corso" senza arrivare al backend (LocationRequestWorker: fused
  getCurrentLocation null, poi retry silenzioso — comportamento
  previsto), alla terza il pulsante e' passato a "GPS assente" e la
  schermata ha mostrato le barre. **Dato chiave: 25 satelliti visti,
  14 usati, ma nessun fix arrivato all'app.** Quindi il problema aperto
  dal 18/9 ("fix GPS non disponibile") NON e' di ricezione satellitare:
  il chip calcola la posizione ma questa non raggiunge l'app, ne' via
  fused provider ne' via LocationManager/GPS_PROVIDER. Ipotesi da
  verificare con la diagnostica: posizione di sistema o provider GPS
  disattivati/limitati dalle impostazioni Samsung, restrizioni di
  risparmio batteria sull'app, errore nella registrazione del listener.
  Aggiunti in GpsSearchScreen.kt v0.2.0 diagnostica a schermo e
  recupero da getLastKnownLocation (watch-app v0.16.0).
  Domanda utente sulle effemeridi (A-GPS) per accelerare il fix:
  valutata ma non implementata ora — Android le scarica gia' in
  automatico via rete, e con 14 satelliti usati il fix c'e' gia': non
  e' la causa di questo problema. Riconsiderare solo se, risolta la
  consegna, il primo fix risultasse lento (v0.82.0).
- 2026-09-23: Secondo test Ricerca GPS (v0.16.0) su Watch4: posizione
  di sistema ON, provider GPS ON, registrazione app OK, 10 satelliti
  usati, 0 fix consegnati all'app. Esclusa una regressione nel codice
  dei permessi (git: `requestPermissionsAndStart` invariato dalla
  v0.31.0). Ipotesi principali, in ordine: (1) permesso ridotto a
  livello AppOps (concesso "sulla carta" ma posizione ignorata);
  (2) app di posizione fittizia attiva nelle Opzioni sviluppatore
  (attive sul watch per l'ADB via WiFi): sostituisce il provider GPS
  vero, i satelliti si vedono ma le posizioni reali non arrivano.
  Aggiunte le righe di diagnostica corrispondenti (watch-app v0.17.0).
  Riga "ultima posizione GPS di sistema" del test non ancora riportata
  dall'utente (v0.83.0).
- 2026-09-23: **Individuata la causa del "fix GPS non disponibile"
  aperto dal 18/9.** Diagnostica v0.17.0 sul Watch4: posizione precisa
  ON, background ON, AppOps "consentito", provider passive/network/
  fused/gps, nessuna posizione fittizia — ma "ultima posizione GPS di
  sistema: nessuna" con 10 satelliti usati. Aprendo Google Maps il fix
  e' arrivato istantaneamente e la nostra app ha inviato subito la
  posizione (il worker stava ritentando). Conclusione: non un problema
  di permessi ne' di ricezione, ma di come chiediamo la posizione — il
  GPS del Watch4 non chiude il fix senza i dati di aiuto (ora,
  effemeridi) che Maps fornisce via servizi Google, e la nostra
  getCurrentLocation si arrendeva prima. Fix (watch-app v0.18.0):
  `GpsAssist` inietta ora/effemeridi (sendExtraCommand, comandi di
  sistema standard) prima di ogni ricerca, durata esplicita 90s in
  LocationRequestWorker/SosWorker, Ricerca GPS anche via fused
  provider. Rivista quindi la valutazione di v0.82.0 sulle effemeridi:
  la domanda dell'utente era pertinente. Su richiesta utente il
  pulsante ora mostra "Invio posizione in corso…" durante il tentativo
  (`GpsAvailability.sending`). **Da verificare**: fix senza aprire Maps
  (v0.84.0).
- 2026-09-23: L'utente segnala che il 18-19/9 fix GPS e notifiche zone
  funzionavano: sospetta una regressione recente. Verifica fatta:
  (1) **Notifiche zone: regressione reale**, lato phone-app/backend, non
  watch. Dalla Fase 2 (installata il 23/9 pomeriggio) la phone-app si
  iscrive ai topic FCM `child-<id>` solo per i figli trovati con
  `whereEqualTo("familyId", ...)` e scarta i messaggi di figli non in
  `KnownChildrenCache`; in piu' si disiscrive dal vecchio topic
  "parents". Fino alla migrazione `familyId` (sera del 23/9) nessun
  documento aveva `familyId` → lista figli vuota → nessuna iscrizione →
  notifiche di zone/SOS/chat perse in quella finestra. Errore
  d'ordine: Fase 2 installata prima della migrazione. Dopo la
  migrazione dovrebbe essere risolto: **da verificare** con un
  ingresso/uscita zona reale.
  (2) **GPS del watch**: nessuna modifica dopo il 19/9 tocca la
  richiesta di posizione o la registrazione delle zone sul watch
  (diff 1033976..e9b8d7a~1: solo buffer punti Fase 3, client HTTP
  condiviso Fase 4, orario eventi zona, pulsante). Il blocco osservato
  ("ultima posizione GPS di sistema: nessuna" finche' non si apre Maps)
  e' a livello di sistema; ipotesi non verificata: riavvio/aggiornamento
  recente del watch che ha azzerato i dati di aiuto del GPS. Il fix
  v0.18.0 (GpsAssist) copre comunque il caso.
  Tentata una lettura da Firestore delle date delle ultime posizioni/
  eventi per datare l'inizio del problema: bloccata dal controllo di
  sicurezza della sessione (dati di produzione), non aggirata — da
  decidere con l'utente (v0.84.1).
- 2026-09-23: L'utente ha autorizzato in modo permanente la lettura da
  Firebase. Creato `backend/scripts/diag-device-history.js` (sola
  lettura, niente coordinate) + regola in `.claude/settings.json` +
  nota in CLAUDE.md. Risultato: posizioni automatiche 18/9 181, 19/9
  278, 20/9 94, 21/9 0, 22/9 26, 23/9 16; eventi zona regolari fino a
  oggi (ultimi geofence_enter 23/9 19:50-20:50Z), nessun evento il
  21/9. Il calo del tracking automatico precede ogni modifica al
  codice del watch (identico dal 19/9 21:15 al 23/9) → causa esterna al
  codice (watch spento/non indossato il 21, poi GPS di sistema senza
  dati di aiuto come visto nei test di oggi; da chiedere all'utente se
  il watch si e' riavviato/aggiornato o e' stato in risparmio
  energetico in quei giorni). Le notifiche zona mancanti sul telefono
  restano spiegate dalla finestra Fase 2 senza `familyId` (v0.85.0).
- 2026-09-23: L'utente conferma: watch scaricato del tutto il 20/9 (il
  21 non ricorda). Nuovo test: nemmeno Maps ottiene la posizione, 23
  satelliti visti/12 usati, "ultima posizione GPS di sistema" ferma con
  secondi in aumento. Storico (script diagnostico v0.2.0, ora anche con
  la precisione): ultima posizione 20:50:30Z con 8 m di precisione =
  vero fix GPS, poi piu' nulla. **Ipotesi principale**: dopo la scarica
  completa i dati di aiuto salvati nel chip GPS (ora/effemeridi/
  almanacco) sono rimasti sbagliati — il chip aggancia i satelliti ma
  non chiude il fix, se non occasionalmente. Coerente con la data
  d'inizio del calo (20/9) e con il codice del watch invariato.
  Azioni: chiesto all'utente di verificare Data e ora automatiche sul
  watch; aggiunto pulsante "Reset dati GPS" (delete_aiding_data +
  reiniezione, ricerca fino a 10 min) — watch-app v0.19.0. Se neanche
  il reset funziona: riavvio completo del watch, poi valutare un
  problema hardware/firmware (fuori dal controllo dell'app) (v0.86.0).
- 2026-09-23: L'utente riapre Maps sul watch: posizione esatta, icona
  GPS fissa nella tendina, ma nulla di nuovo arriva al backend → "il
  problema e' il nostro codice". Verificato con lo script (ora in ora
  italiana: l'utente leggeva 20:50 UTC per le 22:50 locali — stesso
  invio): la posizione delle 22:50 (8 m) compare DUE volte, la seconda
  dal servizio di tracking → servizio vivo, riceve le posizioni
  prodotte da altri (Maps) ma non ne ottiene di proprie. Precisione
  mediana ~21 m in tutti i giorni (anche 18-19): non e' cambiato il
  tipo di fonte, e' crollata la quantita'. **Decisione**: il limite e'
  davvero nel nostro codice — da fermo il servizio chiedeva
  PRIORITY_BALANCED_POWER_ACCURACY, che dipende da Wi-Fi/celle/telefono
  associato e puo' non accendere mai il GPS; dopo la scarica del 20/9
  quelle fonti non rispondono. Passato a PRIORITY_HIGH_ACCURACY anche
  da fermo (intervallo invariato 10'), piu' iniezione GpsAssist.
  Scartata l'alternativa "watchdog con Handler che ripiega sul GPS
  dopo 12' senza punti": con il watch in sospensione profonda un
  Handler puo' non scattare, mentre la richiesta al fused provider
  sveglia il dispositivo da se'. Contropartita accettata: piu' batteria
  da fermo, da rivalutare con l'uso reale. Aggiunti anche log e
  TrackingStatus (servizio prima completamente muto). Ipotesi da
  confermare con l'utente: con quale telefono e' associato il watch e
  se dopo il 20/9 e' ancora connesso via Bluetooth (watch-app v0.20.0)
  (v0.87.0).
- 2026-09-23: L'utente conferma che il watch ha **sempre** funzionato
  senza telefono collegato in Bluetooth (uso standalone LTE): ipotesi
  "fonte = telefono associato" scartata. Con priorita' bilanciata, su un
  watch standalone le posizioni (~21 m) venivano quindi da Wi-Fi/celle
  (posizione di rete Google) o da GPS acceso a discrezione del sistema;
  dopo la scarica/riavvio del 20/9 quella strada non produce piu'
  punti, per motivi non osservabili dall'app. La correzione v0.20.0
  (alta precisione anche da fermo) non dipende da quale fonte sia
  sparita, quindi resta valida. Nessun nuovo punto al backend dopo le
  22:50 (v0.20.0 non ancora installata) (v0.87.1).
- 2026-09-23: **Causa trovata.** Dopo un fix GPS occasionale (23:24,
  11 m) e nuovi "GPS assente" con 16 satelliti usati, l'utente (in
  casa) verifica le impostazioni del watch: Wi-Fi acceso (serve per il
  debug via WiFi), **"Migliora precisione" SPENTA**. E' la posizione di
  rete Google (Wi-Fi/celle): spenta, Android disattiva il provider
  "network" e in casa resta solo il GPS, che al chiuso chiude il fix
  di rado. Spiega tutto: crollo dei punti automatici dal 20/9 (il
  tracking a priorita' bilanciata si appoggiava al Wi-Fi), "GPS
  assente" alternato a fix occasionali, Maps che "ce la fa" (GPS acceso
  di continuo), precisione ~20 m del 18-19/9 (Wi-Fi). Probabile
  spegnimento con la scarica/riavvio del 20/9. Nessuna regressione nel
  codice. Aggiunta la riga diagnostica corrispondente (watch-app
  v0.21.0) e la sezione "Impostazioni obbligatorie" in
  watch-app/README.md. **Da decidere con l'utente**: se riportare il
  tracking da fermo a priorita' bilanciata (v0.20.0 l'aveva alzata ad
  alta prima di conoscere la causa: piu' robusto, piu' batteria). Da
  riconsiderare anche le aggiunte fatte durante la diagnosi (Ricerca
  GPS, GpsAssist, reset): utili, ma nate per un'ipotesi poi rivelatasi
  non la causa (v0.88.0).
- 2026-09-23: L'utente riferisce che nei tragitti casa-scuola all'aperto
  di ieri e oggi il watch non avrebbe mai fatto un fix. Lo storico
  (script v0.5.0, nuova opzione --all) dice altro: ritorno 22/9
  13:19-13:50 = 22 punti "moving" ~1/min (7-42 m); ritorno 23/9
  13:23-13:49 = 12 punti "moving" (12-37 m) → all'aperto in movimento il
  GPS funziona (modalita' moving = HIGH_ACCURACY ogni minuto). Andata
  22/9 07:20-07:38: solo 4 punti "still" (activity recognition non ha
  rilevato il movimento → priorita' bilanciata, con "Migliora precisione"
  spenta quasi nulla). Andata 23/9: NESSUN dato ne' evento (neanche
  l'uscita dalla zona casa) → watch non trasmetteva (spento/scarico/
  senza dati/servizio fermo dopo una reinstallazione?). Chiesto
  all'utente se le posizioni del ritorno sono visibili sulla phone-app
  (se no, problema di visualizzazione lato telefono) e com'era il watch
  la mattina del 23 (v0.88.1).
- 2026-09-23: L'utente autorizza la lettura diretta delle posizioni.
  Aggiunte allo script `--track` e `--hourly` (coordinate usate solo per
  distanze, mai stampate). **Correzione della voce precedente**: i punti
  "moving" del 22-23/9 alle 13 sono fermi a ~2 km da casa (scuola?),
  non il tragitto; l'utente aveva ragione. Riepilogo orario: tracking
  continuo fino al 20/9 alle 19 (10/ora da fermo anche di notte con
  scarto 3-5 m = Wi-Fi), poi mai piu' ripreso, solo raffiche isolate;
  22/9 pomeriggio → 23/9 13:00 nessun dato ne' evento zona. Due
  ipotesi, entrambe compatibili: "Migliora precisione" spenta (blocca
  punti da fermo e geofence, che richiedono la posizione di rete: gli
  ultimi geofence_enter arrivano solo subito dopo un fix GPS) e/o
  servizio di tracking che dopo il riavvio del 20/9 non resta attivo.
  Verifica: diagnostica v0.21.0 ("Tracking automatico", "Punti
  tracking", "Errore tracking") + `--hourly` il giorno dopo con
  "Migliora precisione" riattivata (v0.89.0).
- 2026-09-23: Due segnalazioni utente sulla phone-app. (1) "Adesso"
  fisso nella StatusCard: aggiunto un orologio a 30s (produceState) e
  il parametro nowMillis a formatRelativeTime. (2) Senza fix il watch
  non mandava nemmeno la batteria. Decisione: riusare trigger-event.js
  con un nuovo type "status" invece di un nuovo endpoint (Vercel Hobby
  e' limitato a 12 funzioni, gia' 11 in uso), scrivendo un campo
  separato `lastStatusAt` e NON `lastSeen`/`lastLocation`, che restano
  "ultima posizione" (anche la guardia di freschezza di v0.65.0 si
  basa su lastSeen). Il watch lo manda solo quando LocationRequestWorker
  fallisce il fix (invio manuale o "Aggiorna posizione" del genitore);
  non ancora dal tracking periodico. Avvisi di batteria scarica non
  collegati allo "status" (backend v0.19.0, watch-app v0.22.0,
  phone-app v0.17.0) (v0.90.0).
- 2026-09-23: Tre richieste utente. (1) Satelliti visti/agganciati
  sulla phone-app "se non consuma": si', GnssStatus ascoltato solo
  durante il tentativo di LocationRequestWorker (il GPS e' gia' acceso),
  valore massimo del tentativo, salvato in devices/{id}.gnss fuori dalla
  guardia di freschezza (descrive l'ultimo tentativo, riuscito o no).
  Non inviato dal tracking periodico. (2) Verde fluo della batteria
  illeggibile → #2E7D32. (3) Pulsante del watch a stati colorati: barra
  di progresso disegnata a mano (Box + fillMaxWidth(frazione), niente
  weight ne' progress indicator di Wear mai provati), verde 4s su
  invio riuscito, rosso fisso con GPS assente. Backend
  trigger-event v0.20.0, watch-app v0.24.0, phone-app v0.18.0 (v0.91.0).
- 2026-09-23: Test utente: fix phone ok tranne i satelliti; barra
  del watch ok. Richieste: avvisi batteria anche dallo "status", e
  batteria a ogni aggiornamento di posizione. Indagando (script v0.8.0,
  ora stampa anche lo stato del device senza coordinate): gnss e
  lastStatusAt assenti perche' la posizione delle 23:43 e' arrivata dal
  Wi-Fi senza accendere il GPS ("Migliora precisione" riattivata: punti
  ogni 5' in casa con 11-16 m). Trovato un bug vero: gli eventi
  geofence arrivavano con battery=null e trigger-event.js azzerava
  batteria/temperatura/carica sul device (e con lat/lon 0,0 spostava
  l'ultima posizione). Correzioni: aggiornamento solo dei campi
  presenti, coordinate 0,0 ignorate per lastLocation/lastSeen, batteria
  inviata anche dagli eventi zona, gnssActive=false mostrato come "GPS
  non usato", checkBatteryAlerts anche sullo status (backend
  trigger-event v0.21.0, watch-app v0.25.0, phone-app v0.19.0)
  (v0.92.0).
- 2026-09-23: Confermato dall'utente e sul backend: all'apertura della
  phone-app posizione richiesta e ricevuta, "GPS non usato" (posizione
  da Wi-Fi), batteria/temperatura presenti. Script diagnostico 0.8.1
  (stampa "GPS non usato" invece di "null visti"). Ancora da vedere: il
  numero di satelliti con un fix davvero GPS (all'aperto) e la batteria
  che resta dopo un evento zona (v0.92.1).
- 2026-09-23: Domanda utente: l'app sul watch si avvia da sola? Verifica:
  si' all'accensione (BootReceiver su BOOT_COMPLETED rilancia servizio
  e sync zone; i lavori periodici WorkManager sopravvivono da soli), no
  dopo un aggiornamento dell'app: il processo viene fermato e il
  servizio restava giu' fino all'apertura manuale. Con le molte
  reinstallazioni da Android Studio dei giorni scorsi puo' aver
  contribuito ai buchi nello storico (la causa principale resta
  "Migliora precisione" spenta). Aggiunto MY_PACKAGE_REPLACED al
  BootReceiver, try/catch sull'avvio, TrackingStatus.startedBy in
  diagnostica (watch-app v0.26.0) (v0.93.0).
- 2026-09-23: Richiesta utente: la richiesta di posizione dal telefono
  deve funzionare con l'app del watch chiusa. Verificato: push gia' ad
  alta priorita' (parent-command sendPushSafe, android.priority high),
  FcmService riceve i data message anche ad app chiusa. Punto debole:
  LocationRequestWorker accodato come lavoro normale → con il watch in
  Doze partenza ritardata. Ora espedito (RUN_AS_NON_EXPEDITED se la
  quota espedita e' finita) e riavvio del servizio di tracking se non
  attivo (consentito da push ad alta priorita'). Limite noto: un'app in
  "Arresto forzato" non riceve push finche' non viene riaperta
  (watch-app v0.27.0) (v0.94.0).
- 2026-09-23: Confermato dall'utente e sul backend: con l'app del watch
  chiusa la richiesta dal telefono ha rilanciato il tracker in
  background e la posizione e' arrivata (23:58, 14-15 m). Nuova
  richiesta: sul telefono, se la posizione non arriva, barra traslucida
  "nuovo tentativo tra N s" sotto la riga satelliti e richieste
  ripetute. Scelte: esito ricavato da devices/{id} (lastSeen = successo;
  lastStatusAt senza lastSeen o timeout 120 s = fallimento), 60 s di
  attesa fra i tentativi, massimo 20 (~1 ora), margine di 10 s per le
  differenze fra gli orologi; logica in AppViewModel (retryStates) per
  coprire anche la richiesta automatica all'apertura; barra disegnata a
  mano come quella del watch (phone-app v0.20.0) (v0.95.0).
- 2026-09-23: Test utente con watch in modalita' aereo: nessuna barra
  sul telefono. Causa: i primi 2 minuti (RESPONSE_TIMEOUT_MS) di attesa
  della risposta non avevano nessuno stato visibile. Aggiunto
  LocationRetry.waitingForWatch: barra "In attesa della posizione dal
  watch… N s" dall'invio della richiesta, poi barra del nuovo tentativo
  (phone-app v0.21.0) (v0.96.0).
- 2026-09-23: Richiesta utente: avviso da watch in modalita' aereo o
  spento, con icona sul telefono. Vincolo fisico dichiarato all'utente:
  in modalita' aereo la rete sparisce quasi subito. Disegno a tre
  livelli: (1) invio immediato best effort (4 s, niente coda: un
  "airplane_on" arrivato dopo il rientro mostrerebbe uno stato falso);
  (2) ora di inizio salvata in SharedPreferences e inviata al rientro
  ("airplane_off"/"boot" con since) da un WatchStateWorker con vincolo di
  rete; (3) sul telefono "non raggiungibile" dopo 30' senza notizie,
  calcolato localmente. Ricevitore registrato a runtime nel servizio di
  tracking (AIRPLANE_MODE_CHANGED non arriva ai ricevitori solo da
  manifest). Backend: riusato lo "status" di trigger-event (limite 12
  funzioni Vercel). Sul telefono aereo/spento vengono ignorati se dopo
  arriva altro dal watch (margine 60 s, perche' lo stesso invio aggiorna
  lastStatusAt con l'ora del server). Backend trigger-event v0.22.0,
  watch-app v0.28.0, phone-app v0.22.0 (v0.97.0).
- 2026-09-24: Confermate dall'utente tutte le notifiche di stato del
  watch (aereo on/off, spegnimento, riaccensione). Bug: alla
  riaccensione la barra dei tentativi restava con la posizione gia'
  arrivata. Ricostruito dallo storico: 00:23:10 "watch_boot" (status
  senza posizione) → il telefono lo ha letto come fix fallito → conto
  alla rovescia, durante il quale non guardava le posizioni; 00:23:20
  posizione arrivata. Correzione: nuovo campo lastNoFixAt (solo status
  senza watchState = tentativo fallito) usato al posto di lastStatusAt,
  e controllo delle posizioni in arrivo anche durante il conto alla
  rovescia (trigger-event v0.23.0, phone-app v0.23.0). Vercel: Node 20
  dismesso dal 01/10/2026 → backend e CI su Node 24, verificato con
  Node v24.21.0 (test, sintassi, caricamento moduli) (v0.98.0).
- 2026-09-24: Preparazione alla compattazione su richiesta dell'utente:
  aggiunta la sezione "Stato attuale (2026-09-24)" in cima a questo
  file (fotografia per chi riprende), aggiornata "Struttura repo";
  corretto README.md (backend descritto ancora come "Cloud Functions",
  aggiunti Node 24, scripts/ e workflow) (v0.98.1).
- 2026-09-24: Decisione utente sulla priorita' del tracking da fermo:
  torna bilanciata (default), con interruttore per bambino sulla
  phone-app per forzare l'alta precisione. Scelte: il valore vive sul
  device in Firestore (devices/{id}.trackingHighAccuracy), scritto solo
  dal backend (parent-command set_tracking_mode, controllo famiglia; le
  regole non permettono scritture client su devices), inviato al watch
  con push e riletto a ogni sync di device-config (se la push si perde).
  Sul watch il cambio riapplica la richiesta senza cambiare fermo/
  movimento (EXTRA_REAPPLY). Trovato e corretto nello stesso giro un bug
  preesistente: GeofenceSyncWorker cancellava tutte le zone quando la
  chiamata a device-config falliva (lista vuota = errore); ora ritenta.
  watch-app v0.29.0, phone-app v0.24.0, parent-command/device-config
  v0.5.0 (v0.99.0).
- 2026-09-24: Richiesta utente, viste le molte funzioni aggiunte: ogni
  nuova funzione va documentata con spiegazione nel README.md. Regola
  scritta in CLAUDE.md; creata la sezione "Funzioni" del README con
  l'elenco completo attuale (watch/telefono/backend) (v0.99.1).
- 2026-09-24: Richiesta utente: icona di stato quando il watch e' in
  carica. Il campo `charging` arrivava gia' con ogni punto/status, ma fino
  a 10' dopo il collegamento: il watch ora invia subito uno "status" con
  reason "power" su ACTION_POWER_CONNECTED/DISCONNECTED (ricevitore
  runtime in LocationTrackingService, come aereo/spegnimento; i broadcast
  di alimentazione non arrivano ai ricevitori del solo manifest). Il
  backend non lo conta come fix fallito (niente lastNoFixAt), nessuna push
  (solo icona, per non disturbare). Sulla phone-app 🔌 accanto al nome,
  solo se non c'e' gia' un'icona di allarme (aereo/spento/non
  raggiungibile). watch-app v0.30.0, phone-app v0.25.0, trigger-event
  v0.24.0 (v0.100.0).
- 2026-09-24: L'autonomia stimata non era mai comparsa. Il dato arrivava
  al backend sempre null (verificato con la diagnostica, v0.8.2 che ora
  lo stampa): la formula CHARGE_COUNTER/CURRENT_NOW veniva scartata sul
  Watch4 (unita' mA o segno non standard, probabile). Scelta: stima
  principale dall'andamento della percentuale dallo scollegamento
  (ancoraggio in SharedPreferences, azzerato in carica), piu' fedele
  all'uso reale; la formula hardware, normalizzata, resta solo come
  valore iniziale. Deroga consapevole alla richiesta del 19/9 ("chiesta
  al sistema operativo"): quella strada su questo hardware non da'
  valori usabili. watch-app v0.31.0 (v0.100.1).
- 2026-09-24: L'utente ha rifiutato la stima calcolata dall'app (v0.100.1):
  l'autonomia va chiesta a Wear OS. Unica API pubblica per l'autonomia in
  scarica: PowerManager.getBatteryDischargePrediction() (API 31+, lettura
  senza permessi). Se ritorna null non si mostra nulla, nessun ripiego
  calcolato. Da verificare sul Watch4 se Wear OS la fornisce davvero (log
  "BatteryInfo"). watch-app v0.32.0 (v0.100.2).
- 2026-09-24: Logcat di telefono (motorola edge 20, Android 13) e watch
  (SM-R895F, **Android 16 / API 36**, non Wear OS 3 come si assumeva).
  Trovato: BOOT_COMPLETED ri-consegnato dopo ogni arresto forzato
  (Android 15+) → falsi "watch riacceso" a ogni Run di Android Studio.
  Filtro su Settings.Global.BOOT_COUNT salvato in prefs (v0.100.3).
  Altri punti emersi: le push con payload "notification" sulla phone-app
  finiscono nel canale fcm_fallback (manca default_notification_channel_id
  nel manifest, correzione proposta); nel logcat del watch non compaiono
  le righe dei nostri tag, causa non chiarita.
- 2026-09-24: Canale di default FCM "alerts" nel manifest della phone-app
  (v0.26.0). Analisi battery drain sui due logcat: telefono normale
  (-15/-23 mA a schermo spento, 72→71% in 26'); watch -105 mA a schermo
  spento (64→59% in 16'), ~50 risvegli/min da pacchetti di rete senza
  app ("Invalid uid for waking network packet"), attribuibili al debug
  ADB via Wi-Fi attivo durante la cattura, non alle nostre app (nessun
  wakelock/job/GPS nostro nel log). Il watch riporta current_now in mA e
  cc in µAh (conferma del motivo per cui la vecchia formula d'autonomia
  veniva scartata). Per il consumo reale per app serve
  `dumpsys batterystats` senza ADB Wi-Fi attivo.
- 2026-09-24: Verificato sul campo: autonomia di Wear OS funziona (5,5 h).
  Silenzio 01:23→11:46 = arresto forzato lasciato da Android Studio
  (nessun codice puo' aggirarlo; alternativa Device Owner proposta e non
  scelta). Posizione su richiesta: getCurrentLocation restituiva la cache
  Wi-Fi senza accendere il GPS → ora requestLocationUpdates HIGH_ACCURACY
  con maxUpdateAge 0 e finestra di miglioramento di 30 s (v0.100.5,
  watch-app v0.34.0). Tracking da fermo lasciato su rete per scelta.
- 2026-09-24: Avviso push "watch muto" (v0.101.0). Scelte: controllo su
  GitHub Actions ogni 30' (Vercel Hobby non ha cron, stesso schema della
  pulizia), soglia 60' (tracking da fermo ≤10', upload ogni 15'), un
  avviso per periodo di silenzio (silenceAlertAt), niente avviso con
  aereo/spento gia' segnalati, niente push di "tornato raggiungibile".
  Alternativa scartata: Device Owner per impedire l'arresto forzato
  (reset di fabbrica, esito incerto su Samsung, non copre ADB).
- 2026-09-24: Avviso batteria scarica in ritardo (10% alle 15:21, push
  alle 15:39): il backend valuta la batteria solo quando riceve dati, e i
  punti restano sul watch fino al caricamento a gruppi (Doze lo rinvia).
  Scelta: il watch forza un caricamento espedito al primo attraversamento
  di 10/5/2 % (LowBatteryTrigger), invece di caricare piu' spesso sempre
  (costerebbe batteria). Nota: "GPS non usato" su una risposta da 15 m
  senza Wi-Fi era la posizione in cache (watch ancora < v0.34). Consumo
  pomeridiano ~17%/h con punti da fermo a 13-17 m (GPS acceso anche da
  fermo?) da verificare con batterystats.
