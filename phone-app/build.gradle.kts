// Build file di progetto (root). Le versioni dei plugin sono qui,
// applicate poi nel modulo app/. Stesse versioni AGP/Kotlin di
// watch-app per coerenza (vedi ../watch-app/build.gradle.kts).
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
