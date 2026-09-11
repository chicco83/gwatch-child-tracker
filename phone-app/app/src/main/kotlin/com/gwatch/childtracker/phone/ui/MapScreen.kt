package com.gwatch.childtracker.phone.ui

// Storico versioni
// v0.1.0 (2026-09-10): prima versione, layout a Column con
//   Modifier.weight(1f) sull'AndroidView della mappa per farle occupare
//   lo spazio restante fra StatusCard (sopra) e EventsList (sotto).
// v0.2.0 (2026-09-10): stesso identico errore di compilazione già
//   incontrato nel watch-app (vedi ChatScreen.kt): "Cannot access
//   'weight': it is internal in 'androidx.compose.foundation.layout'"
//   con le versioni di Compose fissate in questo progetto. Riscritto
//   senza Modifier.weight: la mappa (AndroidView) ora riempie tutto lo
//   Box con Modifier.fillMaxSize(), e StatusCard/EventsList sono
//   sovrapposti sopra con Modifier.align (StatusCard in alto,
//   EventsList in basso) invece di essere disposti in sequenza in una
//   Column pesata.
// v0.3.0 (2026-09-10): aggiunti i controlli richiesti dall'utente dopo
//   il primo test su device reale: (1) un pulsante "Aggiorna posizione"
//   che chiede subito al watch un fix GPS via push (vedi
//   AppViewModel.requestLocation/backend/api/request-location.js),
//   invece di dover aspettare il prossimo upload periodico; (2) uno
//   switch per passare fra "solo posizione attuale" (comportamento di
//   prima) e "percorso ultime 24h" (disegna la polyline sullo storico
//   filtrato alle ultime 24h, invece di disegnarla sempre come prima).
// v0.4.0 (2026-09-10): banner SOS — quando deviceState.sosActive e'
//   true (il watch ha attivato un SOS, vedi trigger-event.js) mostra un
//   banner rosso in cima con un pulsante "Disattiva SOS"
//   (AppViewModel.cancelSos/backend/api/cancel-sos.js), che marca
//   sosActive=false e manda la push che ferma SosLocationService sul
//   watch.
// v0.5.0 (2026-09-10): richiesto un modo per il bambino di sapere che
//   il genitore ha visto la posizione che ha inviato (SOS o "Invia
//   posizione" premuto sul watch, non una richiesta remota del
//   genitore stesso). Aggiunto un LaunchedEffect(events): appena questa
//   schermata mostra un evento "sos" o "location_request" (source
//   "child") non ancora marcato, chiama AppViewModel.ackEvent, che
//   avvisa il watch (vedi backend/api/ack-event.js). "Visto" qui
//   significa letteralmente "la mappa con quell'evento e' stata
//   composta" — non richiede un tocco esplicito, coerente con com'e'
//   gia' pensata questa schermata (si apre gia' mostrando l'ultima
//   posizione).
// v0.6.0 (2026-09-11): segnalato un logout inatteso premendo "Esci" —
//   il pulsante era un TextButton nella TopAppBar, esattamente affianco
//   a "Messaggi"/"Zone", che chiamava onSignOut() subito al tocco senza
//   nessuna conferma: bastava un tocco leggermente spostato sugli altri
//   due per disconnettersi per errore. Aggiunto un AlertDialog di
//   conferma (stesso pattern gia' usato per l'SOS sul watch,
//   sos_confirm_title/message) prima di chiamare onSignOut().
// v0.7.0 (2026-09-11): richiesta utente dopo il fix precedente — non
//   era chiaro che "Esci" fosse un logout (confuso con un pulsante di
//   chiusura schermata qualsiasi). Tre modifiche:
//   1) "Esci" (rinominato "Logout") non e' piu' un pulsante diretto in
//      barra ma una voce dentro un menu hamburger (pattern classico:
//      un'azione rara/distruttiva sepolta in un menu, non affiancata a
//      quelle frequenti come "Messaggi"/"Zone" — la stessa causa del
//      bug v0.6.0). Il dialog di conferma gia' aggiunto in v0.6.0 resta
//      invariato.
//   2) lo switch "Percorso 24h" si sposta dalla card MapControls
//      (rimossa) alla TopAppBar, accanto all'icona del menu.
//   3) il pulsante "Aggiorna posizione" si sposta sulla stessa riga
//      della StatusCard (testo stato + batteria), invece di stare in
//      una card MapControls separata sotto.

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gwatch.childtracker.phone.R
import com.gwatch.childtracker.phone.data.model.DeviceEvent
import com.gwatch.childtracker.phone.data.model.DeviceState
import com.gwatch.childtracker.phone.util.formatRelativeTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

