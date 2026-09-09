# phone-app

App Android (Kotlin, Compose) per il genitore: mappa con l'ultima
posizione del watch, storico spostamenti (ultime 48h), gestione zone
(geofence), notifiche push su SOS/ingresso-uscita zona. Login con
l'account Google del genitore (uno dei due pre-autorizzati, vedi
`../CONTEXT.md`). Mappa **OpenStreetMap** (libreria osmdroid), non
Google Maps: niente chiave API, niente fatturazione da collegare al
progetto Google Cloud — coerente con la scelta fatta per tutto il resto
dello stack (vedi CONTEXT.md, log decisioni).

**Stato:** setup Firebase completato, scaffolding completo,
**non ancora compilato/testato** — nessun SDK Android/rete verso i
repository Google Maven in questo ambiente (stesso limite di
`watch-app/`).

## Struttura

```
phone-app/
  settings.gradle.kts
  build.gradle.kts              (root)
  gradle.properties
  debug.keystore                 Firma di debug fissa nel progetto (vedi Setup)
  gradlew / gradlew.bat / gradle/wrapper/   Gradle wrapper (riusato da watch-app)
  app/
    build.gradle.kts
    google-services.json         Config Firebase reale (già presente, vedi Setup)
    src/main/
      AndroidManifest.xml
      kotlin/com/gwatch/childtracker/phone/
        TrackerApplication.kt         Canale notifica "alerts" + init osmdroid
        auth/AuthRepository.kt        Login Google + Firebase Auth
        data/
          model/Models.kt             DeviceState, LocationPoint, GeofenceZone, DeviceEvent
          DeviceRepository.kt         Listener Firestore in tempo reale + scrittura geofence
        messaging/FcmService.kt       Ricezione push SOS/geofence, registra il token
        ui/
          MainActivity.kt             NavHost login/mappa/zone
          AppViewModel.kt             Stato condiviso (StateFlow su Firestore)
          LoginScreen.kt
          MapScreen.kt                Mappa (osmdroid) + card stato + eventi recenti
          GeofenceScreen.kt           Aggiungi/modifica/elimina zone (tocco su mappa)
        util/
          Constants.kt                DEVICE_ID (deve combaciare col backend)
          TimeFormat.kt
      res/
```

## Come funziona (riassunto architetturale)

- **Nessun endpoint backend dedicato al phone**: l'app legge
  direttamente da Firestore con listener in tempo reale
  (`DeviceRepository`), protetti dalle regole di sicurezza
  (`backend/firestore.rules` — solo chi ha un documento `parents/{uid}`
  può leggere). La mappa si aggiorna da sola non appena il watch invia
  un nuovo punto, senza bisogno di un pulsante "aggiorna" o di una
  chiamata al backend.
- **Mappa**: `MapView` di osmdroid incorporato in Compose via
  `AndroidView` (osmdroid non ha una API Compose nativa). Tile
  OpenStreetMap (MAPNIK), marker per l'ultima posizione, polyline per
  lo storico, poligoni-cerchio per le geofence.
- **Geofence**: uniche scritture dirette dal client. `GeofenceScreen`
  scrive/aggiorna/cancella documenti in `devices/figlio/geofences/`;
  il watch le legge poi da `/api/device-config` (nessuna sincronia
  diretta phone→watch, passa dal backend).
- **Notifiche push**: al login viene registrato il token FCM del
  telefono in `parents/{uid}.fcmTokens` (array, un genitore può avere
  più dispositivi). `trigger-event` sul backend invia la push a tutti i
  token registrati quando arriva un SOS o una transizione di zona.
- **Storico**: mostra solo le ultime 48h (`Constants.HISTORY_WINDOW_HOURS`)
  anche se il backend ne conserva 12 mesi, per restare leggibile/veloce.

## Setup Firebase — già fatto

L'app Android è già registrata sul progetto Firebase reale
(`child-tracker-7a1f1`, package `com.gwatch.childtracker.phone`), fatto
via API con la service account che mi hai fornito (nessuna carta
richiesta: Firestore/Auth restano sul piano Spark). Già presenti nel
repo (entrambi ignorati da git, vedi `.gitignore` — restano solo sulla
tua macchina):

- `app/google-services.json` — config reale scaricata da Firebase.
- `debug.keystore` — keystore di debug generato per l'occasione, il cui
  SHA-1 (`40:53:35:1C:55:9E:EB:45:FC:8C:21:1B:76:37:56:0A:25:9F:22:CC`)
  è registrato su Firebase per far funzionare il login Google.
  `app/build.gradle.kts` lo usa come firma di debug al posto di quello
  di default in `~/.android/`, così la firma resta identica su
  qualunque macchina tu compili — nessun passaggio "prendi la tua
  SHA-1 e registrala" da fare a mano.

**Non serve nessuna chiave Maps**: con osmdroid non c'è nulla da
configurare per la mappa.

## Compilare e testare

1. Apri `phone-app/` in Android Studio.
2. Sync Gradle.
3. Esegui su un telefono/emulatore con Google Play Services (serve per
   il login Google, non per la mappa).
4. Accedi con **cristianozecchi@gmail.com** o
   **benedettagarofalo81@gmail.com** (gli unici due account
   pre-autorizzati in `parents/`, vedi `CONTEXT.md`) — con un altro
   account Google il login riesce ma la lettura dati viene respinta
   dalle regole Firestore (comportamento atteso, non un bug).

## Cosa NON è ancora stato testato

- Compilazione reale (nessun SDK Android in questo ambiente).
- Login Google end-to-end (la SHA-1 è registrata correttamente, ma il
  flusso completo va verificato su dispositivo).
- Rendering mappa/geofence/notifiche su dispositivo reale.

## Note

Vedi [`../CONTEXT.md`](../CONTEXT.md) per architettura completa e
[`../CHANGELOG.md`](../CHANGELOG.md) per lo storico.
