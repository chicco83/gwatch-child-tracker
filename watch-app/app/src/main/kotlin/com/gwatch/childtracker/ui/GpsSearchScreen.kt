package com.gwatch.childtracker.ui

// Versione: 0.6.0 (2026-09-23)
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
// - 0.2.0 (2026-09-23): primo test su Watch4 reale — con 25 satelliti
//   visti e 14 USATI (quindi il chip GPS la posizione l'aveva calcolata)
//   la schermata non riceveva mai il fix e restava sulle barre. Stesso
//   sintomo gia' visto il 18/9 con il fused provider di
//   LocationRequestWorker ("fix GPS non disponibile (null)" per ore): la
//   ricezione funziona, e' la CONSEGNA della posizione all'app che si
//   blocca da qualche parte. Aggiunti:
//   (1) diagnostica a schermo, per capire dove senza Logcat: posizione di
//       sistema ON/OFF, provider GPS ON/OFF, esito della registrazione
//       (prima un errore finiva solo in Logcat), numero di fix arrivati
//       al listener, eta' dell'ultima posizione GPS nota al sistema;
//   (2) recupero alternativo: ogni secondo si legge
//       getLastKnownLocation(GPS_PROVIDER); se e' recente (<= FRESH_FIX_S)
//       vale come fix anche se il listener non e' mai stato chiamato;
//   (3) onFixFound chiamato una sola volta anche se listener e recupero
//       scattano insieme.
// - 0.3.0 (2026-09-23): secondo test su Watch4 — posizione di sistema ON,
//   provider GPS ON, registrazione OK, 10 satelliti usati, ma 0 fix
//   arrivati all'app. Android accetta la richiesta senza errori ma non
//   consegna nulla: tipico di un permesso "ridotto" a livello di AppOps
//   (es. posizione ignorata o solo in primo piano) che checkSelfPermission
//   non mostra. Aggiunte alla diagnostica: permesso preciso, permesso in
//   background, stato AppOps della posizione precisa, provider presenti,
//   e se l'ultima posizione di sistema risulta fittizia (mock: con le
//   Opzioni sviluppatore attive un'app di posizione fittizia sostituisce
//   il GPS vero).
// - 0.4.0 (2026-09-23): terzo test — permessi, AppOps e mock tutti a
//   posto, ma "ultima posizione GPS di sistema: nessuna" con 10 satelliti
//   usati; aprendo Google Maps il fix e' arrivato SUBITO anche alla nostra
//   app. Quindi il GPS di sistema non produceva posizioni per nessuno
//   finche' Maps non ha fornito i suoi dati di aiuto. Ora all'avvio della
//   ricerca: (1) GpsAssist inietta ora + effemeridi (force=true, e'
//   un'azione esplicita dell'utente); (2) oltre al GPS diretto, richiesta
//   continua anche tramite servizi Google (fused provider), la stessa
//   strada di Maps. La diagnostica conta a parte i fix delle due strade.
// - 0.5.0 (2026-09-23): pulsante "Reset dati GPS" (GpsAssist.resetAidingData):
//   cancella i dati di aiuto del chip e riparte da zero; in quel caso la
//   ricerca dura fino a RESET_SEARCH_LIMIT_S (una partenza a freddo puo'
//   richiedere diversi minuti). Esito mostrato in diagnostica.
// - 0.6.0 (2026-09-23): righe sul tracking automatico (TrackingStatus):
//   servizio attivo, priorita' richiesta, punti ricevuti ed eta'
//   dell'ultimo, ultimo errore.
//
// Nota di progetto: niente Modifier.weight (non risolveva a build reale
// in questo progetto, vedi CONTEXT.md) — barre a larghezza fissa dentro
// una Row allineata in basso, stessi mattoni (Box/Row/background) gia'
// verificati in ChatScreen.kt.

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
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
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.gwatch.childtracker.R
import com.gwatch.childtracker.location.GpsAssist
import com.gwatch.childtracker.location.TrackingStatus
import kotlinx.coroutines.delay

private const val TAG = "GpsSearchScreen"

// Durata massima di una ricerca: oltre, GPS spento fino a "Riprova".
private const val SEARCH_LIMIT_S = 180

// v0.5.0: durata dopo un "Reset dati GPS" (partenza a freddo).
private const val RESET_SEARCH_LIMIT_S = 600

