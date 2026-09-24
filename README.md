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
/backend      Funzioni Vercel (api/), regole/indici Firestore, script
              (scripts/: migrazione familyId, diagnostica storico)
.github/      Workflow: pulizia notturna storico, test backend
```

<!-- 2026-09-24: prima diceva "/backend Firebase (Firestore rules, Cloud
     Functions)", non piu' vero dalla migrazione a Vercel (v0.6.0). -->

## Stack

- Watch: Wear OS (Kotlin), Google Play Services (FusedLocationProvider,
  Geofencing API, ActivityRecognitionClient), chat/comandi remoti via
  push FCM.
- Phone: Android (Kotlin/Compose), mappa **OpenStreetMap (osmdroid)** —
  non Google Maps SDK, sostituito in v0.17.0 per evitare una
  fatturazione Google Cloud permanentemente attiva (vedi CONTEXT.md).
- Backend: Firebase Spark (Firestore, FCM) + **Vercel Functions**
  (**Node.js 24**/`firebase-admin`; Node 20 dismesso da Vercel dal
  01/10/2026) al posto delle Cloud Functions di
  Firebase, che richiederebbero il piano Blaze — migrato in v0.6.0
  (vedi CONTEXT.md).
- Distribuzione: ADB via WiFi in sviluppo, Play Console Internal
  Testing per il watch in uso

## Funzioni

<!-- 2026-09-24: sezione creata su richiesta dell'utente; formato
     aggiornato lo stesso giorno (nome in elenco puntato + 2-3 righe di
     dettaglio). Regola in CLAUDE.md: ogni nuova funzione va aggiunta qui
     in questo formato. -->

### Watch (Galaxy Watch4, app Wear OS)

- **Tracking automatico della posizione**

  Registra la posizione e la invia al backend a gruppi, ogni 15 minuti
  o prima se ci sono molti punti. Da fermo ogni 10 minuti usando Wi-Fi
  e rete (poca batteria), in movimento ogni minuto con il GPS.
  Richiede sul watch "Migliora precisione" attiva (Impostazioni → Posizione).

- **Avvio automatico**

  Il tracking riparte da solo all'accensione del watch, dopo ogni
  aggiornamento dell'app e quando arriva una richiesta di posizione dal
  telefono, anche con l'app chiusa (ma non dopo un "Arresto forzato").

- **Invia posizione**

  Pulsante che invia subito la posizione al telefono. Accende davvero il
  GPS (niente posizione in cache) e aspetta fino a 30 s un fix sotto i
  20 m; al chiuso, se il GPS non aggancia, invia la migliore ottenuta. Durante il
  tentativo diventa una barra blu (max 90 s), poi verde per qualche
  secondo se l'invio riesce, rosso fisso "GPS assente" se non c'è segnale.

- **Ricerca GPS**

  Si apre toccando il pulsante rosso "GPS assente". Mostra una barra per
  satellite (verde = usato per la posizione), i satelliti visti/usati e
  una diagnostica; il pulsante "Reset dati GPS" riscarica ora ed effemeridi.

- **SOS**

  Pulsante rosso con richiesta di conferma, per evitare attivazioni per
  sbaglio. Invia la posizione ogni 30 secondi finché il genitore non lo
  disattiva dal telefono; sul watch compare il banner "SOS ATTIVO".

- **Zone (geofence)**

  Rileva ingresso e uscita dalle zone create sul telefono, anche con
  l'app chiusa. Se la zona lo prevede, attiva il "Non disturbare" del
  watch all'ingresso e lo disattiva all'uscita.

- **Chat**

  Messaggi con il genitore tramite risposte rapide o dettatura vocale,
  senza tastiera. I messaggi in arrivo mostrano una notifica sul watch.

- **Batteria**

  Percentuale, temperatura, stato di carica e autonomia stimata vengono
  inviati con ogni posizione, con ogni evento zona e anche quando il GPS
  non trova la posizione, così sul telefono restano sempre aggiornati.
  Collegando o scollegando il caricatore lo stato parte subito (appena
  c'è rete), senza aspettare la posizione successiva.
  L'autonomia è la previsione di Wear OS (la stessa delle impostazioni
  batteria), non calcolata dall'app: se il sistema non ne fornisce una
  (o su Wear OS 3) la riga "Autonomia" sul telefono non compare.

- **Avvisi di stato del watch**

  Segnala al telefono modalità aereo attivata/disattivata, spegnimento e
  riaccensione. L'avviso immediato parte solo se c'è ancora rete; al
  rientro arriva comunque, con l'ora di inizio del periodo offline.
  La riaccensione si segnala solo per un vero riavvio del watch, non
  quando l'app viene riaperta dopo un arresto forzato.

- **"Posizione visualizzata"**

  Quando il genitore apre sul telefono una posizione inviata dal
  bambino (SOS o "Invia posizione"), sul watch arriva una notifica.

### Telefono (app Android del genitore)

- **Accesso e famiglie**

  Login con l'account Google, solo per i genitori autorizzati. Più
  genitori per famiglia (codice invito dalle Impostazioni); famiglie
  diverse sullo stesso sistema non vedono i dati l'una dell'altra.

- **Mappa**

  Mappa OpenStreetMap con un segnaposto per ogni bambino e le zone
  disegnate come cerchi. L'interruttore "Percorso 24h" mostra il
  tragitto delle ultime 24 ore.

- **Scheda del bambino**

  Ultima posizione (tempo che si aggiorna da solo, più data e ora),
  batteria colorata, temperatura, velocità, autonomia e satelliti
  dell'ultimo tentativo ("GPS non usato" se la posizione viene dal Wi-Fi).

- **Stato del watch**

  Icona accanto al nome: ✈️ modalità aereo, ⏻ spento, 📵 non
  raggiungibile (nessuna notizia da oltre 30 minuti), 🔌 in carica
  (mostrata solo se non c'è uno degli stati precedenti). Sotto, la riga
  "Stato watch" dice da quando.

- **Aggiorna posizione**

  Chiede la posizione al watch, anche in automatico all'apertura
  dell'app. Mostra "In attesa della posizione dal watch…" e, se non
  arriva, "nuovo tentativo tra N s": riprova fino a 20 volte (circa un'ora).

- **Zone**

  Si creano toccando la mappa o cercando un indirizzo, con raggio da 20
  a 2000 m. Per ogni zona: notifica di ingresso e/o uscita, allarme
  sonoro all'uscita, "Non disturbare" del watch dentro la zona.

- **Notifiche**

  SOS con allarme che suona anche in silenzioso, ingresso/uscita zone,
  messaggi, batteria scarica (10%, 5%, 2%: al 2% anche richiesta di
  posizione e messaggio al watch) e stato del watch.

- **Chat**

  Messaggi con il watch; se ci sono più bambini, un selettore permette
  di scegliere a chi scrivere.

- **Impostazioni**

  Proprio nickname, nickname dei bambini, aggiunta di un bambino e
  invito di un secondo genitore. Per ogni bambino, l'interruttore "Alta
  precisione da fermo": GPS ogni 10 minuti anche da fermo, più batteria.

### Backend (Vercel + Firestore)

- **Endpoint**

  Funzioni in `backend/api/` per posizioni, eventi, stato del watch,
  comandi del genitore, chat e configurazione del watch. Ogni chiamata
  è autenticata (token del watch o login del genitore).

- **Pulizia notturna**

  Un workflow GitHub Actions cancella ogni notte lo storico scaduto.
  Ogni chiamata resta entro i limiti di tempo di Vercel e viene ripetuta
  finché non c'è più niente da cancellare.

- **Limite giornaliero di sicurezza**

  Tetto alle scritture per dispositivo, per restare nel piano gratuito
  di Firebase. L'SOS ne è esente.

- **Home Assistant (opzionale)**

  L'endpoint `/api/ha-status`, protetto da token, espone posizione e
  batteria per mostrarle anche in Home Assistant. L'app funziona
  comunque anche senza.

- **Diagnostica**

  `node backend/scripts/diag-device-history.js` (sola lettura, mai
  coordinate stampate) mostra stato del watch, eventi, posizioni con
  precisione e, con `--hourly`, il riepilogo orario del tracking.
