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
// v0.8.0 (2026-09-11): fase 3/4 — supporto N bambini (vedi CONTEXT.md).
//   Una sola mappa mostra ora TUTTI i bambini insieme (non uno switcher
//   che ne mostra uno alla volta, richiesta esplicita dell'utente): un
//   marker per bambino (etichettato col nickname), riga orizzontale
//   scorrevole di status-card (una per bambino, ognuna col proprio
//   pulsante "Aggiorna posizione"), lista di banner SOS invece di uno
//   singolo (piu' bambini potrebbero avere un SOS attivo insieme). Le
//   geofence restano disegnate una sola volta ciascuna (sono gia' una
//   risorsa condivisa, vedi GeofenceScreen.kt fase 2/4), non duplicate
//   per bambino. Aggiunta anche una voce "Impostazioni" nel menu
//   hamburger, accanto a "Logout" (nuova SettingsScreen.kt: nickname
//   proprio/dei bambini, "Aggiungi bambino").

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.gwatch.childtracker.phone.data.model.ChildInfo
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

// Roma come default finche' non arriva il primo fix da qualunque watch.
private val DEFAULT_POSITION = GeoPoint(41.9028, 12.4964)

// Finestra del percorso mostrato quando lo switch "Percorso 24h" e'
// attivo. Filtra lato client la stessa `historyByChild` gia' caricata da
// AppViewModel (finestra piu' ampia, Constants.HISTORY_WINDOW_HOURS),
// senza bisogno di una query Firestore separata.
private const val PATH_WINDOW_HOURS = 24L

// Tavolozza per distinguere il percorso di bambini diversi sulla mappa
// (marker/zone restano invece a colore fisso, non serve distinguerli).
private val PATH_COLORS = listOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFF34A853.toInt(), 0xFFFBBC05.toInt())

