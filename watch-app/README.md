# watch-app

App Wear OS (Kotlin) installata sul Galaxy Watch4 LTE del figlio.

**Stato:** non ancora implementata (scaffolding).

## Responsabilità (scope MVP)

- Lettura posizione con sampling adattivo (`FusedLocationProviderClient`,
  intervallo variabile in base al movimento rilevato via
  `ActivityRecognitionClient`).
- Geofence casa/scuola via Android Geofencing API (gestita a livello OS,
  nessun polling continuo).
- Invio dati al backend Firebase in batch (via `WorkManager`, non un
  network call per ogni punto).
- Pulsante/tile SOS: fix immediato ad alta precisione + invio
  prioritario al backend.
- Foreground service con notifica silenziosa (richiesto per background
  location su Wear OS).

## Note

Vedi [`../CONTEXT.md`](../CONTEXT.md) per architettura completa e
[`../CHANGELOG.md`](../CHANGELOG.md) per lo storico.
