# Review del codice — gwatch-child-tracker

Analisi completa del repository (backend Vercel/Firebase, phone-app Android,
watch-app Wear OS), eseguita il 2026-09-22 sul commit `0c29ad9`.

---

## Valutazione generale

Progetto ben curato: la documentazione per-file con "storico versioni" è
eccellente, e si vede che molti bug sono già stati cacciati sul hardware reale
(guardie di freschezza su `lastSeen`, `sendPushSafe`, migrazioni self-healing,
cap di sicurezza). L'architettura è coerente con i vincoli dei piani gratuiti
(Firebase Spark + Vercel Hobby).

Ci sono però **due problemi di sicurezza architetturali** da risolvere prima
di far usare l'app a una seconda famiglia, e un paio di bug funzionali veri.

---

## 🔴 Critico — sicurezza

### 1. Nessun controllo di ownership genitore→bambino (backend + regole)

**File:** `backend/api/parent-command.js`, `backend/firestore.rules`

Dopo `checkParentAuth()` — che verifica solo che esista il documento
`parents/{uid}` — **qualsiasi genitore autenticato può agire su qualsiasi
`childId`**: invio messaggi, richiesta posizione, conferma eventi, cambio
nickname e soprattutto **`cancel_sos`** — cioè silenziare un SOS attivo di un
bambino di un'altra famiglia. Le scritture passano per l'Admin SDK, che
bypassa le regole Firestore.

Stesso problema nelle regole: `isParent()` non dipende dal singolo device,
quindi ogni genitore può:
- **leggere** posizioni correnti, storico (12 mesi), eventi e chat di **tutti**
  i bambini (`match /devices/{deviceId}` + subcollection);
