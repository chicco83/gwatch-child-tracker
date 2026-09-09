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
  Geofencing API, ActivityRecognitionClient)
- Phone: Android (Kotlin), Google Maps SDK
- Backend: Firebase Spark (Firestore, Cloud Functions, FCM)
- Distribuzione: ADB via WiFi in sviluppo, Play Console Internal
  Testing per il watch in uso
