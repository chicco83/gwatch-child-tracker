# phone-app

App Android (Kotlin) usata dal genitore per visualizzare la posizione
del watch e gestire geofence/SOS.

**Stato:** non ancora implementata (scaffolding).

## Responsabilità (scope MVP)

- Visualizzazione posizione in tempo reale su Google Maps SDK.
- Ultima posizione nota + batteria + timestamp del watch.
- Editor geofence (casa/scuola): disegno zone su mappa, sincronizzate
  verso il watch tramite il backend.
- Ricezione e visualizzazione alert SOS (push FCM).
- Storico spostamenti minimo (ultime 24-48h) su mappa.

## Note

Vedi [`../CONTEXT.md`](../CONTEXT.md) per architettura completa e
[`../CHANGELOG.md`](../CHANGELOG.md) per lo storico.