- **scrivere/cancellare le geofence di tutti** (`match /geofences/{zoneId}` è
  l'unica scrittura concessa al client, protetta solo da `isParent()`).

Per uso single-family funziona, ma è una bomba a orologeria: basta che una
seconda famiglia installi l'app (o che tu la mostri a parenti) e i dati si
scambiano. In phone-app il problema è visibile anche in UI:
`DeviceRepository.observeChildren()` fa query sull'intera collezione `devices`
e mostra quindi tutti i bambini di tutte le famiglie nel selettore.

**Fix:**
1. Aggiungere un legame esplicito `parents/{uid}.childIds: string[]`,
   popolato automaticamente da `create_child` in parent-command.js (e una
   migrazione per il bambino esistente "figlio").
2. Regole Firestore: la lettura di `devices/{deviceId}` e subcollection
   richiede `isParent()` **e** che `deviceId` sia in `parents/{uid}.childIds`
   (pattern classico: funzione helper che legge il doc parents del chiamante).
   Stessa condizione per le scritture su `geofences` (almeno: non poter
   toccare zone il cui `childIds` non include uno dei propri figli).
3. In `parent-command.js`: prima di ogni azione che porta un `childId`
   (message, request_location, cancel_sos, ack_event, set_nickname)
   verificare l'ownership lato server — le regole non proteggono le chiamate
   Admin SDK.

### 2. Topic FCM globale "parents" — push trasversali tra famiglie

**File:** `backend/api/trigger-event.js`, `backend/api/send-message.js`,
`phone-app/.../TrackerApplication.kt`, `phone-app/.../messaging/FcmService.kt`

Le notifiche (SOS, geofence, chat) partono sul topic FCM **globale**
`"parents"`, e ogni phone-app si iscrive a quel topic in
`TrackerApplication.onCreate()` — **anche prima del login**. Conseguenze:

- il telefono di qualsiasi famiglia riceve la notifica SOS/geofence/chat di
  **qualsiasi** bambino (il payload data contiene `childName`, `lat`, `lon`);
- peggio: su `sos_alarm`/`exit_alarm`, `SosAlarmService`/`ExitAlarmService`
  **partono a suonare su tutti i dispositivi iscritti**, anche di famiglie
  estranee — un allarme SOS di un bambino fa squillare i telefoni di tutti;
- in phone-app, `FcmService.handleChatMessage()` non filtra per `childId`: un
  messaggio chat di un altro watch finisce in `IncomingMessageStore` e
  compare nella schermata Chat locale.

**Fix:**
- Opzione A (consigliata): topic **per-bambino** (es. `child-<childId>`);
  la phone-app si iscrive solo ai topic dei propri figli (dopo il login,
  conoscendo i propri childIds dal punto 1).
- Opzione B: tornare agli array di token FCM per genitore, con invio filtrato
  server-side sul legame parent→child.
- Minimo sindacale (da fare comunque): filtro per `childId` in
  `FcmService.onMessageReceived()` — ignorare il messaggio se il childId non
  è tra i propri figli, prima di mostrare la notifica o avviare gli allarmi.

---

## 🟠 Bug funzionali

### 3. Race condition nell'upload posizioni (watch-app)

**File:** `watch-app/.../upload/LocationUploadWorker.kt`,
`watch-app/.../data/PendingLocationStore.kt`,
`watch-app/.../location/LocationTrackingService.kt`

Il worker gira sotto **due** nomi di lavoro unici diversi
(`location-upload-periodic` e `location-upload-oneshot`) → le due istanze
possono eseguirsi in parallelo. `peekBatch()` e `removeOldest()` sono chiamate
`@Synchronized` *separate* (la sincronizzazione protegge ogni singola chiamata,
non la sequenza peek→upload→remove). Scenario:

1. Worker A: `peekBatch()` → N punti.
2. Worker B: `peekBatch()` → gli stessi N punti.
3. Entrambi uploadano con successo.
4. Worker A: `removeOldest(N)` → ok.
5. Worker B: `removeOldest(N)` → rimuove N punti **più recenti, mai
   uploadati** → perdita dati (oppure, in variante, duplicati nello storico).

**Fix:** rendere il "claim" atomico — `removeOldest()` ritorna i punti che ha
rimosso e solo chi li ha claimati fa l'upload; oppure un unico nome di lavoro
unico con policy REPLACE così oneshot e periodico non corrono mai insieme.

### 4. Migrazione geofence sovrascrive `childIds`

**File:** `backend/api/device-config.js` — `ensureGeofencesMigrated()`

La migrazione dalla vecchia subcollection scrive
`{ ...d.data(), childIds: [childId] }` con merge: se la zona esiste già nella
collezione radice con altri id (es. già migrata per un altro bambino, o due
subcollection legacy che condividessero un id), il ri-esecuzione della
migrazione **stacca la zona dai bambini precedenti**. Usare
`FieldValue.arrayUnion(childId)` invece del replacement.

### 5. Quota per-device vs quota gratuita *per progetto*

**File:** `backend/api/_lib/quota.js`, `backend/api/ingest-location.js`

La guardia conta **1 chiamata** a `ingest-location`, ma quella scrive
**N documenti** (fino a 100 punti = fino a 100 scritture Firestore). Le quote
gratuite Spark (20.000 scritture/giorno) sono condivise da **tutto il
progetto**, non per dispositivo: con N bambini × 5.000 chiamate/giorno + i
punti posizione (un bambino in movimento che uploada ogni minuto ≈ 1.440
scritture/giorno solo di locations), con 3-4 bambini si può superare la soglia
gratuita e finire in fatturazione/throttling — esattamente ciò che la guardia
doveva evitare. Contare i *punti* (e le scritture effettive) nel contatore di
quota, non le chiamate.

---

## 🟡 Minori / robustezza

| # | Dove | Problema |
|---|------|----------|
| 6 | `backend/vercel.json` | `maxDuration: 30` per tutte le funzioni: `cleanup.js` fa fino a 10 cicli query+commit di 500 doc — se supera i 30s la funzione muore, il cron GitHub fallisce con 500 e la pulizia resta parziale. Valutare `maxDuration` più alto solo per `api/cleanup`. |
| 7 | `backend/api/ingest-location.js` | `received: points.length` conta anche i punti invalidi saltati dal `continue`; nessun range-check su lat/lon (es. `\|lat\| ≤ 90`, `\|lon\| ≤ 180`). |
| 8 | `backend/api/ha-status.js` | Il consume di quota avviene prima del check esistenza del device: un polling HA verso un childId inesistente consuma quota e poi risponde 404. Spostare il get del doc prima della quota. |
| 9 | phone-app `FcmService.postNotification()` | ID notifica = `System.currentTimeMillis().toInt()`: due notifiche arrivate nello stesso istante si sovrascrivono. Usare un contatore incrementale (`AtomicInteger`). |
| 10 | watch-app `network/BackendClient.kt` | Ogni worker fa `BackendClient()` → nuovo `OkHttpClient` (e pool di connessioni) a ogni chiamata. Su LTE del watch spreci socket e batteria: rendere il client singleton (companion object o iniettato). |
| 11 | backend handler vari | `validateConfig()` è chiamato solo in 3 endpoint su 9 (ingest-location, register-watch-token, sos-heartbeat); negli altri manca. Unificarlo (es. dentro `wrapHandler`), così una config rotta fallisce dappertutto allo stesso modo. |
| 12 | watch-app `sos/SosWorker.kt` | `Result.retry()` con backoff di default sul fix GPS fallito: per l'SOS (funzione più critica dell'app) valutare un retry più aggressivo/espedito. |
| 13 | watch-app `geofence/GeofenceSyncWorker.kt` | Rimuove e ri-registra tutte le geofence a ogni sync: una transizione pendente tra remove/add va persa. Accettabile (documentato come scelta deliberata), ma tenerlo presente se i falsi allarmi "spariscono" o non arrivano. |

