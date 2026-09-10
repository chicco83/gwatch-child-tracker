// Build file di progetto (root). Le versioni dei plugin sono qui,
// applicate poi nel modulo app/.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // v0.2.0 (2026-09-10): chat FCM (vedi app/build.gradle.kts, README.md)
    id("com.google.gms.google-services") version "4.4.2" apply false
}