// Barre mostrate: le piu' forti, per stare nella larghezza dello schermo tondo.
private const val MAX_BARS = 12

// Scala delle barre: ~45 dB-Hz e' gia' un segnale ottimo a cielo aperto.
private const val CN0_FULL_SCALE = 45f

// v0.2.0: eta' massima di getLastKnownLocation(GPS) per valere come fix.
private const val FRESH_FIX_S = 15L

private data class Satellite(val cn0: Float, val usedInFix: Boolean)

// v0.2.0: dati mostrati nel blocco di diagnostica (null = non ancora letto).
private data class GpsDiagnostics(
    val locationEnabled: Boolean? = null,
    val gpsProviderEnabled: Boolean? = null,
    val registerError: String? = null,
    val listenerFixes: Int = 0,
    // v0.4.0: fix arrivati tramite servizi Google (fused provider).
    val fusedFixes: Int = 0,
    // v0.5.0: esito dell'ultimo "Reset dati GPS" (null = non fatto).
    val resetAccepted: Boolean? = null,
    val lastKnownAgeS: Long? = null,
    // v0.3.0
    val fineGranted: Boolean? = null,
    val backgroundGranted: Boolean? = null,
    val appOpMode: String? = null,
    val providers: String? = null,
    val lastKnownMock: Boolean? = null,
)

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
    // v0.5.0: true dal tocco su "Reset dati GPS" fino al riavvio della ricerca.
    var resetRequested by remember { mutableStateOf(false) }
    var limitS by remember { mutableStateOf(SEARCH_LIMIT_S) }
    var satellites by remember { mutableStateOf<List<Satellite>>(emptyList()) }
    var diag by remember { mutableStateOf(GpsDiagnostics()) }
    // v0.2.0: una sola chiamata a onFixFound anche se listener e recupero
    // da getLastKnownLocation scattano nello stesso momento.
    var fixHandled by remember { mutableStateOf(false) }

    val handleFix: (String) -> Unit = { source ->
        if (!fixHandled) {
            fixHandled = true
            running = false
            Log.i(TAG, "fix ottenuto da: $source")
            onFixFound()
        }
    }

    if (hasPermission && running) {
        DisposableEffect(attempt) {
            // v0.5.0: il reset avviene qui, dopo che il DisposableEffect
            // precedente ha gia' spento il GPS (onDispose) e prima di
            // riaccenderlo.
            if (resetRequested) {
                val accepted = GpsAssist.resetAidingData(context)
                diag = diag.copy(resetAccepted = accepted)
                resetRequested = false
            }
            val stop = startGpsSearch(
                context = context,
                onSatellites = { satellites = it },
                onRegisterResult = { error -> diag = diag.copy(registerError = error) },
                onListenerFix = {
                    diag = diag.copy(listenerFixes = diag.listenerFixes + 1)
                    handleFix("listener GPS")
                },
                onFusedFix = {
                    diag = diag.copy(fusedFixes = diag.fusedFixes + 1)
                    handleFix("servizi Google")
                },
            )
            onDispose { stop() }
        }
        LaunchedEffect(attempt) {
            seconds = 0
            while (seconds < limitS) {
                // v0.2.0: diagnostica + recupero alternativo, una volta al secondo.
                val snapshot = readSystemGpsState(context)
                diag = diag.copy(
                    locationEnabled = snapshot.locationEnabled,
                    gpsProviderEnabled = snapshot.gpsProviderEnabled,
                    lastKnownAgeS = snapshot.lastKnownAgeS,
                    fineGranted = snapshot.fineGranted,
                    backgroundGranted = snapshot.backgroundGranted,
                    appOpMode = snapshot.appOpMode,
                    providers = snapshot.providers,
                    lastKnownMock = snapshot.lastKnownMock,
                )
                if (seconds % 10 == 0) Log.i(TAG, "stato: $diag, satelliti=${satellites.size}")
                val age = snapshot.lastKnownAgeS
                if (age != null && age <= FRESH_FIX_S) {
                    handleFix("getLastKnownLocation (${age}s)")
                    break
                }
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
                        diag = GpsDiagnostics()
                        fixHandled = false
                        limitS = SEARCH_LIMIT_S
                        attempt++
                        running = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { CenteredChipLabel(stringResourceCompat(R.string.gps_search_retry)) },
                )
            }
            // v0.5.0: sempre disponibile, anche a ricerca in corso.
            CompactChip(
                onClick = {
                    satellites = emptyList()
                    diag = GpsDiagnostics()
                    fixHandled = false
                    resetRequested = true
                    limitS = RESET_SEARCH_LIMIT_S
                    attempt++
                    running = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.secondaryChipColors(),
                label = { CenteredChipLabel(stringResourceCompat(R.string.gps_search_reset)) },
            )
            DiagnosticsBlock(context, diag)
        }
        CompactChip(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            colors = ChipDefaults.secondaryChipColors(),
            label = { CenteredChipLabel(stringResourceCompat(R.string.back)) },
        )
    }
}

