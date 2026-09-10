# Contesto di progetto — gwatch-child-tracker

> Questo file va aggiornato ad ogni commit significativo: riflette lo stato
> attuale del progetto, le decisioni prese e il motivo per cui sono state
> prese. Non è uno storico (per quello c'è CHANGELOG.md), è una fotografia
> del "dove siamo e perché".

**Versione contesto:** 0.34.0
**Ultimo aggiornamento:** 2026-09-10

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

## Struttura repo

```
/watch-app    Wear OS app (Kotlin) — installata sul Galaxy Watch4
/phone-app    App Android (Kotlin) — usata dal genitore
/backend      Firebase (Firestore rules, Cloud Functions)
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
