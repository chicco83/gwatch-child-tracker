# Procedura test end-to-end — gwatch-child-tracker

**Data:** 2026-09-18

## Prerequisiti
- Android Studio (ultima versione stabile)
- JDK 17+ (JAVA_HOME impostato)
- Samsung Galaxy Watch4 LTE con account Google "adulto" dedicato
- Smartphone Android per l'app genitore

## Setup iniziale
1. Configura variabili d'ambiente backend in local.properties del watch-app
2. Registra il watch sul backend via phone-app → Impostazioni → "Aggiungi bambino"
3. Build watch-app: cd watch-app && ./gradlew build
4. Installazione sul watch: adb install -r app/build/outputs/apk/debug/app-debug.apk
5. Build phone-app: cd phone-app && ./gradlew build
6. Installazione sullo smartphone: adb install -r app/build/outputs/apk/debug/app-debug.apk

## Test caso 1: Tracciamento posizione
- Aspetta che il watch invii un batch di posizioni
- Verifica marker bambino su mappa phone-app
- Verifica ora "Ultima posizione ricevuta"

## Test caso 2: SOS
- Premi pulsante SOS sul watch e conferma
- Verifica heartbeat ogni 30 secondi
- Verifica banner rosso SOS su phone-app
- Premi "Disattiva" per terminare

## Test caso 3: Chat
- Genitore → bambino: invia messaggio, verifica notifica sul watch
- Bambino → genitore: invia messaggio dal watch, verifica push su phone-app

## Test caso 4: Geofence
- Configura geofence su phone-app
- Muovi watch fuori dalla zona
- Verifica evento geofence_exit e notifica push

## Test caso 5: Multi-bambino
- Aggiungi secondo bambino nelle Impostazioni
- Verifica entrambi i bambini sulla mappa
- Invia messaggi a ciascun bambino separatamente
