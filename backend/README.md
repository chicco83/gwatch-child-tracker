# backend

Backend Firebase (piano gratuito Spark) condiviso da watch-app e
phone-app.

**Stato:** non ancora implementato (scaffolding).

## Responsabilità (scope MVP)

- Firestore: stato corrente del dispositivo (ultima posizione, batteria,
  timestamp), configurazione geofence, storico posizioni (retention
  breve, 24-48h in MVP).
- Cloud Functions: trigger su nuovo evento SOS → invio push FCM al
  genitore.
- Firebase Cloud Messaging: notifiche push (ingresso/uscita geofence,
  SOS).
- Regole di sicurezza Firestore: accesso limitato al genitore
  autenticato e al device token del watch.

## Note

Vedi [`../CONTEXT.md`](../CONTEXT.md) per architettura completa e
[`../CHANGELOG.md`](../CHANGELOG.md) per lo storico.
