package com.gwatch.childtracker.config

import com.gwatch.childtracker.BuildConfig

/**
 * Valori iniettati in fase di build da local.properties (mai
 * committato, vedi watch-app/local.properties.example) — cosi' il
 * DEVICE_TOKEN non finisce mai nel sorgente.
 */
object BackendConfig {
    val baseUrl: String = BuildConfig.BACKEND_BASE_URL
    val deviceToken: String = BuildConfig.DEVICE_TOKEN
}
