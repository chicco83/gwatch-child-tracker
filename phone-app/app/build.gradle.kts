import java.util.Properties

// Storico versioni (firma release/Play Console)
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
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gwatch.childtracker.phone"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
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
