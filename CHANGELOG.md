# Changelog

Tutte le modifiche rilevanti al progetto sono documentate in questo file.

Formato basato su [Keep a Changelog](https://keepachangelog.com/it/1.0.0/),
versionamento secondo [Semantic Versioning](https://semver.org/lang/it/).

## [Unreleased]

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