---

## ✅ Punti di forza

- **Design difensivo coerente**: quota giornaliera con margine volutamente
  basso, SOS esente da quota (mai bloccare la funzione di sicurezza),
  `sendPushSafe` che non fa mai fallire la richiesta del genitore per un token
  FCM rotto (e auto-pulisce il token `registration-token-not-registered`),
  guardie di freschezza su `lastSeen` (nessuna regressione temporale, fix
  `77ec5b0`), safety cap a 3 ore sul loop SOS, buffer locale con tetto a 500
  punti.
- **Migrazioni self-healing** ben pensate e documentate: token legacy
  (hash salvato al volo in `_lib/auth.js`), geofence legacy (re-tentata a ogni
  chiamata dopo il fix v0.5.0).
- **Auth solida per come è strutturata**: device token mai in chiaro su
  Firestore (solo hash SHA-256), confronto costant-time
  (`crypto.timingSafeEqual`), fail-closed sui token statici mancanti,
  `parents/{uid}` creabile solo da admin.
- **Separazione delle responsabilità lato watch** sensata: buffer locale +
  worker di upload, service foreground dedicato per il SOS a 30", geofence
  delegate all'OS (risparmio batteria), DND gestito localmente sulla risposta
  della stessa chiamata trigger-event.
- **Testing**: esiste (`node --test`, `backend/test/`) ma copre solo i due
  helper (auth, quota) — nessun test sugli handler e nessun job CI che esegue
  `npm test` (il workflow GitHub fa solo il cleanup cron). Aggiungere un job
  di test su push/PR.
- I commenti con storico versioni per file sono una miniera per chi riprende
  il progetto; la disciplina CONTEXT.md/CHANGELOG.md/README è buona (vedi però
  igiene sotto).

---

## 🧹 Igiene / documentazione

- **`CHANGELOG.md`**: contiene testo placeholder generico ("Description of the
  new feature added in this release...") e un'entry `[0.33.1]` vuota accanto a
  una `[0.62.0]` — pulirli, altrimenti il changelog perde credibilità rispetto
  al resto della documentazione.
- **`CONTEXT.md`** a ~108 KB: valutare di spezzarlo per componente
  (backend/watch/phone) — ora è l'unico file da leggere per capire qualsiasi
  cosa e crescerà ancora.
- `IMPROVEMENT_PLAN.md`: piano generico tipo template (timeline a settimane su
  punti vaghi), poco utile rispetto a quanto sopra; o allinearlo a queste
  priorità o rimuoverlo.

---

## Priorità consigliate

1. **Ownership genitore→bambino** (punto 1) — regole Firestore + verifica in
   parent-command.js + `parents/{uid}.childIds` popolato da create_child.
2. **Topic FCM per-bambino + filtro childId lato phone** (punto 2).
3. **Race upload worker** (punto 3) — perdita dati reale, fix piccolo
   (~20 righe in PendingLocationStore/LocationUploadWorker).
4. **Conteggio punti nella quota** (punto 5) e `maxDuration` per cleanup
   (punto 6).
5. Fix minori della tabella 🟡 quando c'è un giro di lavoro.
6. Job CI `npm test` + pulizie documentation.
