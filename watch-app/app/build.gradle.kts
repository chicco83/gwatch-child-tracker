import java.util.Properties

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

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // v0.2.0 (2026-09-10): chat via push FCM invece di polling, per
    // consumo batteria (vedi CONTEXT.md, log decisioni)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.gwatch.childtracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gwatch.childtracker"
        minSdk = 30 // Wear OS 3 (Galaxy Watch4 e successivi)
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        buildConfigField("String", "DEVICE_TOKEN", "\"$deviceToken\"")
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
