package com.gwatch.childtracker.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Versione: 0.1.0 (2026-09-23)
 *
 * Conta i satelliti visti e agganciati (usati per il fix) durante un
 * tentativo di posizione, per mostrarli sulla phone-app (richiesta
 * utente). Nessun consumo aggiuntivo: registrarsi a GnssStatus non
 * accende il GPS, riceve dati solo mentre e' gia' acceso per la richiesta
 * in corso (LocationRequestWorker). Si tiene il massimo raggiunto
 * durante il tentativo.
 */
class GnssCounter(private val context: Context) {
    @Volatile var maxVisible: Int = 0
        private set
    @Volatile var maxUsed: Int = 0
        private set
    @Volatile var received: Boolean = false
        private set

    private val locationManager = context.getSystemService(LocationManager::class.java)

    private val callback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val used = (0 until status.satelliteCount).count { status.usedInFix(it) }
            maxVisible = maxOf(maxVisible, status.satelliteCount)
            maxUsed = maxOf(maxUsed, used)
            received = true
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        runCatching {
            locationManager?.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), callback)
        }.onFailure { Log.w(TAG, "start fallito", it) }
    }

    fun stop() {
        runCatching { locationManager?.unregisterGnssStatusCallback(callback) }
    }

    companion object {
        private const val TAG = "GnssCounter"
    }
}
