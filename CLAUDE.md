# Istruzioni per Claude Code

## Documentazione da mantenere sempre aggiornata

Prima di ogni compattazione della conversazione (context compaction) e
comunque dopo ogni blocco di modifiche significativo (nuova feature,
bugfix trovato su hardware reale, cambio architetturale), aggiorna
questi tre file perché riflettano lo stato reale del progetto:

- **`CONTEXT.md`** — stato architetturale, decisioni prese e perché,
  backlog aperto/chiuso. Bump del numero di versione in cima al file
  + nuova voce nel "Log decisioni" in fondo.
- **`CHANGELOG.md`** — nuova voce per la modifica (formato Keep a
  Changelog già in uso nel file: Added/Changed/Fixed/Known
  limitations).
- **`README.md`** (root) — solo se la modifica cambia lo stack, la
  struttura del repo o le istruzioni d'uso descritte lì. Controllarlo
  comunque ad ogni giro: è rimasto disallineato per diverse versioni
  in passato (parlava ancora di Google Maps SDK e Cloud Functions
  molto dopo la migrazione a osmdroid/Vercel).
  **Regola aggiunta il 2026-09-24 (richiesta utente):** ogni **nuova
  funzione** (o modifica visibile di una esistente) va scritta nella
  sezione **"Funzioni"** del README.md, sotto Watch / Telefono /
  Backend, con una spiegazione breve: cosa fa, come si usa, limiti
  principali. Nello stesso commit della funzione, non "dopo".

Motivo: un riassunto automatico di conversazione perde i dettagli fini
(bug trovati durante il test, decisioni prese lì per lì) — questi tre
file sono l'unica fonte affidabile per chi riprende il progetto dopo
una compattazione o in una sessione futura, compreso lo stesso Claude.

## Diagnostica dati reali (autorizzata dall'utente, 2026-09-23)

Per datare un problema sul campo (posizioni/eventi zona che smettono di
arrivare) usare `node backend/scripts/diag-device-history.js [giorni]`:
sola lettura, stampa solo date/conteggi/tipi di evento/precisione/
distanze, mai coordinate (le usa solo per calcolare le distanze, con
`--track`/`--hourly`; l'utente ha autorizzato la lettura delle posizioni
il 2026-09-23). `--hourly` e' il controllo piu' rapido per vedere se il
tracking automatico e' continuo (~10 punti/ora da fermo, di piu' in
movimento).
Permesso in `.claude/settings.json`. Non scrivere altri script che
leggono dati di produzione senza chiederlo all'utente.