// v0.2.0: righe di diagnostica sotto le barre (scorrendo in giu').
@Composable
private fun DiagnosticsBlock(context: Context, diag: GpsDiagnostics) {
    fun onOff(value: Boolean?): String = when (value) {
        true -> "ON"
        false -> "OFF"
        null -> "?"
    }
    val lines = listOf(
        context.getString(R.string.gps_diag_location, onOff(diag.locationEnabled)),
        context.getString(R.string.gps_diag_provider, onOff(diag.gpsProviderEnabled)),
        if (diag.registerError == null) {
            context.getString(R.string.gps_diag_register_ok)
        } else {
            context.getString(R.string.gps_diag_register_error, diag.registerError)
        },
        context.getString(R.string.gps_diag_listener_fixes, diag.listenerFixes),
        context.getString(R.string.gps_diag_fused_fixes, diag.fusedFixes),
        // v0.6.0: tracking automatico (letto a ogni ricomposizione, cioe'
        // almeno una volta al secondo col cronometro della ricerca).
        context.getString(
            R.string.gps_diag_tracking,
            if (TrackingStatus.serviceRunning) "attivo" else "NON attivo",
            TrackingStatus.priorityLabel ?: "?",
        ),
        context.getString(
            R.string.gps_diag_tracking_fixes,
            TrackingStatus.fixCount,
            TrackingStatus.lastFixAgeS()?.let { "${it}s fa" } ?: "mai",
        ),
        context.getString(R.string.gps_diag_tracking_error, TrackingStatus.lastError ?: "nessuno"),
        context.getString(
            R.string.gps_diag_reset,
            when (diag.resetAccepted) {
                true -> "accettato"
                false -> "RIFIUTATO"
                null -> "non fatto"
            },
        ),
        if (diag.lastKnownAgeS == null) {
            context.getString(R.string.gps_diag_last_known_none)
        } else {
            context.getString(R.string.gps_diag_last_known_age, diag.lastKnownAgeS)
        },
        // v0.3.0: permessi effettivi dell'app.
        context.getString(R.string.gps_diag_fine, onOff(diag.fineGranted)),
        context.getString(R.string.gps_diag_background, onOff(diag.backgroundGranted)),
        context.getString(R.string.gps_diag_appop, diag.appOpMode ?: "?"),
        context.getString(R.string.gps_diag_providers, diag.providers ?: "?"),
        context.getString(R.string.gps_diag_mock, onOff(diag.lastKnownMock)),
    )
    lines.forEach {
        Text(
            text = it,
            style = MaterialTheme.typography.caption3,
            textAlign = TextAlign.Center,
            color = Color.LightGray,
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

private data class SystemGpsState(
    val locationEnabled: Boolean?,
    val gpsProviderEnabled: Boolean?,
    val lastKnownAgeS: Long?,
    val fineGranted: Boolean?,
    val backgroundGranted: Boolean?,
    val appOpMode: String?,
    val providers: String?,
    val lastKnownMock: Boolean?,
)

// v0.2.0: stato del sistema letto a ogni secondo. Ogni lettura e'
// protetta a parte: un errore su una non deve nascondere le altre.
@SuppressLint("MissingPermission")
private fun readSystemGpsState(context: Context): SystemGpsState {
    val locationManager = context.getSystemService(LocationManager::class.java)
    val locationEnabled = runCatching { locationManager.isLocationEnabled }.getOrNull()
    val gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()
    val lastKnown = runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
    val ageS = lastKnown?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000_000L }
    // v0.3.0: isFromMockProvider e' deprecato da API 31 ma disponibile
    // da API 18 (minSdk 30): va bene per tutte le versioni del watch.
    @Suppress("DEPRECATION")
    val mock = lastKnown?.isFromMockProvider
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val background = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    // v0.3.0: stato AppOps della posizione precisa per questa app — puo'
    // essere "ignorato" anche con il permesso concesso.
    val appOp = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_FINE_LOCATION,
            android.os.Process.myUid(),
            context.packageName,
        )
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> "consentito"
            AppOpsManager.MODE_FOREGROUND -> "solo in primo piano"
            AppOpsManager.MODE_IGNORED -> "IGNORATO"
            AppOpsManager.MODE_ERRORED -> "NEGATO"
            AppOpsManager.MODE_DEFAULT -> "predefinito"
            else -> "codice $mode"
        }
    }.getOrNull()
    val providers = runCatching { locationManager.allProviders.joinToString(", ") }.getOrNull()
    return SystemGpsState(locationEnabled, gpsEnabled, ageS, fine, background, appOp, providers, mock)
}

