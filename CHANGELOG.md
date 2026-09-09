# Changelog

Tutte le modifiche rilevanti al progetto sono documentate in questo file.

Formato basato su [Keep a Changelog](https://keepachangelog.com/it/1.0.0/),
versionamento secondo [Semantic Versioning](https://semver.org/lang/it/).

## [Unreleased]

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
