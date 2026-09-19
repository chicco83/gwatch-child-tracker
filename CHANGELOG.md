# Changelog

Tutte le modifiche rilevanti e aggiornamenti del progetto sono documentati qui.

## [0.33.1] - 2026-09-19
### Added
- **Rilascio di versione 0.33.1**: Aggiunta notifica "Posizione visualizzata" sul watch quando il genitore vede sulla phone-app una posizione inviata volontariamente dal bambino (SOS o "Invia posizione"): nuovo endpoint `backend/api/ack-event.js` (idempotente), chiamato da `MapScreen.kt` quando mostra un evento non ancora marcato "acknowledged".