// Roma come default finche' non arriva il primo fix dal watch.
private val DEFAULT_POSITION = GeoPoint(41.9028, 12.4964)

// Finestra del percorso mostrato quando lo switch "Percorso 24h" e'
// attivo. Filtra lato client la stessa `history` gia' caricata da
// AppViewModel (finestra piu' ampia, Constants.HISTORY_WINDOW_HOURS),
// senza bisogno di una query Firestore separata.
private const val PATH_WINDOW_HOURS = 24L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: AppViewModel,
    onOpenGeofences: () -> Unit,
    onOpenChat: () -> Unit,
    onSignOut: () -> Unit,
) {
    val deviceState by viewModel.deviceState.collectAsState()
    val history by viewModel.history.collectAsState()
    val geofences by viewModel.geofences.collectAsState()
    val events by viewModel.events.collectAsState()

    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(DEFAULT_POSITION)
        }
    }
    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

    // Vedi storico versioni v0.5.0 sopra. Filtro sul "source": una
    // richiesta di posizione fatta dal genitore stesso (MapControls
    // sotto) non deve generare "il genitore ha visto la tua posizione"
    // sul watch, non avrebbe senso.
    LaunchedEffect(events) {
        events
            .filter { !it.acknowledged && (it.type == "sos" || (it.type == "location_request" && it.source != "parent")) }
            .forEach { viewModel.ackEvent(it.id) }
    }

    // Centra la mappa sull'ultima posizione nota solo alla prima
    // ricezione: dopo, l'utente deve poter muovere liberamente la mappa
    // senza che un punto GPS successivo la "strappi" da sotto le dita.
    var centered by remember { mutableStateOf(false) }
    var showFullPath by remember { mutableStateOf(false) }
    var requestingLocation by remember { mutableStateOf(false) }
    var deactivatingSos by remember { mutableStateOf(false) }
    // v0.6.0: vedi storico versioni sopra — conferma prima del logout.
    var showSignOutConfirm by remember { mutableStateOf(false) }
    // v0.7.0: stato apertura del menu hamburger (contiene solo "Logout").
    var showMenu by remember { mutableStateOf(false) }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text(stringResource(R.string.sign_out_confirm_title)) },
            text = { Text(stringResource(R.string.sign_out_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    onSignOut()
                }) { Text(stringResource(R.string.sign_out_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) {
                    Text(stringResource(R.string.sign_out_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onOpenChat) { Text(stringResource(R.string.chat_title)) }
                    TextButton(onClick = onOpenGeofences) { Text(stringResource(R.string.geofences_title)) }
                    // v0.7.0: switch "Percorso 24h", prima nella card
                    // MapControls (rimossa) sotto la mappa, ora qui
                    // accanto all'icona del menu.
                    Text(stringResource(R.string.map_show_path), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = showFullPath, onCheckedChange = { showFullPath = it })
                    // v0.7.0: "Logout" non e' piu' un pulsante diretto
                    // in barra (causa del logout accidentale v0.6.0) ma
                    // una voce dentro questo menu hamburger.
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.menu_content_description))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sign_out)) },
                            onClick = {
                                showMenu = false
                                showSignOutConfirm = true
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { map ->
                    map.overlays.clear()

                    deviceState.lastLocation?.let { loc ->
                        val point = GeoPoint(loc.lat, loc.lon)
                        map.overlays.add(
                            Marker(map).apply {
                                position = point
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = context.getString(R.string.last_known_position)
                            },
                        )
                        if (!centered) {
                            map.controller.setCenter(point)
                            map.controller.setZoom(15.0)
                            centered = true
                        }
                    }

                    if (showFullPath) {
                        val cutoff = System.currentTimeMillis() - PATH_WINDOW_HOURS * 3_600_000L
                        val recentHistory = history.filter { it.timestampMillis >= cutoff }
                        if (recentHistory.size >= 2) {
                            map.overlays.add(
                                Polyline().apply {
                                    setPoints(recentHistory.map { GeoPoint(it.lat, it.lon) })
                                },
                            )
                        }
                    }

                    geofences.forEach { zone ->
                        val center = GeoPoint(zone.lat, zone.lon)
                        map.overlays.add(
                            Polygon().apply {
                                setPoints(Polygon.pointsAsCircle(center, zone.radiusMeters))
                                fillColor = if (zone.active) 0x334285F4.toInt() else 0x339E9E9E.toInt()
                                strokeColor = if (zone.active) 0xFF4285F4.toInt() else 0xFF9E9E9E.toInt()
                                strokeWidth = 3f
                            },
                        )
                    }

                    map.invalidate()
                },
            )

            Column(modifier = Modifier.align(Alignment.TopCenter)) {
                if (deviceState.sosActive) {
                    SosBanner(
                        deactivating = deactivatingSos,
                        onDeactivate = {
                            deactivatingSos = true
                            viewModel.cancelSos { ok ->
                                deactivatingSos = false
                                val feedbackRes = if (ok) {
                                    R.string.sos_deactivate_success
                                } else {
                                    R.string.sos_deactivate_failed
                                }
                                Toast.makeText(context, context.getString(feedbackRes), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
                StatusCard(
                    state = deviceState,
                    requesting = requestingLocation,
                    onRequestLocation = {
                        requestingLocation = true
                        viewModel.requestLocation { ok ->
                            requestingLocation = false
                            val feedbackRes = if (ok) {
                                R.string.map_request_location_sent
                            } else {
                                R.string.map_request_location_failed
                            }
                            Toast.makeText(context, context.getString(feedbackRes), Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            EventsList(events = events, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun SosBanner(deactivating: Boolean, onDeactivate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.sos_banner_title),
                color = MaterialTheme.colorScheme.onError,
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onDeactivate, enabled = !deactivating) {
                Text(
                    text = stringResource(R.string.sos_banner_deactivate),
                    color = MaterialTheme.colorScheme.onError,
                )
            }
        }
    }
}

// v0.7.0: prima card separata (MapControls) sotto la StatusCard —
// eliminata, il pulsante ora sta sulla stessa riga dello stato (vedi
// StatusCard sotto) e lo switch "Percorso 24h" e' salito in TopAppBar.
@Composable
private fun StatusCard(
    state: DeviceState,
    requesting: Boolean,
    onRequestLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = state.lastSeenMillis?.let { formatRelativeTime(it) }
                        ?: stringResource(R.string.no_data_yet),
                    style = MaterialTheme.typography.titleMedium,
                )
                state.battery?.let { battery ->
                    Text(
                        text = stringResource(R.string.battery_format, battery),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            TextButton(onClick = onRequestLocation, enabled = !requesting) {
                Text(
                    stringResource(
                        if (requesting) R.string.map_requesting_location else R.string.map_request_location,
                    ),
                )
            }
        }
    }
}

@Composable
private fun EventsList(events: List<DeviceEvent>, modifier: Modifier = Modifier) {
    if (events.isEmpty()) return
    val formatter = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    LazyColumn(modifier = modifier.fillMaxWidth().heightIn(max = 160.dp).padding(horizontal = 12.dp)) {
        items(events) { event ->
            Text(
                text = "${eventLabel(event.type, event.zoneName)} · ${formatter.format(Date(event.timestampMillis))}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

private fun eventLabel(type: String, zoneName: String?): String = when (type) {
    "sos" -> "🆘 SOS"
    "geofence_enter" -> "→ Entrato in ${zoneName ?: "zona"}"
    "geofence_exit" -> "← Uscito da ${zoneName ?: "zona"}"
    "location_request" -> "📍 Posizione inviata su richiesta"
    else -> type
}
