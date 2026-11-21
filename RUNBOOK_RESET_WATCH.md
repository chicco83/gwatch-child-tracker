# Runbook — factory reset del watch e ripristino dell'app senza nuova associazione

**Versione:** 1.0 — 2026-11-21
**Vale per:** Galaxy Watch4 LTE (Wear OS) con la watch-app `com.gwatch.childtracker`.

## Scopo

Poter fare il reset di fabbrica del watch e rimettere in funzione il tracciamento
**senza creare un nuovo bambino e senza rifare l'associazione dal telefono del genitore**.
Questo runbook copre solo la parte dell'app. La eSIM è un'altra cosa (vedi sotto).

## Fatti verificati nel codice (2026-11-21)

1. **L'identità del watch è il `device.token`, scritto dentro l'APK al momento della build.**
   `watch-app/app/build.gradle.kts` legge `device.token` e `backend.base.url` da
   `watch-app/local.properties` e li inietta come `BuildConfig.DEVICE_TOKEN` /
   `BuildConfig.BACKEND_BASE_URL`; `config/BackendConfig.kt` li espone. Il watch non salva
   il token da nessun'altra parte e non c'è nessun passaggio di "associazione" sul watch.
2. **Il backend riconosce il bambino dal token.** `backend/api/_lib/auth.js`
   (`resolveDeviceId`) calcola lo SHA-256 del token ricevuto nell'header `x-device-token`
   e cerca `devices/{childId}.deviceTokenHash`. Su Firestore c'è solo l'hash, mai il token.
   Il token in chiaro viene mostrato **una volta sola** quando il genitore usa
   "Aggiungi bambino" sul telefono (azione `create_child` di `parent-command.js`).
3. **Quindi:** un APK costruito con lo stesso `device.token`, installato su un watch
   resettato, risulta subito lo stesso bambino (stessa cronologia, zone, chat, famiglia).
   Il telefono del genitore non interviene.
4. **Cosa si perde col reset** (`allowBackup="false"` nel manifest, quindi nessun
   backup automatico di Android):
   - i punti GPS non ancora inviati (`PendingLocationStore`, al massimo qualche
     decina di minuti di posizioni);
   - lo stato locale in SharedPreferences (modalità di tracciamento, viaggio previsto,
     stato di batteria scarica, inizio periodo offline). Tutto si ricostruisce da solo:
     modalità e viaggio sono riletti dal backend da `GeofenceSyncWorker`; il resto
     riparte da zero senza danni.
   - le zone (geofence) registrate nel sistema: `GeofenceSyncWorker` le riscarica da
     `/api/device-config` (una sincronizzazione immediata all'apertura dell'app,
     `MainActivity.kt`, poi una ogni 6 ore).
   - il token FCM: il watch ne riceve uno nuovo e lo registra da solo su
     `/api/register-watch-token`.
5. **Cosa NON ripristina il reset da solo:** i permessi (posizione, riconoscimento
   attività, notifiche, posizione in background) e l'impostazione di sistema
   "Migliora precisione" vanno riconcessi a mano.

## Cosa conservare PRIMA del reset (backup)

| Cosa | Dove sta | Perché serve | Come salvarlo |
|---|---|---|---|
| `watch-app/local.properties` (contiene `device.token`) | solo sul PC, ignorato da git (`.gitignore`) | senza il token l'APK non si autentica | copiarlo in un posto privato e fuori da git (gestore password o cartella cifrata). Non metterlo nel repository né nei documenti |
| `watch-app/app/google-services.json` | solo sul PC, ignorato da git | serve per compilare l'app (chat FCM) | copia privata; si può anche riscaricare dalla console Firebase del progetto `child-tracker-7a1f1` |
| APK già compilato con quel token (facoltativo) | `watch-app/app/build/outputs/apk/debug/` | permette di reinstallare senza ricompilare | dopo `Run`/`assembleDebug` copiare il file `.apk` in un posto privato: **contiene il token**, trattarlo come un segreto |
| Numero di telefono / EID / ICCID della eSIM | watch e area clienti dell'operatore | per riattivare la linea dati | annotarli |

Nota: nella cartella di progetto su Google Drive il file `local.properties` esiste già
(verificato il 2026-11-21), quindi una copia c'è; conviene comunque farne una seconda
fuori dalla cartella sincronizzata del repo.

## Procedura dopo il reset

1. **Riaccendere il watch, rifare la configurazione iniziale**, account Google adulto
   dedicato (quello usato per il watch), e riattivare la eSIM con l'operatore.
2. **Riattivare le opzioni sviluppatore e il debug via WiFi** (Impostazioni → Info →
   Software → toccare 7 volte "Versione software"; poi Opzioni sviluppatore → Debug ADB e
   Debug tramite WiFi), oppure installare da Play Console → Internal Testing se l'app
   è stata pubblicata lì.
3. **Installare l'app con lo stesso token**:
   - da Android Studio: aprire `watch-app/`, verificare che `local.properties` abbia il
     `device.token` originale e `backend.base.url`, poi *Run → Run 'app'*; oppure
   - con l'APK salvato: `adb connect <ip>:<porta>` poi `adb install -r app-debug.apk`.
4. **Concedere i permessi** all'apertura: posizione, riconoscimento attività, notifiche,
   poi posizione in background (passaggio separato richiesto da Android).
5. **Impostazioni → Posizione → "Migliora precisione" ATTIVA** (senza, il tracking al
   chiuso produce pochissimi punti).
6. **Verificare** dal telefono del genitore: "Aggiorna posizione" deve riportare una
   posizione nuova e la batteria del watch. Controllo veloce dei dati lato server:
   `node backend/scripts/diag-device-history.js 1` (sola lettura, vedi `CLAUDE.md`).
   Controllare anche che le zone siano tornate (si riscaricano all'apertura dell'app).

## Se il token è andato perso (caso di emergenza)

Il backend non conserva il token in chiaro, quindi **non si può rileggere**. Oggi
nel backend non esiste un'azione per rigenerarlo: `create_child` crea un bambino **nuovo**
(nuovo `childId`, nuova cronologia). Le strade possibili:

- creare un nuovo bambino con "Aggiungi bambino", usare il nuovo token, e accettare che
  la cronologia sia separata da quella vecchia;
- oppure implementare una nuova azione `rotate_child_token` in `parent-command.js`
  (il genitore autorizzato genera un nuovo token per un `childId` esistente della sua
  famiglia, il backend aggiorna `deviceTokenHash`). **Non fatto**: è una modifica
  all'autenticazione in produzione, da decidere con l'utente (vedi CONTEXT.md).

## eSIM (fuori dall'app)

La eSIM non si può copiare né ripristinare da backup: il profilo sta nel chip sicuro
(eUICC) e le chiavi non escono mai da lì. Dopo un reset che la cancella, serve un nuovo
codice di attivazione (QR) dell'operatore. Prima del reset: chiedere all'operatore se
il reset di fabbrica cancella la eSIM e farsi preparare un QR di riserva. Se il reset
non la cancella (opzione tipo "elimina eSIM" lasciata disattivata), la linea resta.

## Rollback

Documento nuovo, nessuna modifica al codice: per toglierlo basta
`git revert <commit di questo runbook>`.

## Storico modifiche

- 2026-11-21 — v1.0, creazione. Prima: nessuna procedura scritta per il reset del watch.
  Ora: punti verificati nel codice (token in BuildConfig, hash lookup in `auth.js`,
  `allowBackup=false`), elenco di cosa salvare e procedura di ripristino. Perché:
  richiesta dell'utente di poter fare un factory reset senza nuova associazione.
