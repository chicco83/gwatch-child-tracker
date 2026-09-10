# watch-app

App Wear OS (Kotlin) installata sul Galaxy Watch4 LTE del figlio.

**Stato:** scaffolding completo, setup Firebase (chat) completato,
**non ancora compilato/testato** — in questo ambiente non c'è l'SDK
Android né rete verso i repository Google Maven, quindi non ho potuto
verificare la build. Va aperto in Android Studio per il primo
build/test reale.

## Struttura

```
watch-app/
  settings.gradle.kts
  build.gradle.kts              (root)
  gradle.properties
  local.properties.example       Template config locale (device token, URL backend)
  gradlew / gradlew.bat / gradle/wrapper/   Gradle wrapper già generato
  app/
    build.gradle.kts
    google-services.json         Config Firebase reale (solo per la chat, vedi Setup)
    src/main/
      AndroidManifest.xml
      kotlin/com/gwatch/childtracker/
        TrackerApplication.kt        Canali di notifica (tracking, messaggi)
        config/BackendConfig.kt      Legge i BuildConfig (device token, URL)
        network/
          BackendClient.kt           Client verso gli endpoint del watch
          model/                     LocationPoint, GeofenceZone, ChatMessage
        data/
          PendingLocationStore.kt      Buffer locale posizioni non ancora inviate
          MessageStore.kt              Stato chat in memoria (letto da ChatScreen)
        location/
          LocationTrackingService.kt   Foreground service, sampling adattivo
          ActivityTransitionReceiver.kt Rileva fermo/in movimento
        upload/LocationUploadWorker.kt  Svuota il buffer verso il backend
        geofence/
          GeofenceSyncWorker.kt        Sincronizza le zone dal backend
          GeofenceBroadcastReceiver.kt  Riceve ingresso/uscita zona
          GeofenceEventWorker.kt        Invia l'evento al backend
        sos/SosWorker.kt              Fix posizione + invio SOS (espedito)
        messaging/FcmService.kt       Riceve la chat in push, registra il token
        boot/BootReceiver.kt          Riavvia service/geofence dopo reboot
        ui/
          MainActivity.kt              Permessi + navigazione main/chat
          ChatScreen.kt                 Chat: risposte rapide + dettatura vocale
      res/
```

## Come funziona (riassunto architetturale)

- **Sampling adattivo**: `LocationTrackingService` gira come foreground
  service persistente. `ActivityTransitionReceiver` rileva se il
  bambino è fermo o in movimento e dice al service quale intervallo
  usare — 10 minuti da fermo, 1 minuto in movimento (vedi
  `../CONTEXT.md`).
- **Upload a batch**: ogni punto GPS finisce in un buffer locale
  (`PendingLocationStore`, SharedPreferences). Un `LocationUploadWorker`
  periodico (ogni 15 minuti, minimo consentito da WorkManager) svuota
  il buffer verso `/api/ingest-location`; se il buffer supera 15 punti,
  un upload immediato viene forzato subito invece di aspettare.
- **Geofence**: gestite dalla Geofencing API di Android (livello OS,
  non polling nell'app). `GeofenceSyncWorker` scarica le zone da
  `/api/device-config` ogni 6 ore e le registra. Le transizioni
  arrivano a `GeofenceBroadcastReceiver`, che le inoltra a
  `/api/trigger-event` tramite `GeofenceEventWorker`.
- **SOS**: bottone in `MainActivity` → `SosWorker` (lavoro *espedito*,
  bypassa Doze/App Standby) prende un fix ad alta precisione e chiama
  `/api/trigger-event` con `type: "sos"`.
- **Chat**: push FCM, non polling — scelta per il consumo batteria
  (con FCM il radio resta a riposo tra un messaggio e l'altro; il
  polling lo terrebbe sveglio a intervalli fissi anche senza nulla di
  nuovo, vedi CONTEXT.md). `FcmService` riceve i messaggi del genitore
  (sempre payload "data", mai "notification": la notifica va costruita
  a mano, vedi commento nel file) e li mette in `MessageStore`, letto
  da `ChatScreen`. Niente tastiera (impraticabile su un display così
  piccolo): solo risposte rapide preimpostate e dettatura vocale
  (`RecognizerIntent`, gestito dall'app di sistema). Allo stesso modo
  di `GeofenceSyncWorker`, il token FCM viene registrato via
  `/api/register-watch-token`; lo storico recente arriva da
  `/api/messages` all'apertura della `ChatScreen` (il watch non ha un
  SDK Firestore completo, solo `firebase-messaging` per la push).

## Setup Firebase (chat) — già fatto

L'app è registrata sul progetto Firebase reale (`child-tracker-7a1f1`,
package `com.gwatch.childtracker`), fatto via API con la stessa
service account già usata per `phone-app/` (vedi `../CONTEXT.md`,
log decisioni). Il file `app/google-services.json` è già presente nel
repo locale (ignorato da git, vedi `.gitignore` — resta solo sulla tua
macchina). A differenza della phone-app, **non serve nessuna SHA-1**:
la chat usa solo FCM, niente login Google sul watch.

## Setup per compilare/testare (stasera)

1. Apri la cartella `watch-app/` in Android Studio (Iguana o più
   recente).
2. Copia `local.properties.example` in `local.properties` (nella
   stessa cartella, già ignorato da git) e compila:
   - `device.token`: **stesso valore** di `DEVICE_TOKEN` impostato su
     Vercel (backend/.env.example) — senza questo il watch non riesce
     ad autenticarsi col backend.
   - `backend.base.url`: lascia il default
     `https://gwatch-child-tracker.vercel.app` a meno che tu non l'abbia
     cambiato.
3. Sync Gradle (Android Studio lo propone da solo aprendo il progetto).
4. Con il watch in modalità sviluppatore + debug via WiFi già
   abbinato (vedi conversazione precedente su come farlo senza cavo):
   *Run → Run 'app'*, seleziona il Watch4 come target.
5. Alla prima apertura l'app chiede i permessi (posizione, activity
   recognition, notifiche, poi posizione in background come step
   separato — richiesto da Android). Concedili tutti perché il
   tracking funzioni anche ad app chiusa.

## Cosa NON è ancora stato testato

- Compilazione reale (nessun SDK Android in questo ambiente).
- Comportamento su hardware reale (sampling adattivo, geofence,
  consumo batteria).
- Flusso di richiesta permessi end-to-end.

## Note

Vedi [`../CONTEXT.md`](../CONTEXT.md) per architettura completa e
[`../CHANGELOG.md`](../CHANGELOG.md) per lo storico.
