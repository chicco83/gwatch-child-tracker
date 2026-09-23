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

<!-- 2026-09-24: sezione creata su richiesta dell'utente. Regola in
     CLAUDE.md: ogni nuova funzione va aggiunta qui con una spiegazione
     breve (cosa fa, come si usa, eventuali limiti). -->

### Watch (Galaxy Watch4, app Wear OS)

- **Tracking automatico della posizione** — servizio sempre attivo che
  registra la posizione e la invia al backend a gruppi (ogni 15 minuti o
  prima se ci sono molti punti). Da fermo ogni 10 minuti a priorità
  *bilanciata* (Wi-Fi e rete, poca batteria); in movimento ogni minuto
  con GPS. L'alta precisione anche da fermo si attiva dal telefono.
  Richiede sul watch *Impostazioni → Posizione → "Migliora precisione"*
  attiva (vedi `watch-app/README.md`).
- **Avvio automatico** — il tracking riparte da solo all'accensione del
  watch, dopo ogni aggiornamento dell'app e quando arriva una richiesta
  di posizione dal telefono, anche con l'app chiusa.
- **Invia posizione** — pulsante che invia subito la posizione. Diventa
  una barra blu durante il tentativo (max 90 s), verde per qualche
  secondo se l'invio riesce, rosso fisso "GPS assente, tocca per cercare"
  se non c'è segnale.
- **Ricerca GPS** — si apre dal pulsante rosso: barre dei satelliti
  (verde = usato per la posizione), conteggio visti/usati, diagnostica
  (permessi, posizione di rete, stato del tracking e da cosa è partito) e
  pulsante "Reset dati GPS" (cancella e riscarica ora ed effemeridi,
  come il reset A-GPS dei vecchi navigatori).
- **SOS** — pulsante rosso con conferma; invia la posizione ogni 30
  secondi finché il genitore non lo disattiva dal telefono; banner "SOS
  ATTIVO" sul watch.
- **Zone (geofence)** — rilevamento di ingresso/uscita dalle zone create
  sul telefono, anche con l'app chiusa; con l'opzione della zona attiva,
  "Non disturbare" automatico dentro la zona.
- **Chat** — messaggi con il genitore: risposte rapide e dettatura
  vocale, notifica all'arrivo di un messaggio.
- **Batteria** — percentuale, temperatura, carica e autonomia stimata
  inviate con ogni posizione, con ogni evento zona e anche quando il GPS
  non trova la posizione.
- **Avvisi di stato** — modalità aereo attivata/disattivata, spegnimento
  e riaccensione: avviso immediato se c'è ancora rete, altrimenti
  inviato al rientro con l'ora di inizio del periodo offline.
- **"Posizione visualizzata"** — notifica sul watch quando il genitore
  ha visto una posizione inviata dal bambino.

### Telefono (app Android del genitore)

- **Accesso** — login Google, solo per genitori autorizzati; più
  genitori per famiglia (codice invito dalle Impostazioni); famiglie
  diverse sullo stesso sistema non vedono i dati l'una dell'altra.
- **Mappa** — OpenStreetMap con un segnaposto per ogni bambino, zone
  disegnate come cerchi, interruttore "Percorso 24h" per lo storico.
- **Scheda del bambino** — ultima posizione (tempo relativo che si
  aggiorna da solo + data e ora), batteria colorata, temperatura,
  carica, velocità, autonomia residua, satelliti dell'ultimo tentativo
  (o "GPS non usato" se la posizione viene dal Wi-Fi), ultimo contatto
  senza posizione, stato del watch con icona: ✈️ modalità aereo,
  ⏻ spento, 📵 non raggiungibile (nessuna notizia da oltre 30 minuti).
- **Aggiorna posizione** — chiede la posizione al watch (anche
  automaticamente all'apertura dell'app). Barra "In attesa della
  posizione dal watch…"; se non arriva, barra "nuovo tentativo tra N s"
  e nuova richiesta, fino a 20 tentativi (circa un'ora, con l'app aperta).
- **Zone** — creazione e modifica toccando la mappa o cercando un
  indirizzo, raggio da 20 a 2000 m, notifica di ingresso e/o uscita,
  allarme sonoro all'uscita, "Non disturbare" del watch nella zona.
- **Notifiche** — SOS (allarme che suona anche in silenzioso),
  ingresso/uscita zone, messaggi, batteria scarica (10%, 5%, 2%; al 2%
  richiesta di posizione e messaggio automatico al watch), stato del
  watch (modalità aereo, spegnimento, riaccensione).
- **Chat** — con selettore del bambino se ce n'è più di uno.
- **Impostazioni** — proprio nickname, nickname dei bambini, aggiunta
  di un bambino, invito di un secondo genitore, interruttore **"Alta
  precisione da fermo"** per bambino (GPS ogni 10 minuti anche da fermo,
  più affidabile senza Wi-Fi ma consuma più batteria).

### Backend (Vercel + Firestore)

- **Endpoint** in `backend/api/` per posizioni, eventi, stato del watch,
  comandi del genitore, chat, configurazione del watch.
- **Pulizia notturna** dello storico scaduto (workflow GitHub Actions,
  ripetuto finché c'è da cancellare, ogni chiamata entro i limiti di
  Vercel).
- **Limite giornaliero di sicurezza** sulle scritture per restare nel
  piano gratuito (l'SOS ne è esente).
- **Home Assistant** (opzionale) — `/api/ha-status` con token statico
  per mostrare posizione e batteria anche in Home Assistant.
- **Diagnostica** — `node backend/scripts/diag-device-history.js`
  (sola lettura, mai coordinate stampate): stato del watch, eventi,
  posizioni con precisione, riepilogo orario (`--hourly`).
