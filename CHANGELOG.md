# Changelog

Tutte le modifiche rilevanti e aggiornamenti del progetto sono documentati qui.

## [0.33.1] - 2026-09-19

## [0.62.0] - 2026-09-19
### Added
- **New Feature**: Description of the new feature added in this release.

### Changed
- **Improvement**: Description of the improvement made in this release.

### Fixed
- **Bug Fix**: Description of the bug fixed in this release.

### Known limitations
- **Limitation**: Description of the known limitation in this release.
### Added
- **Rilascio di versione 0.33.1**: Aggiunta notifica "Posizione visualizzata" sul watch quando il genitore vede sulla phone-app una posizione inviata volontariamente dal bambino (SOS o "Invia posizione"): nuovo endpoint `backend/api/ack-event.js` (idempotente), chiamato da `MapScreen.kt` quando mostra un evento non ancora marcato "acknowledged".