# backend

Backend a costo zero, senza carta collegata da nessuna parte:
**Firestore su piano Firebase Spark** (datastore) + **funzioni
HTTP su Vercel** (compute), invece delle Cloud Functions di Firebase
(che richiedono il piano Blaze — vedi log decisioni in
`../CONTEXT.md`).

**Stato:** endpoint MVP implementati. Regole Firestore già deployate
sul progetto reale (`child-tracker-7a1f1`). Deploy su Vercel da fare
(vedi Setup).

## Struttura

```
backend/
  firebase.json            Config Firestore (regole, indici, emulatore) — niente Functions
  .firebaserc               ID progetto Firebase
  firestore.rules           Regole di sicurezza Firestore
  firestore.indexes.json    Indici Firestore (vuoto, MVP non ne richiede)
  package.json              Dipendenze delle funzioni Vercel (firebase-admin)
  vercel.json                Config runtime funzioni Vercel
  .env.example                Template variabili d'ambiente (token + service account)
  api/
    ingest-location.js        POST — batch posizioni dal watch
    trigger-event.js          POST — SOS o transizione geofence + push FCM
    device-config.js          GET  — geofence attive per il watch
    ha-status.js               GET  — stato per il polling opzionale di Home Assistant
    cleanup.js                  GET  — pulizia storico scaduto, invocata da GitHub Actions
    send-message.js             POST — messaggio chat dal watch al genitore + push FCM
    send-message-to-child.js    POST — messaggio chat dal genitore al watch + push FCM
    register-watch-token.js     POST — registra il token FCM del watch
    messages.js                  GET  — storico chat recente (usato dal watch)
    _lib/
      firebase-admin.js         Init condivisa dell'Admin SDK
      auth.js                    Verifica token device/HA/genitore
      quota.js                    Guardia di traffico giornaliera (vedi sotto)
```

## Modello dati Firestore

```
devices/{deviceId}                      stato corrente (lastLocation, battery, lastSeen, activity)
devices/{deviceId}/locations/{autoId}    storico posizioni (retention 12 mesi via TTL, vedi Setup)
devices/{deviceId}/geofences/{zoneId}    zone configurate dal genitore (name, lat, lon, radiusMeters, active)
devices/{deviceId}/events/{autoId}       eventi (sos, geofence_enter, geofence_exit)
devices/{deviceId}/messages/{autoId}     chat testuale (sender: "parent"|"child", text, timestamp)
devices/{deviceId}/quota/{YYYY-MM-DD}    contatore chiamate/giorno (solo backend, vedi sotto)
parents/{uid}                            token FCM del genitore per le push
```

`devices/{deviceId}.fcmToken` (campo sul documento principale, non una
sotto-collezione): token FCM del watch, per svegliarlo quando il
genitore scrive in chat (vedi `register-watch-token.js`).

MVP: un solo dispositivo (`devices/figlio`), un solo genitore.
Multi-figlio/multi-genitore è in backlog Fase 2 (vedi `../CONTEXT.md`).

## Endpoint (funzioni Vercel)

