# backend

Backend Firebase (piano gratuito Spark) condiviso da watch-app e
phone-app.

**Stato:** endpoint MVP implementati (Cloud Functions + regole
Firestore). Non ancora distribuito su un progetto Firebase reale.

## Struttura

```
backend/
  firebase.json          Configurazione progetto Firebase (functions, emulatori)
  .firebaserc             ID progetto Firebase (da compilare, vedi Setup)
  firestore.rules         Regole di sicurezza Firestore
  firestore.indexes.json  Indici Firestore (vuoto per ora, MVP non ne richiede)
  functions/
    index.js               Cloud Functions (endpoint HTTPS + trigger Firestore)
    package.json
    .env.example            Template variabili d'ambiente (token)
```

## Modello dati Firestore

```
devices/{deviceId}                      stato corrente (lastLocation, battery, lastSeen, activity)
devices/{deviceId}/locations/{autoId}    storico posizioni (retention 48h via TTL, vedi Setup)
devices/{deviceId}/geofences/{zoneId}    zone configurate dal genitore (name, lat, lon, radiusMeters, active)
devices/{deviceId}/events/{autoId}       eventi (sos, in futuro geofence_enter/exit)
parents/{uid}                            token FCM del genitore per le push
```

MVP: un solo dispositivo (`devices/figlio`), un solo genitore.
Multi-figlio/multi-genitore è in backlog Fase 2 (vedi `../CONTEXT.md`).

## Endpoint Cloud Functions

| Endpoint | Metodo | Auth | Chiamato da |
|---|---|---|---|
| `/ingestLocation` | POST | header `X-Device-Token` | watch-app (batch posizioni) |
| `/triggerSos` | POST | header `X-Device-Token` | watch-app (pulsante SOS) |
| `/deviceConfig` | GET | header `X-Device-Token` | watch-app (legge geofence attive) |
| `/haStatus` | GET | header `Authorization: Bearer <token>` | Home Assistant (polling opzionale) |

La phone-app **non** passa da questi endpoint: legge/scrive Firestore
direttamente via SDK con Firebase Auth (realtime, nessun costo extra
nel piano gratuito per questo volume).

## Setup

1. Crea un progetto Firebase (piano Spark, gratuito) dalla console
   Firebase, poi sostituisci il placeholder in `.firebaserc` con il suo
   project ID.
2. `cd backend/functions && cp .env.example .env`, genera due token
   casuali lunghi (es. `openssl rand -hex 32`) e valorizza
   `DEVICE_TOKEN` e `HA_STATUS_TOKEN`. Non committare mai `.env`.
3. In `firestore.rules`, sostituisci
   `SOSTITUISCI_CON_UID_GENITORE` con l'UID Firebase Auth reale del
   genitore (visibile in Console → Authentication dopo il primo login
   dalla phone-app).
4. Abilita la **TTL policy** su `devices/*/locations` sul campo
   `expiresAt` (una tantum, non gestibile da file di config):
   ```
   gcloud firestore fields ttl-configs create \
     --collection-group=locations --field=expiresAt
   ```
   Così lo storico oltre le 48h viene eliminato automaticamente e
   gratuitamente da Firestore, senza bisogno di una Cloud Function
   dedicata.
5. `npm install` dentro `functions/`.

## Sviluppo locale

```
firebase emulators:start --only functions,firestore
```

Nessun costo, nessun deploy necessario per testare la logica.

## Deploy

```
firebase deploy --only functions,firestore:rules
```

## Note

Vedi [`../CONTEXT.md`](../CONTEXT.md) per architettura completa e
[`../CHANGELOG.md`](../CHANGELOG.md) per lo storico.
