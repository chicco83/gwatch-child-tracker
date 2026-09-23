package com.gwatch.childtracker.ui

// Versione: 0.1.0 (2026-09-23)
//
// Schermata "Ricerca GPS" stile vecchi navigatori TomTom: una barra per
// satellite, alta quanto il segnale (C/N0 in dB-Hz), verde se usato per
// calcolare la posizione, grigia se solo visto. Richiesta dall'utente
// dopo il fix v0.14.0 del pulsante "Invia posizione" (GPS assente).
//
// Come funziona:
// - Si apre toccando "GPS assente, tocca per cercare" nella schermata
//   principale (MainActivity.kt).
// - Accende il GPS SOLO mentre la schermata e' aperta (requestLocationUpdates
//   sul provider GPS: senza, Android non emette lo stato dei satelliti) e
//   al massimo per SEARCH_LIMIT_S secondi, poi si ferma da sola: se il
//   bambino lascia il watch su questa schermata non resta a consumare
//   batteria. "Riprova" riparte da zero.
// - Al primo fix chiama onFixFound: MainActivity segna il GPS
//   disponibile, lancia l'invio della posizione e torna alla schermata
//   principale.
//
// Storico versioni:
// - 0.1.0 (2026-09-23): prima versione.
//
// Nota di progetto: niente Modifier.weight (non risolveva a build reale
// in questo progetto, vedi CONTEXT.md) — barre a larghezza fissa dentro
// una Row allineata in basso, stessi mattoni (Box/Row/background) gia'
// verificati in ChatScreen.kt.

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.gwatch.childtracker.R
import kotlinx.coroutines.delay

private const val TAG = "GpsSearchScreen"

// Durata massima di una ricerca: oltre, GPS spento fino a "Riprova".
private const val SEARCH_LIMIT_S = 180

// Barre mostrate: le piu' forti, per stare nella larghezza dello schermo tondo.
private const val MAX_BARS = 12

// Scala delle barre: ~45 dB-Hz e' gia' un segnale ottimo a cielo aperto.
private const val CN0_FULL_SCALE = 45f

private data class Satellite(val cn0: Float, val usedInFix: Boolean)

@Composable
fun GpsSearchScreen(onFixFound: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val hasPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    // attempt cambia a ogni "Riprova": riavvia GPS e cronometro.
    var attempt by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(true) }
    var seconds by remember { mutableStateOf(0) }
    var satellites by remember { mutableStateOf<List<Satellite>>(emptyList()) }

    if (hasPermission && running) {
        DisposableEffect(attempt) {
            val stop = startGpsSearch(
                context = context,
                onSatellites = { satellites = it },
                onFix = {
                    running = false
                    onFixFound()
                },
            )
            onDispose { stop() }
        }
        LaunchedEffect(attempt) {
            seconds = 0
            while (seconds < SEARCH_LIMIT_S) {
                delay(1000)
                seconds++
            }
            running = false
        }
    }

    val used = satellites.count { it.usedInFix }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Text(text = stringResourceCompat(R.string.gps_search_title))

        if (!hasPermission) {
            Text(
                text = stringResourceCompat(R.string.gps_search_no_permission),
                textAlign = TextAlign.Center,
            )
        } else {
            SatelliteBars(satellites)
            Text(
                text = context.getString(R.string.gps_search_counts, satellites.size, used),
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (running) {
                    context.getString(R.string.gps_search_elapsed, seconds / 60, seconds % 60)
                } else {
                    stringResourceCompat(R.string.gps_search_stopped)
                },
                style = MaterialTheme.typography.caption3,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResourceCompat(R.string.gps_search_hint),
                style = MaterialTheme.typography.caption3,
                textAlign = TextAlign.Center,
            )
            if (!running) {
                Chip(
                    onClick = {
                        satellites = emptyList()
                        attempt++
                        running = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { CenteredChipLabel(stringResourceCompat(R.string.gps_search_retry)) },
                )
            }
        }
        CompactChip(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            colors = ChipDefaults.secondaryChipColors(),
            label = { CenteredChipLabel(stringResourceCompat(R.string.back)) },
        )
    }
}

// Una barra per satellite, ordinate dalla piu' forte. Altezza minima
// 2dp cosi' anche un satellite visto ma senza segnale utile compare.
@Composable
private fun SatelliteBars(satellites: List<Satellite>) {
    val bars = satellites.sortedByDescending { it.cn0 }.take(MAX_BARS)
    Row(
        modifier = Modifier.height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (bars.isEmpty()) {
            Text(
                text = "…",
                style = MaterialTheme.typography.caption2,
            )
        }
        bars.forEach { sat ->
            val fraction = (sat.cn0 / CN0_FULL_SCALE).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height((2 + 54 * fraction).dp)
                    .background(if (sat.usedInFix) Color(0xFF4CAF50) else Color.Gray),
            )
        }
    }
}

// Avvia GPS + ascolto satelliti; ritorna la funzione che li ferma.
// Gli errori (es. permesso revocato nel frattempo) vengono solo loggati:
// la schermata resta a zero satelliti invece di far crashare l'app.
@SuppressLint("MissingPermission")
private fun startGpsSearch(
    context: Context,
    onSatellites: (List<Satellite>) -> Unit,
    onFix: () -> Unit,
): () -> Unit {
    val locationManager = context.getSystemService(LocationManager::class.java)
    val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            onSatellites(
                (0 until status.satelliteCount).map {
                    Satellite(cn0 = status.getCn0DbHz(it), usedInFix = status.usedInFix(it))
                },
            )
        }
    }
    var fixDelivered = false
    val locationListener = LocationListener {
        if (!fixDelivered) {
            fixDelivered = true
            onFix()
        }
    }
    try {
        locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), gnssCallback)
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            0f,
            locationListener,
            Looper.getMainLooper(),
        )
    } catch (e: Exception) {
        Log.w(TAG, "startGpsSearch: impossibile avviare la ricerca GPS", e)
    }
    return {
        runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
        runCatching { locationManager.removeUpdates(locationListener) }
    }
}
