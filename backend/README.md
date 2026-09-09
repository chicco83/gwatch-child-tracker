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
    _lib/
      firebase-admin.js         Init condivisa dell'Admin SDK
      auth.js                    Verifica token device/HA
```

## Modello dati Firestore

```
devices/{deviceId}                      stato corrente (lastLocation, battery, lastSeen, activity)
devices/{deviceId}/locations/{autoId}    storico posizioni (retention 48h via TTL, vedi Setup)
devices/{deviceId}/geofences/{zoneId}    zone configurate dal genitore (name, lat, lon, radiusMeters, active)
devices/{deviceId}/events/{autoId}       eventi (sos, geofence_enter, geofence_exit)
parents/{uid}                            token FCM del genitore per le push
```

MVP: un solo dispositivo (`devices/figlio`), un solo genitore.
Multi-figlio/multi-genitore è in backlog Fase 2 (vedi `../CONTEXT.md`).

## Endpoint (funzioni Vercel)

| Endpoint | Metodo | Auth | Chiamato da |
|---|---|---|---|
| `/api/ingest-location` | POST | header `X-Device-Token` | watch-app (batch posizioni) |
| `/api/trigger-event` | POST | header `X-Device-Token` | watch-app (SOS, ingresso/uscita geofence) — scrive l'evento e invia la push FCM nella stessa chiamata |
| `/api/device-config` | GET | header `X-Device-Token` | watch-app (legge geofence attive) |
| `/api/ha-status` | GET | header `Authorization: Bearer <token>` | Home Assistant (polling opzionale) |

La phone-app **non** passa da questi endpoint: legge/scrive Firestore
direttamente via SDK con Firebase Auth (realtime, nessun costo extra
nel piano gratuito per questo volume).

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
3. Abilita la **TTL policy** su `devices/*/locations` sul campo
   `expiresAt` (una tantum):
   ```
   gcloud firestore fields ttl-configs create \
     --collection-group=locations --field=expiresAt \
     --project=child-tracker-7a1f1
   ```
   Così lo storico oltre le 48h viene eliminato automaticamente e
   gratuitamente da Firestore.

### Funzioni (Vercel)

1. Genera una chiave della service account Firebase (Console ->
   Impostazioni progetto -> Account di servizio -> Genera nuova
   chiave privata) e codificala in base64: `base64 -w0 chiave.json`.
2. Su [vercel.com](https://vercel.com): *Add New -> Project -> Import*
   questo repository GitHub, impostando **Root Directory** su
   `backend`. Nessuna carta richiesta per il piano Hobby gratuito.
3. In *Project Settings -> Environment Variables* aggiungi (vedi
   `.env.example`): `FIREBASE_SERVICE_ACCOUNT_B64`, `DEVICE_TOKEN`
   (generato es. con `openssl rand -hex 32`), `HA_STATUS_TOKEN`.
4. Deploy: automatico ad ogni push su questo branch/repo una volta
   collegato il progetto — nessun comando manuale da rilanciare in
   seguito.

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
