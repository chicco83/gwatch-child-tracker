import java.util.Properties

// Storico versioni
// v0.1.0 (2026-09-10): aggiunta signingConfig "release", letta da
//   local.properties (mai committato — stesso pattern gia' in uso in
//   watch-app/app/build.gradle.kts per device.token/backend.base.url):
//   release.storeFile/storePassword/keyAlias/keyPassword. Necessaria
//   per generare un Android App Bundle (.aab) firmato da caricare su
//   Play Console → Internal Testing (vedi CONTEXT.md, "Distribuzione
//   app"). Se le chiavi non sono presenti (caso normale per un build
//   di sviluppo), la signingConfig "release" non viene creata e
//   buildTypes.release resta senza firma, come prima — nessun impatto
//   sui build di debug quotidiani in Android Studio.
// v0.2.0 (2026-09-11): aggiunta la dipendenza
//   "androidx.compose.material:material-icons-core" (vedi dependencies
//   sotto) — serve l'icona hamburger (Icons.Filled.Menu) nel nuovo menu
//   di MapScreen.kt, non inclusa di default in material3.
// v0.9.0 (2026-09-18): bug di build segnalato dall'utente — "Unresolved
//   reference: BuildConfig" in MapScreen.kt, dove si mostra il numero di
//   versione (BuildConfig.VERSION_NAME) accanto al nome app. Causa: con
//   AGP 8+ la generazione della classe BuildConfig NON e' piu' implicita,
//   va abilitata esplicitamente con buildFeatures.buildConfig = true
//   (vedi buildFeatures sotto) — nel watch-app era gia' presente perche'
//   serviva da prima per DEVICE_TOKEN/BACKEND_BASE_URL, qui invece non
//   era mai stato necessario finche' non si e' iniziato a leggere
//   BuildConfig.VERSION_NAME.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile: String = localProps.getProperty("release.storeFile", "")
val releaseStorePassword: String = localProps.getProperty("release.storePassword", "")
val releaseKeyAlias: String = localProps.getProperty("release.keyAlias", "")
val releaseKeyPassword: String = localProps.getProperty("release.keyPassword", "")
val hasReleaseSigning: Boolean = releaseStoreFile.isNotBlank()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.gwatch.childtracker.phone"
    // SDK 35, requisito Play Store per i nuovi upload (il 34 non
    //   e' piu' accettato). Edge-to-edge gestito in MainActivity.enableEdgeToEdge().
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gwatch.childtracker.phone"
        minSdk = 26
        targetSdk = 35
        // 2026-09-18: stesso problema segnalato sul watch-app — versionCode/
        // versionName fermi a 2/"0.2.0" da decine di commit, mai
        // incrementati dopo il primo bump. Bump ad ogni release d'ora in poi.
        // v0.4.0 (2026-09-18): fix buildConfig=true + fix altezza
        // StatusCard (vedi Storico versioni sopra e MapScreen.kt).
        // v0.5.0 (2026-09-18): StatusCard a tutta larghezza/riga singola,
        // colore solo sul valore batteria; allarme SOS che bypassa il
        // silenzioso/DND (vedi alarm/SosAlarmService.kt).
        // v0.6.0 (2026-09-18): mostra temperatura batteria/stato di
        // carica nella StatusCard (vedi MapScreen.kt/Models.kt).
        // v0.7.0 (2026-09-18): mostra la velocita' (km/h) in StatusCard,
        // vedi MapScreen.kt.
        // v0.8.0 (2026-09-18): fix pulsante "Aggiorna posizione" schiacciato
        // in StatusCard (vedi MapScreen.kt); titolo notifica chat ora
        // "Messaggio da {nickname}" invece del generico "dal watch" (vedi
        // FcmService.kt).
        // v0.9.0 (2026-09-18): GeofenceScreen — campo ricerca indirizzo
        // compattato con lente dentro il campo (niente piu' label +
        // pulsante "Cerca" separato), righe zona piu' compatte con
        // scrollbar per far capire che ce ne sono altre, fix switch
        // "Allarme sonoro all'uscita" disallineato/tagliato (vedi
        // GeofenceScreen.kt).
        // v0.10.0 (2026-09-18): StatusCard — un'informazione per riga
        // (ultima posizione/batteria/temperatura/velocita'), valore in
        // grassetto, tolta la soglia minima che nascondeva la velocita'
        // quando bassa (vedi MapScreen.kt/TimeFormat.kt).
        // v0.11.0 (2026-09-18): GeofenceScreen — nuovo toggle "Non
        // disturbare (watch) in questa zona" (DND automatico
        // ingresso/uscita, vedi backend/api/trigger-event.js v0.14.0 e
        // watch-app dnd/DndController.kt).
        // v0.12.0 (2026-09-19): StatusCard — nuova riga "Autonomia
        // residua" sotto la percentuale batteria, richiesta dall'utente
        // (vedi MapScreen.kt/DeviceState.batteryHoursRemaining, stimata
        // dal sistema operativo del watch, non dalla phone-app).
        // v0.13.0 (2026-09-22): richiesto dall'utente — richiesta
        // automatica della posizione a tutti i bambini all'apertura
        // dell'app; "Ultima posizione" mostra ora anche data/ora
        // assolute oltre al relativo (vedi MapScreen.kt).
        // v0.14.0 (2026-09-23): bump mancato nel commit originale —
        // la Fase 1 "isolamento famiglie" (22/09, vedi CHANGELOG.md
        // v0.68.0) aveva aggiunto la nuova sezione "Genitori" in
        // SettingsScreen.kt (invito/accettazione famiglia) e relativo
        // codice in AppViewModel.kt/BackendClient.kt/
        // DeviceRepository.kt senza incrementare versionCode/
        // versionName: un rebuild sarebbe rimasto indistinguibile dal
        // build precedente. Individuato verificando con l'utente quale
        // fosse la versione realmente in produzione sul telefono.
        // v0.15.0 (2026-09-23): due interventi (vedi CHANGELOG.md
        // v0.71.0). (1) Bug trovato lavorando sul punto 2, non dal
        // documento di review: observeChildren()/observeGeofences() in
        // DeviceRepository.kt interrogavano le collezioni senza filtro
        // familyId — le regole Firestore v0.7.0 rifiutano in blocco una
        // query non provabilmente vincolata, quindi appena pubblicate
        // la lista bambini/zone si sarebbe svuotata per chiunque.
        // (2) Fase 2 di qwen_plan.md (individuato da
        // qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5): topic FCM
        // per-bambino al posto del topic globale "parents" — SOS/
        // geofence/chat/batteria di un bambino non arrivano piu' a
        // telefoni di altre famiglie iscritti allo stesso topic.
        // v0.16.0 (2026-09-23): Fase 4 di qwen_plan.md (individuato da
        // qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) —
        // FcmService.postNotification() usava
        // System.currentTimeMillis().toInt() come id: due notifiche
        // arrivate nello stesso millisecondo si sovrascrivevano invece
        // di comparire entrambe. Sostituito con un contatore atomico
        // monotono. Aggravante trovata qui, non dal documento di
        // review: il PendingIntent di apertura usava sempre lo stesso
        // requestCode fisso — col nuovo id univoco due notifiche
        // potevano davvero coesistere, ma avrebbero condiviso la stessa
        // destinazione (quella dell'ultima creata). Il requestCode ora
        // e' lo stesso id univoco della notifica.
        // v0.17.0 (2026-09-23): "Ultima posizione" che si aggiorna da sola
        // ogni 30s; riga "Ultimo contatto (senza posizione)".
        // v0.18.0 (2026-09-23): riga satelliti visti/agganciati; verde della
        // batteria scurito (il verde fluo era illeggibile).
        // v0.19.0 (2026-09-23): "Satelliti: GPS non usato" quando la posizione
        // arriva da Wi-Fi/rete.
        // v0.20.0 (2026-09-23): richiesta posizione che insiste (fino a 20
        // tentativi) con barra "nuovo tentativo tra Ns" nella StatusCard.
        // v0.21.0 (2026-09-23): barra "in attesa del watch" subito dopo la
        // richiesta, invece di 2 minuti senza nulla a schermo.
        // v0.22.0 (2026-09-23): icona e riga "Stato watch" (modalita' aereo /
        // spento / non raggiungibile), nuovi eventi nello storico.
        // v0.23.0 (2026-09-24): la barra dei tentativi sparisce appena arriva
        // una posizione; gli avvisi di riaccensione/modalita' aereo non
        // contano piu' come tentativo fallito (lastNoFixAt).
        // v0.24.0 (2026-09-24): interruttore "Alta precisione da fermo" per
        // bambino nelle Impostazioni.
        // v0.25.0 (2026-09-24): icona "in carica" (🔌) accanto al nome del
        // bambino (ui/MapScreen.kt watchHeaderIcon).
        versionCode = 25
        versionName = "0.25.0"
    }

    // Keystore di debug fisso nel progetto (../debug.keystore, mai
    // committato — vedi .gitignore) invece di quello di default in
    // ~/.android/: cosi' la firma resta identica su qualunque macchina
    // si compili, ed e' la stessa il cui SHA-1 e' stato registrato su
    // Firebase per il login Google (vedi README.md).
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        // v0.9.0: vedi Storico versioni sopra — richiesto da AGP 8+ per
        // generare la classe BuildConfig (usata in MapScreen.kt per
        // BuildConfig.VERSION_NAME).
        buildConfig = true
    }

    // Stessa versione compiler di watch-app: coerente con Kotlin 1.9.24
    // (vedi ../watch-app/app/build.gradle.kts).
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Compose UI — stesso BOM/versione base di ui:1.6.8 gia' usato in
    // watch-app, per restare compatibili con compiler 1.5.14/Kotlin 1.9.24.
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // v0.2.0 (vedi Storico versioni sopra): icona hamburger per il menu
    // di MapScreen.kt (Icons.Filled.Menu) — non fa parte di material3,
    // serve il modulo icone "core" a parte (stessa versione via BOM sopra).
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Firebase: Auth (login genitore), Firestore (letture in tempo
    // reale), Messaging (push SOS/geofence). Nessuna Cloud Function
    // richiamata da qui: le scritture passano solo dal backend Vercel,
    // tranne le geofence (scrittura diretta consentita dalle regole,
    // vedi backend/firestore.rules).
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")

    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Mappa OpenStreetMap (osmdroid) invece di Google Maps: nessuna API
    // key, nessuna fatturazione da collegare al progetto Google Cloud —
    // coerente con la scelta "mai una carta se evitabile" fatta per
    // tutto il resto dello stack (vedi CONTEXT.md, log decisioni).
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // v0.2.0 (chat): unica chiamata REST verso il backend (invio
    // messaggio al watch, serve la push FCM), vedi data/BackendClient.kt.
    // Stessa versione di watch-app/app/build.gradle.kts.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
