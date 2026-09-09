# Contesto di progetto — gwatch-child-tracker

> Questo file va aggiornato ad ogni commit significativo: riflette lo stato
> attuale del progetto, le decisioni prese e il motivo per cui sono state
> prese. Non è uno storico (per quello c'è CHANGELOG.md), è una fotografia
> del "dove siamo e perché".

**Versione contesto:** 0.13.0
**Ultimo aggiornamento:** 2026-09-09

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
- **Mappa:** Google Maps SDK (free tier $200/mese di credito Google,
  sufficiente per uso familiare).
- **Auth:** Firebase Authentication, legata all'account Google del
  genitore (non a quello "adulto" del watch, che resta solo login
  tecnico del dispositivo).
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
- **watch-app/**: non ancora implementata.
- **phone-app/**: non ancora implementata.

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
