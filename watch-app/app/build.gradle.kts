import java.util.Properties

// Storico versioni (firma release/Play Console)
// v0.1.0 (2026-09-10): aggiunta signingConfig "release", letta da
//   local.properties (release.storeFile/storePassword/keyAlias/
//   keyPassword) come gia' fatto per device.token/backend.base.url
//   sotto — mai un keystore/password nel sorgente. Necessaria per
//   generare un Android App Bundle (.aab) firmato da caricare su Play
//   Console → Internal Testing (pubblicazione privata, vedi
//   CONTEXT.md, "Distribuzione app"). Se local.properties non ha
//   queste chiavi (caso normale per un build di sviluppo), la
//   signingConfig "release" semplicemente non viene creata e
//   buildTypes.release resta senza firma, come prima.

// Legge device.token e backend.base.url da local.properties (mai
// committato, vedi ../local.properties.example) per iniettarli come
// BuildConfig: cosi' il DEVICE_TOKEN non finisce mai nel sorgente/repo.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val deviceToken: String = localProps.getProperty("device.token", "")
val backendBaseUrl: String =
    localProps.getProperty("backend.base.url", "https://gwatch-child-tracker.vercel.app")

// Keystore di release (Play Console), opzionale: vedi Storico versioni
// sopra. Path relativo alla root del progetto watch-app/ (o assoluto).
val releaseStoreFile: String = localProps.getProperty("release.storeFile", "")
val releaseStorePassword: String = localProps.getProperty("release.storePassword", "")
val releaseKeyAlias: String = localProps.getProperty("release.keyAlias", "")
val releaseKeyPassword: String = localProps.getProperty("release.keyPassword", "")
val hasReleaseSigning: Boolean = releaseStoreFile.isNotBlank()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // v0.2.0 (2026-09-10): chat via push FCM invece di polling, per
    // consumo batteria (vedi CONTEXT.md, log decisioni)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.gwatch.childtracker"
    // SDK 35, requisito Play Store per i nuovi upload (vedi phone-app).
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gwatch.childtracker"
        minSdk = 30 // Wear OS 3 (Galaxy Watch4 e successivi)
        targetSdk = 35
        // 2026-09-18: versionCode/versionName erano fermi a 2/"0.2.0" da
        // decine di commit — mai piu' incrementati dopo il primo bump.
        // Utente non riusciva a verificare da Impostazioni watch se
        // l'app appena compilata fosse davvero quella installata (utile
        // ora che stiamo diagnosticando un bug "invio silenzioso": senza
        // un numero che cambia, un vecchio APK non reinstallato per
        // errore sarebbe indistinguibile da uno aggiornato). Bump ad
        // ogni release d'ora in poi.
        // v0.5.0 (2026-09-18): GeofenceSyncWorker one-shot ora usa
        // ExistingWorkPolicy.REPLACE invece di KEEP (vedi MainActivity.kt)
        // — riapre l'app forza sempre una sync fresca delle zone.
        // v0.6.0 (2026-09-18): BatteryInfo.kt (temperatura batteria +
        // stato di carica, centralizzato al posto di 4 copie duplicate
        // di currentBatteryPercent()).
        // v0.7.0 (2026-09-18): aggiunta velocita' (Location.getSpeed())
        // ai payload posizione, richiesta dall'utente per mostrarla
        // sulla mappa della phone-app.
        // v0.8.0 (2026-09-18): fix banner "SOS inviato"/"posizione
        // inviata" che ricompariva ad ogni avvio dell'app senza un
        // invio nuovo davvero (vedi MainActivity.kt, observeWorkOutcomes).
        // v0.9.0 (2026-09-18): DND automatico per zona, richiesto
        // dall'utente — nuovo dnd/DndController.kt, GeofenceEventWorker
        // applica il cambio letto dalla risposta di trigger-event.js,
        // nuovo permesso ACCESS_NOTIFICATION_POLICY (concesso a mano
        // dall'utente, non a runtime).
        // v0.10.0 (2026-09-19): autonomia residua batteria in ore,
        // richiesta dall'utente — chiesta al sistema operativo invece
        // che stimata da uno storico (vedi location/BatteryInfo.kt,
        // readHoursRemaining/BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
        // + CURRENT_NOW), inviata al backend su ogni punto posizione/
        // evento insieme al resto dello stato batteria.
        // v0.11.0 (2026-09-23): bug trovato diagnosticando una
        // segnalazione dell'utente (evento geofence con orario
        // sbagliato se il primo invio falliva e veniva ritentato piu'
        // tardi, vedi geofence/GeofenceBroadcastReceiver.kt/
        // GeofenceEventWorker.kt).
        // v0.12.0 (2026-09-23): Fase 3 di qwen_plan.md (individuato da
        // qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — race
        // condition reale nell'upload posizioni: due esecuzioni
        // concorrenti di LocationUploadWorker (periodico + one-shot,
        // nomi di lavoro WorkManager distinti) potevano uploadare lo
        // stesso batch e la seconda rimuovere punti piu' recenti mai
        // uploadati. Vedi data/PendingLocationStore.kt v0.2.0
        // (claimBatch/requeue, lock condiviso a livello di companion
        // object) e upload/LocationUploadWorker.kt v0.2.0.
        // v0.13.0 (2026-09-23): Fase 4 di qwen_plan.md (individuato da
        // qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — ogni worker
        // creava un nuovo OkHttpClient ad ogni chiamata (pool di
        // connessioni sprecato su LTE), vedi network/BackendClient.kt
        // v0.6.0: ora un solo client condiviso per l'intero processo.
        // Verificato anche (nessuna modifica necessaria): sos/SosWorker.kt
        // e' gia' accodato come lavoro espedito (setExpedited) — il
        // retry aggressivo suggerito dal piano per l'SOS era gia' in
        // vigore prima di questo giro; geofence/GeofenceSyncWorker.kt
        // (remove+re-add ad ogni sync) resta una scelta deliberata gia'
        // documentata, non un bug da correggere.
        // v0.14.0 (2026-09-23): pulsante "Invia posizione" mai piu'
        // bloccato con GPS assente, vedi ui/MainActivity.kt.
        // v0.15.0 (2026-09-23): schermata "Ricerca GPS" con barre dei
        // satelliti, vedi ui/GpsSearchScreen.kt.
        // v0.16.0 (2026-09-23): Ricerca GPS con diagnostica a schermo e
        // recupero da getLastKnownLocation, vedi ui/GpsSearchScreen.kt 0.2.0.
        // v0.17.0 (2026-09-23): diagnostica permessi/AppOps/mock nella
        // Ricerca GPS, vedi ui/GpsSearchScreen.kt 0.3.0.
        // v0.18.0 (2026-09-23): iniezione ora/effemeridi (location/GpsAssist.kt),
        // durata esplicita del fix in LocationRequestWorker/SosWorker, Ricerca
        // GPS anche via servizi Google, pulsante "Invio posizione in corso…".
        // v0.19.0 (2026-09-23): pulsante "Reset dati GPS" nella Ricerca GPS
        // (location/GpsAssist.kt resetAidingData).
        // v0.20.0 (2026-09-23): tracking automatico ad alta precisione anche
        // da fermo (LocationTrackingService), log + stato del tracking
        // (location/TrackingStatus.kt) visibile nella Ricerca GPS.
        // v0.21.0 (2026-09-23): riga "Posizione di rete (Migliora precisione)"
        // nella diagnostica della Ricerca GPS.
        // v0.22.0 (2026-09-23): se il GPS non da' un fix, LocationRequestWorker
        // invia comunque lo stato batteria (BackendClient.sendStatus).
        // v0.23.0 (2026-09-23): satelliti visti/agganciati inviati col
        // tentativo di posizione (location/GnssCounter.kt).
        // v0.24.0 (2026-09-23): pulsante "Invia posizione" a stati colorati
        // (barra di progresso / verde inviato / rosso GPS assente).
        // v0.25.0 (2026-09-23): batteria anche sugli eventi zona; segnala se la
        // posizione e' arrivata senza GPS (gnssActive).
        // v0.26.0 (2026-09-23): tracking riavviato anche dopo ogni
        // aggiornamento dell'app (BootReceiver + MY_PACKAGE_REPLACED).
        // v0.27.0 (2026-09-23): richiesta posizione dal telefono come lavoro
        // espedito + riavvio del tracking se fermo (messaging/FcmService.kt).
        // v0.28.0 (2026-09-23): avvisi modalita' aereo/spegnimento/riaccensione
        // (location/WatchStateReporter.kt).
        // v0.29.0 (2026-09-24): tracking da fermo di nuovo bilanciato, alta
        // precisione attivabile dalla phone-app (location/TrackingMode.kt);
        // la sync zone non cancella piu' le zone se la rete manca.
        // v0.30.0 (2026-09-24): stato del caricatore inviato subito al
        // collegamento/scollegamento (location/WatchStateReporter.kt v0.2.0),
        // per l'icona "in carica" sulla phone-app.
        // v0.31.0 (2026-09-24): autonomia stimata che non compariva mai
        // (location/BatteryInfo.kt v0.11.0: unita'/segno della corrente
        // normalizzati + stima dall'andamento della percentuale).
        // v0.32.0 (2026-09-24): autonomia chiesta solo a Wear OS
        // (PowerManager.getBatteryDischargePrediction), niente stime nostre
        // (location/BatteryInfo.kt v0.12.0, richiesta utente).
        // v0.33.0 (2026-09-24): niente piu' falso "watch riacceso" a ogni
        // Run da Android Studio (location/WatchStateReporter.kt v0.3.0).
        // v0.34.0 (2026-09-24): posizione su richiesta con GPS acceso davvero
        // (niente cache, attesa fino a 30 s di un fix migliore;
        // location/LocationRequestWorker.kt acquireBestLocation).
        versionCode = 34
        versionName = "0.34.0"

        buildConfigField("String", "DEVICE_TOKEN", "\"$deviceToken\"")
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Wear OS Compose
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.wear:wear:1.3.0")
    // v0.2.1 (2026-09-10): androidx.wear.compose:compose-foundation
    // porta transitivamente una versione di foundation-layout piu'
    // vecchia di ui:1.6.8, dove ColumnScope/RowScope.weight risultava
    // "internal" (errore di build in ChatScreen.kt). Fissata la stessa
    // versione di ui per allineare tutto lo stack Compose.
    implementation("androidx.compose.foundation:foundation:1.6.8")

    // Location, Activity Recognition, Geofencing
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Lavoro in background programmato (sync geofence, upload posizioni)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Client HTTP verso il backend (vedi network/)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Solo per la push della chat (v0.2.0): niente Firestore/Auth qui,
    // il watch continua a parlare col backend solo via BackendClient
    // (OkHttp) — stessa scelta "meno pezzi in movimento" di sempre.
    // Stesso BOM version di phone-app, per coerenza.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