| Endpoint | Metodo | Auth | Chiamato da |
|---|---|---|---|
| `/api/ingest-location` | POST | header `X-Device-Token` | watch-app (batch posizioni) |
| `/api/trigger-event` | POST | header `X-Device-Token` | watch-app (SOS, ingresso/uscita geofence) — scrive l'evento e invia la push FCM nella stessa chiamata |
| `/api/device-config` | GET | header `X-Device-Token` | watch-app (legge geofence attive) |
| `/api/ha-status` | GET | header `Authorization: Bearer <token>` | Home Assistant (polling opzionale) |
| `/api/send-message` | POST | header `X-Device-Token` | watch-app (chat: messaggio verso il genitore) — scrive + invia la push FCM nella stessa chiamata |
| `/api/send-message-to-child` | POST | header `Authorization: Bearer <Firebase ID token>` | phone-app (chat: messaggio verso il watch) — scrive + invia la push FCM (solo dati, sveglia l'app) nella stessa chiamata |
| `/api/register-watch-token` | POST | header `X-Device-Token` | watch-app (registra il token FCM per ricevere la chat) |
| `/api/messages` | GET | header `X-Device-Token` | watch-app (storico chat recente, per recuperare messaggi persi ad app chiusa) |

Quasi tutta la phone-app legge/scrive Firestore direttamente via SDK
con Firebase Auth (realtime, nessun costo extra nel piano gratuito per
questo volume) **senza** passare da questi endpoint — l'eccezione è
l'invio dei messaggi di chat (`send-message-to-child`): serve un
passaggio dal backend perché è l'unico posto da cui si può inviare
anche la push FCM che sveglia il watch (su Vercel non c'è un trigger
Firestore equivalente a `onDocumentCreated`, vedi `trigger-event.js`).
La lettura della chat resta invece un listener Firestore diretto,
come tutto il resto.

## Limite di traffico (rete di sicurezza)

Ogni endpoint (tranne l'SOS, volutamente esente — vedi
`trigger-event.js`) è protetto da un tetto di **4.000 chiamate al
giorno per dispositivo**, molto sotto le soglie gratuite reali
(Firestore Spark: 20.000 scritture/giorno; Vercel Hobby: 100 GB-Hours
di esecuzione/mese). Superato il tetto, l'endpoint risponde `429`
invece di eseguire l'operazione — un margine di sicurezza fisso nel
codice, non un tentativo di avvicinarsi alle quote reali. Implementato
in `api/_lib/quota.js`, contatore in `devices/{id}/quota/{YYYY-MM-DD}`.

## Setup

### Firestore (Firebase, piano Spark)

1. Progetto Firebase già creato: `child-tracker-7a1f1` (vedi
   `.firebaserc`). Database Firestore e regole già deployati.
2. **Multi-genitore**: le regole autorizzano chiunque abbia un
   documento in `parents/{uid}` — nessun UID hardcoded, nessun
   redeploy delle regole per aggiungere un genitore. Il documento va
   però creato SOLO da admin (mai dal client, per evitare che un
   account Google qualsiasi si auto-autorizzi): per ogni genitore da
   abilitare, creare via Admin SDK/service account:
   ```
   parents/{uid}  { fcmTokens: [] }
   ```
   L'UID si ottiene creando l'utente Firebase Auth (Console ->
   Authentication, o `auth.createUser({ email })` via Admin SDK) se
   non esiste già.
3. **Pulizia storico**: la TTL policy nativa di Firestore richiede il
   piano Blaze (anche per un uso gratuito), quindi non la usiamo — la
   pulizia gira invece via un **workflow GitHub Actions**
   (`.github/workflows/cleanup-cron.yml`) che chiama `/api/cleanup`
   una volta al giorno. Non usiamo i Cron Job di Vercel: il piano
   Hobby ha bloccato il deploy con `crons` in `vercel.json` (vedi log
   decisioni in `../CONTEXT.md`). Gli indici collection-group su
   `expiresAt` necessari alla query sono già deployati
   (`firestore.indexes.json`).

### Funzioni (Vercel)

1. Genera una chiave della service account Firebase (Console ->
   Impostazioni progetto -> Account di servizio -> Genera nuova
   chiave privata) e codificala in base64: `base64 -w0 chiave.json`.
2. Su [vercel.com](https://vercel.com): *Add New -> Project -> Import*
   questo repository GitHub, impostando **Root Directory** su
   `backend`. Nessuna carta richiesta per il piano Hobby gratuito.
3. In *Project Settings -> Environment Variables* aggiungi (vedi
   `.env.example`): `FIREBASE_SERVICE_ACCOUNT_B64`, `DEVICE_TOKEN`
   (generato es. con `openssl rand -hex 32`), `HA_STATUS_TOKEN`,
   `CRON_SECRET` (altra stringa casuale, stesso valore che andrà
   anche come secret GitHub — vedi punto 5).
4. Deploy: automatico ad ogni push su questo branch/repo una volta
   collegato il progetto — nessun comando manuale da rilanciare in
   seguito.
5. Per attivare la pulizia programmata: nel repository GitHub, *Settings
   -> Secrets and variables -> Actions*, aggiungi un secret
   `CRON_SECRET` con lo stesso valore usato su Vercel. Il workflow
   `.github/workflows/cleanup-cron.yml` gira poi da solo una volta al
   giorno (avviabile anche a mano dalla tab *Actions* del repo, per
   testarlo subito senza aspettare).

## Sviluppo locale

```
cd backend && vercel dev
```

Oppure per la sola parte Firestore, senza le funzioni:

```
firebase emulators:start --only firestore
```

## Note

Vedi [`../CONTEXT.md`](../CONTEXT.md) per architettura completa e
[`../CHANGELOG.md`](../CHANGELOG.md) per lo storico.
