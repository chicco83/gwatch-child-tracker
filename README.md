# gwatch-child-tracker

App per geolocalizzare un figlio minorenne tramite Samsung Galaxy Watch4 LTE (companion Wear OS) + app Android per il genitore, con backend Firebase (piano gratuito).

- Stato del progetto, decisioni architetturali e backlog: vedi [`CONTEXT.md`](./CONTEXT.md).
- Storico versioni: vedi [`CHANGELOG.md`](./CHANGELOG.md).

## Struttura

```
/watch-app    Wear OS app (Kotlin) — installata sul Galaxy Watch4
/phone-app    App Android (Kotlin) — usata dal genitore
/backend      Firebase (Firestore rules, Cloud Functions)
```

## Stack

- Watch: Wear OS (Kotlin), Google Play Services (FusedLocationProvider, Geofencing API, ActivityRecognitionClient), chat/comandi remoti via push FCM.
- Phone: Android (Kotlin/Compose), mappa **OpenStreetMap (osmdroid)** — non Google Maps SDK, sostituito in v0.17.0 per evitare una fatturazione Google Cloud permanentemente attiva (vedi CONTEXT.md).
- Backend: Firebase Spark (Firestore, FCM) + **Vercel Functions** (Node.js/`firebase-admin`) al posto delle Cloud Functions di Firebase, che richiederebbero il piano Blaze — migrato in v0.6.0 (vedi CONTEXT.md).
- Distribuzione: ADB via WiFi in sviluppo, Play Console Internal Testing per il watch in uso

## Installazione

1. Clona il repository.
2. Installa le dipendenze per l'app Android.
   - Naviga nella directory `phone-app` e esegui `./gradlew build` per compilare l'app.
3. Installa le dipendenze per l'app Wear OS.
   - Naviga nella directory `watch-app` e esegui `./gradlew build` per compilare l'app.
4. Configura Firebase:
   - Crea un progetto Firebase.
   - Configura Firestore rules e Cloud Functions.
   - Aggiungi le credenziali Firebase al progetto.
5. Esegui l'app:
   - Connetti il Galaxy Watch4 al computer via USB.
   - Esegui `adb devices` per verificare la connessione.
   - Esegui `adb install -r app/build/outputs/apk/debug/app-debug.apk` per installare l'app Wear OS.
   - Esegui l'app Android sul dispositivo Android.

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