// Avvia GPS + ascolto satelliti; ritorna la funzione che li ferma.
// v0.2.0: l'esito della registrazione arriva a onRegisterResult (null =
// ok, altrimenti il messaggio d'errore) per mostrarlo a schermo; prima
// finiva solo in Logcat. Le due registrazioni sono separate: se una
// fallisce l'altra resta attiva.
@SuppressLint("MissingPermission")
private fun startGpsSearch(
    context: Context,
    onSatellites: (List<Satellite>) -> Unit,
    onRegisterResult: (String?) -> Unit,
    onListenerFix: () -> Unit,
    onFusedFix: () -> Unit,
): () -> Unit {
    // v0.4.0: ora + effemeridi nel chip prima di tutto (vedi GpsAssist.kt).
    GpsAssist.injectAssistance(context, force = true)
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
    val locationListener = LocationListener { onListenerFix() }
    val errors = mutableListOf<String>()
    try {
        locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), gnssCallback)
    } catch (e: Exception) {
        Log.w(TAG, "startGpsSearch: registerGnssStatusCallback fallita", e)
        errors += "satelliti: ${e.javaClass.simpleName}"
    }
    try {
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            0f,
            locationListener,
            Looper.getMainLooper(),
        )
    } catch (e: Exception) {
        Log.w(TAG, "startGpsSearch: requestLocationUpdates fallita", e)
        errors += "posizione: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
    }
    // v0.4.0: stessa richiesta continua che fa Google Maps.
    val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (result.lastLocation != null) onFusedFix()
        }
    }
    try {
        fusedClient.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build(),
            fusedCallback,
            Looper.getMainLooper(),
        )
    } catch (e: Exception) {
        Log.w(TAG, "startGpsSearch: fused requestLocationUpdates fallita", e)
        errors += "servizi Google: ${e.javaClass.simpleName}"
    }
    onRegisterResult(errors.takeIf { it.isNotEmpty() }?.joinToString("; "))
    return {
        runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
        runCatching { locationManager.removeUpdates(locationListener) }
        runCatching { fusedClient.removeLocationUpdates(fusedCallback) }
    }
}

// --- Versione precedente di startGpsSearch (0.1.0, sostituita il 2026-09-23) ---
// @SuppressLint("MissingPermission")
// private fun startGpsSearch(
//     context: Context,
//     onSatellites: (List<Satellite>) -> Unit,
//     onFix: () -> Unit,
// ): () -> Unit {
//     val locationManager = context.getSystemService(LocationManager::class.java)
//     val gnssCallback = object : GnssStatus.Callback() {
//         override fun onSatelliteStatusChanged(status: GnssStatus) {
//             onSatellites(
//                 (0 until status.satelliteCount).map {
//                     Satellite(cn0 = status.getCn0DbHz(it), usedInFix = status.usedInFix(it))
//                 },
//             )
//         }
//     }
//     var fixDelivered = false
//     val locationListener = LocationListener {
//         if (!fixDelivered) {
//             fixDelivered = true
//             onFix()
//         }
//     }
//     try {
//         locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), gnssCallback)
//         locationManager.requestLocationUpdates(
//             LocationManager.GPS_PROVIDER,
//             1000L,
//             0f,
//             locationListener,
//             Looper.getMainLooper(),
//         )
//     } catch (e: Exception) {
//         Log.w(TAG, "startGpsSearch: impossibile avviare la ricerca GPS", e)
//     }
//     return {
//         runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
//         runCatching { locationManager.removeUpdates(locationListener) }
//     }
// }
