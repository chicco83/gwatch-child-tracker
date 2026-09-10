# gwatch-child-tracker

App per geolocalizzare un figlio minorenne tramite Samsung Galaxy Watch4
LTE (companion Wear OS) + app Android per il genitore, con backend
Firebase (piano gratuito).

- Stato del progetto, decisioni architetturali e backlog: vedi
  [`CONTEXT.md`](./CONTEXT.md).
- Storico versioni: vedi [`CHANGELOG.md`](./CHANGELOG.md).

## Struttura

```
/watch-app    Wear OS app (Kotlin) — installata sul Galaxy Watch4
/phone-app    App Android (Kotlin) — usata dal genitore
/backend      Firebase (Firestore rules, Cloud Functions)
```

## Stack

- Watch: Wear OS (Kotlin), Google Play Services (FusedLocationProvider,
  Geofencing API, ActivityRecognitionClient), chat/comandi remoti via
  push FCM.
- Phone: Android (Kotlin/Compose), mappa **OpenStreetMap (osmdroid)** —
  non Google Maps SDK, sostituito in v0.17.0 per evitare una
  fatturazione Google Cloud permanentemente attiva (vedi CONTEXT.md).
- Backend: Firebase Spark (Firestore, FCM) + **Vercel Functions**
  (Node.js/`firebase-admin`) al posto delle Cloud Functions di
  Firebase, che richiederebbero il piano Blaze — migrato in v0.6.0
  (vedi CONTEXT.md).
- Distribuzione: ADB via WiFi in sviluppo, Play Console Internal
  Testing per il watch in uso
