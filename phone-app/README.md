# phone-app

App Android (Kotlin, Compose) per il genitore: mappa con l'ultima
posizione del watch, storico spostamenti (ultime 48h), gestione zone
(geofence), notifiche push su SOS/ingresso-uscita zona. Login con
l'account Google del genitore (uno dei due pre-autorizzati, vedi
`../CONTEXT.md`).

**Stato:** scaffolding completo, **non ancora compilato/testato** —
stesso limite di `watch-app/` (nessun SDK Android/rete verso i
repository Google Maven in questo ambiente). Richiede inoltre due
passaggi di setup su Firebase Console che non posso più fare io da qui
(vedi sotto): le credenziali della service account usate nella sessione
precedente non sono più disponibili in questo ambiente (container
effimero).

## Struttura

```
phone-app/
  settings.gradle.kts
  build.gradle.kts              (root)
  gradle.properties
  local.properties.example       Template config locale (chiave Maps)
  gradlew / gradlew.bat / gradle/wrapper/   Gradle wrapper (riusato da watch-app)
  app/
    build.gradle.kts
    google-services.json         DA SCARICARE da Firebase Console (vedi Setup)
    src/main/
      AndroidManifest.xml
      kotlin/com/gwatch/childtracker/phone/
        TrackerApplication.kt         Canale di notifica "alerts"
        auth/AuthRepository.kt        Login Google + Firebase Auth
        data/
          model/Models.kt             DeviceState, LocationPoint, GeofenceZone, DeviceEvent
          DeviceRepository.kt         Listener Firestore in tempo reale + scrittura geofence
        messaging/FcmService.kt       Ricezione push SOS/geofence, registra il token
        ui/
          MainActivity.kt             NavHost login/mappa/zone
          AppViewModel.kt             Stato condiviso (StateFlow su Firestore)
          LoginScreen.kt
          MapScreen.kt                Mappa + card stato + eventi recenti
          GeofenceScreen.kt           Aggiungi/modifica/elimina zone
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

## Setup richiesto (una tantum, non fatto da questa sessione)

### 1. Registrare l'app Android su Firebase Console

Progetto: `child-tracker-7a1f1`.

1. Console Firebase → **Impostazioni progetto** → **Aggiungi app** → Android.
2. Nome pacchetto: `com.gwatch.childtracker.phone` (deve combaciare
   esattamente con `applicationId` in `app/build.gradle.kts`).
3. **Importante per il login Google**: nello stesso step, aggiungi
   l'impronta SHA-1 del certificato di debug. Da terminale, con Android
   Studio/JDK installati:
   ```
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
   copia la riga `SHA1:` nel campo richiesto dalla Console.
4. Scarica `google-services.json` e mettilo in `phone-app/app/`
   (è già ignorato da git, vedi `.gitignore`).

Senza questo file l'app non compila (il plugin
`com.google.gms.google-services` fallisce la build se manca).

### 2. Chiave Google Maps

1. Google Cloud Console (stesso progetto `child-tracker-7a1f1`) → API e
   servizi → Libreria → abilita **Maps SDK for Android**.
2. Crea una chiave API, ristretta per sicurezza a "Android apps" con il
   nome pacchetto `com.gwatch.childtracker.phone` + la stessa SHA-1 del
   punto precedente.
3. Copia `local.properties.example` in `local.properties` e incolla la
   chiave in `maps.api.key`.

Rientra nel credito gratuito $200/mese di Google Maps Platform — per
uso familiare (poche mappe caricate al giorno) resta a 0€.

### 3. Compilare e testare

1. Apri `phone-app/` in Android Studio.
2. Sync Gradle.
3. Esegui su un telefono/emulatore con Google Play Services.
4. Accedi con **cristianozecchi@gmail.com** o
   **benedettagarofalo81@gmail.com** (gli unici due account
   pre-autorizzati in `parents/`, vedi `CONTEXT.md`) — con un altro
   account Google il login riesce ma la lettura dati viene respinta
   dalle regole Firestore (comportamento atteso, non un bug).

## Cosa NON è ancora stato testato

- Compilazione reale (nessun SDK Android in questo ambiente).
- Login Google end-to-end (richiede SHA-1 registrata, vedi Setup).
- Comportamento mappa/geofence/notifiche su dispositivo reale.

## Note

Vedi [`../CONTEXT.md`](../CONTEXT.md) per architettura completa e
[`../CHANGELOG.md`](../CHANGELOG.md) per lo storico.