private data class ChildEvent(val childName: String, val event: DeviceEvent)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: AppViewModel,
    onOpenGeofences: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val children by viewModel.children.collectAsState()
    val deviceStates by viewModel.deviceStates.collectAsState()
    val historyByChild by viewModel.historyByChild.collectAsState()
    val geofences by viewModel.geofences.collectAsState()
    val eventsByChild by viewModel.eventsByChild.collectAsState()

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
    // richiesta di posizione fatta dal genitore stesso non deve generare
    // "il genitore ha visto la tua posizione" sul watch, non avrebbe
    // senso. v0.8.0: itera su tutti i bambini, non solo uno.
    LaunchedEffect(eventsByChild) {
        eventsByChild.forEach { (childId, events) ->
            events
                .filter { !it.acknowledged && (it.type == "sos" || (it.type == "location_request" && it.source != "parent")) }
                .forEach { viewModel.ackEvent(childId, it.id) }
        }
    }

    // Centra la mappa sulla prima posizione nota (di un bambino
    // qualsiasi) solo alla prima ricezione: dopo, l'utente deve poter
    // muovere liberamente la mappa senza che un punto GPS successivo la
    // "strappi" da sotto le dita.
    var centered by remember { mutableStateOf(false) }
    var showFullPath by remember { mutableStateOf(false) }
    var deactivatingSosFor by remember { mutableStateOf<String?>(null) }
    // v0.6.0: vedi storico versioni sopra — conferma prima del logout.
    var showSignOutConfirm by remember { mutableStateOf(false) }
    // v0.7.0: stato apertura del menu hamburger.
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
                            text = { Text(stringResource(R.string.settings_title)) },
                            onClick = {
                                showMenu = false
                                onOpenSettings()
                            },
                        )
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

                    children.forEachIndexed { index, child ->
                        val state = deviceStates[child.id] ?: return@forEachIndexed
                        state.lastLocation?.let { loc ->
                            val point = GeoPoint(loc.lat, loc.lon)
                            map.overlays.add(
                                Marker(map).apply {
                                    position = point
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    title = child.name
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
                            val recentHistory = historyByChild[child.id].orEmpty().filter { it.timestampMillis >= cutoff }
                            if (recentHistory.size >= 2) {
                                map.overlays.add(
                                    Polyline().apply {
                                        setPoints(recentHistory.map { GeoPoint(it.lat, it.lon) })
                                        outlinePaint.color = PATH_COLORS[index % PATH_COLORS.size]
                                    },
                                )
                            }
                        }
                    }

                    // Le geofence sono una risorsa condivisa (fase 2/4):
                    // disegnate una sola volta ciascuna, non duplicate
                    // per bambino assegnato.
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
                children.filter { deviceStates[it.id]?.sosActive == true }.forEach { child ->
                    SosBanner(
                        childName = child.name,
                        deactivating = deactivatingSosFor == child.id,
                        onDeactivate = {
                            deactivatingSosFor = child.id
                            viewModel.cancelSos(child.id) { ok ->
                                deactivatingSosFor = null
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
                StatusCardRow(
                    children = children,
                    deviceStates = deviceStates,
                    onRequestLocation = { childId, onResult -> viewModel.requestLocation(childId, onResult) },
                )
            }
            EventsList(
                childEvents = children.flatMap { child ->
                    eventsByChild[child.id].orEmpty().map { ChildEvent(child.name, it) }
                }.sortedByDescending { it.event.timestampMillis },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun SosBanner(childName: String, deactivating: Boolean, onDeactivate: () -> Unit) {
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
                text = stringResource(R.string.sos_banner_title_named, childName),
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

// v0.8.0: riga orizzontale scorrevole di status-card, una per bambino —
// prima una singola StatusCard fissa (un solo bambino possibile). Ogni
// card gestisce il proprio stato "richiesta in corso", legato all'esito
// asincrono reale della chiamata (non un fire-and-forget sincrono).
@Composable
private fun StatusCardRow(
    children: List<ChildInfo>,
    deviceStates: Map<String, DeviceState>,
    onRequestLocation: (childId: String, onResult: (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (children.isEmpty()) return
    val context = LocalContext.current
    LazyRow(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        items(children) { child ->
            var requesting by remember(child.id) { mutableStateOf(false) }
            StatusCard(
                childName = child.name,
                state = deviceStates[child.id] ?: DeviceState(),
                requesting = requesting,
                onRequestLocation = {
                    requesting = true
                    onRequestLocation(child.id) { ok ->
                        requesting = false
                        val feedbackRes = if (ok) {
                            R.string.map_request_location_sent
                        } else {
                            R.string.map_request_location_failed
                        }
                        Toast.makeText(context, context.getString(feedbackRes), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.width(260.dp).padding(start = 12.dp, end = 4.dp),
            )
        }
    }
}

// v0.7.0: prima card separata (MapControls) sotto la StatusCard —
// eliminata, il pulsante ora sta sulla stessa riga dello stato (vedi
// StatusCard sotto) e lo switch "Percorso 24h" e' salito in TopAppBar.
@Composable
private fun StatusCard(
    childName: String,
    state: DeviceState,
    requesting: Boolean,
    onRequestLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = childName, style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = state.lastSeenMillis?.let { formatRelativeTime(it) }
                            ?: stringResource(R.string.no_data_yet),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.battery?.let { battery ->
                        Text(
                            text = stringResource(R.string.battery_format, battery),
                            style = MaterialTheme.typography.bodySmall,
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
}

@Composable
private fun EventsList(childEvents: List<ChildEvent>, modifier: Modifier = Modifier) {
    if (childEvents.isEmpty()) return
    val formatter = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    LazyColumn(modifier = modifier.fillMaxWidth().heightIn(max = 160.dp).padding(horizontal = 12.dp)) {
        items(childEvents) { childEvent ->
            val event = childEvent.event
            Text(
                text = "${childEvent.childName} — ${eventLabel(event.type, event.zoneName)} · " +
                    formatter.format(Date(event.timestampMillis)),
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
