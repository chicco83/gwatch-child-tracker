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
// v0.9.0 (2026-09-18): aggiunta la versione (BuildConfig.VERSION_NAME)
//   nella TopAppBar, accanto al nome app (vedi Scaffold sotto) —
//   richiesto dall'utente per verificare a schermo se la build
//   installata sia davvero l'ultima compilata.
// v0.10.0 (2026-09-18): la StatusCard (v0.8.0 sopra) risultava alta
//   circa meta' schermo, segnalato dall'utente con screenshot — vedi
//   commento sulla LazyRow in StatusCardRow per la causa
//   (heightIn(max) mancante, a differenza di EventsList) e il fix.
// v0.11.0 (2026-09-18): ancora segnalato dall'utente dopo v0.10.0 — la
//   card doveva essere larga quanto lo schermo e piu' bassa, e il
//   colore soglia della batteria copriva anche l'etichetta "Batteria
//   watch:", non solo il valore. Sostituita la LazyRow di
//   StatusCardRow con una Column + forEach (elimina alla radice il
//   problema v0.10.0 invece di limitarsi a un heightIn), card a riga
//   singola invece che a due righe impilate (piu' bassa), etichetta e
//   valore batteria in due Text separati (solo il valore e' colorato).
// v0.12.0 (2026-09-22): richiesto dall'utente — due aggiunte:
//   (1) all'apertura dell'app, richiede automaticamente la posizione a
//   tutti i bambini noti (stessa chiamata del pulsante "Aggiorna
//   posizione", vedi AppViewModel.requestLocation), una sola volta per
//   apertura tramite un flag "hasAutoRequestedLocation" invece che ad
//   ogni ricomposizione; (2) "Ultima posizione" ora mostra anche data e
//   ora assolute oltre al relativo ("5 min fa"), utile per capire "di
//   che giorno" quando il dato e' vecchio di ore (vedi formatLastSeen
//   sotto, stesso formato "dd/MM HH:mm" gia' usato in EventsList).

import android.widget.Toast
import com.gwatch.childtracker.phone.BuildConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.gwatch.childtracker.phone.data.model.LocationRetry
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.roundToInt
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

// 2026-09-18 (v0.10.0): rimossa la soglia minima sotto cui la velocita'
// non veniva mostrata (era 2.0 km/h, per filtrare il "rumore" GPS di un
// watch fermo) — richiesto dall'utente un formato fisso a 4 righe in
// StatusCard sempre presenti quando il dato arriva, niente piu' righe
// che appaiono/spariscono in base alla velocita' istantanea.

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
    // 2026-09-23: conto alla rovescia dei nuovi tentativi (AppViewModel.requestLocation).
    val retryStates by viewModel.retryStates.collectAsState()
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

    // v0.12.0: richiesta automatica della posizione all'apertura
    // dell'app per tutti i bambini noti — stessa chiamata del pulsante
    // "Aggiorna posizione", nessun nuovo codice lato backend/watch.
    // "children" arriva vuoto al primo istante (listener Firestore
    // ancora da popolare) e via via si aggiorna: il flag garantisce che
    // scatti una sola volta per apertura, alla prima lista non vuota,
    // invece di ripartire ad ogni ricomposizione o cambio di
    // "children" successivo (es. un bambino rinominato).
    var hasAutoRequestedLocation by remember { mutableStateOf(false) }
    LaunchedEffect(children) {
        if (!hasAutoRequestedLocation && children.isNotEmpty()) {
            hasAutoRequestedLocation = true
            children.forEach { child -> viewModel.requestLocation(child.id) {} }
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
                // 2026-09-18: aggiunta la versione (BuildConfig.VERSION_NAME)
                // accanto al nome app — richiesto dall'utente per poter
                // verificare da schermo, senza aprire le Impostazioni di
                // sistema, se l'ultima build compilata sia davvero quella
                // installata (rilevante durante questa diagnosi: una build
                // vecchia non reinstallata per errore avrebbe lo stesso
                // comportamento del bug in corso).
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
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
                    retryStates = retryStates,
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

// v0.8.0: una card per bambino, colonna verticale (non piu' riga
// scorrevole, vedi v0.11.0 sotto). Ogni card gestisce il proprio stato
// "richiesta in corso", legato all'esito asincrono reale della chiamata
// (non un fire-and-forget sincrono).
// v0.11.0 (2026-09-18): richiesta utente — la card doveva essere larga
// quanto lo schermo (non piu' una LazyRow di card strette da 260dp) e
// piu' bassa/compatta. Sostituita la LazyRow con una semplice Column +
// forEach: con pochi bambini (caso reale di questo progetto) non serve
// virtualizzazione, ed elimina alla radice il problema di v0.10.0
// (LazyRow/LazyColumn senza vincolo di altezza che si espande a
// riempire lo spazio disponibile) invece di limitarsi a tapparlo con
// heightIn — una Column normale si adatta sempre al proprio contenuto.
@Composable
private fun StatusCardRow(
    children: List<ChildInfo>,
    deviceStates: Map<String, DeviceState>,
    retryStates: Map<String, LocationRetry>,
    onRequestLocation: (childId: String, onResult: (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (children.isEmpty()) return
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        children.forEach { child ->
            var requesting by remember(child.id) { mutableStateOf(false) }
            StatusCard(
                childName = child.name,
                state = deviceStates[child.id] ?: DeviceState(),
                retry = retryStates[child.id],
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
    // 2026-09-23: prossimo tentativo automatico, null se nessuna attesa.
    retry: LocationRetry?,
    requesting: Boolean,
    onRequestLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Storico versioni (sezione batteria)
    // v0.9.0 (2026-09-18): la UX del battery indicator colorato
    // era corretta nell'idea ma rotta
    // nell'esecuzione — graffe/parentesi sbilanciate (il pulsante
    // "Aggiorna posizione" era rimasto incastrato dentro il Row della
    // batteria) e un LinearProgressIndicator con Modifier.weight(),
    // API interna in questa versione di Compose (vedi nota storica su
    // ChatScreen.kt/GeofenceScreen.kt): non compilava. Riscritta la
    // struttura, tenuto solo il colore testo (verde/arancio/rosso),
    // tolta la progress bar per non reintrodurre il vincolo weight().
    // v0.11.0 (2026-09-18): segnalato dall'utente — il colore
    // batteria era applicato a UN SOLO Text che conteneva l'intera
    // stringa "Batteria watch: 89%" (stringResource battery_format),
    // quindi anche l'etichetta "Batteria watch:" risultava colorata,
    // non solo il valore. Ora sono due Text separati nello stesso Row:
    // l'etichetta resta nel colore di default, solo "89%" e' colorato.
    // Contestualmente, card a riga singola invece che a due righe
    // impilate (vedi Storico versioni sopra su StatusCardRow): meno
    // alta, coerente con "abbassala" richiesto dall'utente.
    // v0.12.0 (2026-09-18): aggiunti temperatura batteria e stato di
    // carica, richiesti dall'utente (vedi BatteryInfo.kt lato watch).
    // Testo semplice invece di un'icona dedicata, coerente con l'uso di
    // emoji gia' in altri punti dell'app (🆘, 🔔) invece di importare
    // nuove risorse grafiche per un indicatore cosi' piccolo.
    // v0.13.0 (2026-09-18): segnalato dall'utente — il pulsante
    // "Aggiorna posizione" appariva "schiacciato" (il testo andava a
    // capo lettera per lettera in una colonna stretta). Causa: nome +
    // dettagli erano nella stessa Row del pulsante, e in questo
    // progetto Modifier.weight() nei Row non e' utilizzabile (vedi nota
    // storica in SettingsScreen.kt — incompatibilita' con la versione
    // di Compose fissata qui), quindi non si poteva far restringere
    // proporzionalmente la colonna dei dettagli per lasciare spazio al
    // pulsante. Con l'aggiunta di temperatura/carica/velocita' la riga
    // dettagli e' diventata piu' lunga, peggiorando la compressione.
    // Risolto separando le due righe: nome + pulsante insieme in alto
    // (corti, non competono mai per lo spazio), dettagli sotto su una
    // riga propria a piena larghezza, libera di andare a capo
    // normalmente (leggibile) invece di schiacciare il pulsante.
    // v0.14.0 (2026-09-18): richiesto dall'utente — i dettagli erano
    // tutti su un'unica riga separati da "·", difficili da leggere al
    // volo. Ora una riga per informazione (ultima posizione / batteria /
    // temperatura / velocita'), etichetta normale + valore in grassetto
    // (vedi InfoLine sotto). Rimossa anche la soglia minima di velocita'
    // (MIN_DISPLAYED_SPEED_KMH, 2 km/h) che nascondeva del tutto la riga
    // "Velocita'" quando il bambino si muoveva piano: con un formato
    // fisso a righe separate non ha piu' senso far apparire/sparire
    // righe, meglio mostrare il dato reale (anche 0 km/h) quando
    // disponibile.
    Card(modifier = modifier, elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 2026-09-23: icona di stato del watch accanto al nome
                // (modalita' aereo / spento / non raggiungibile), vedi
                // watchStatus(). Precedente: Text(text = childName, ...).
                val status = watchStatus(state, System.currentTimeMillis())
                Text(
                    text = if (status != null) "$childName ${status.first}" else childName,
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onRequestLocation, enabled = !requesting) {
                    Text(
                        stringResource(
                            if (requesting) R.string.map_requesting_location else R.string.map_request_location,
                        ),
                    )
                }
            }
            // 2026-09-23: "adesso" che avanza ogni 30s anche senza dati
            // nuovi (prima "adesso" restava fisso per minuti, segnalato
            // dall'utente). Precedente: formatLastSeen(it) senza nowMillis.
            val nowMillis by produceState(System.currentTimeMillis()) {
                while (true) {
                    kotlinx.coroutines.delay(30_000)
                    value = System.currentTimeMillis()
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                InfoLine(
                    label = stringResource(R.string.status_last_seen_label),
                    value = state.lastSeenMillis?.let { formatLastSeen(it, nowMillis) }
                        ?: stringResource(R.string.no_data_yet),
                )
                // 2026-09-23: ultimo contatto col watch anche senza
                // posizione (stato batteria inviato quando il GPS fallisce).
                // Mostrato solo se piu' recente dell'ultima posizione.
                val lastStatus = state.lastStatusMillis
                if (lastStatus != null && lastStatus > (state.lastSeenMillis ?: 0L)) {
                    InfoLine(
                        label = stringResource(R.string.status_last_contact_label),
                        value = formatLastSeen(lastStatus, nowMillis),
                    )
                }
                // 2026-09-23: satelliti dell'ultimo tentativo (richiesta utente);
                // "GPS non usato" se la posizione e' arrivata da Wi-Fi/rete.
                // Precedente: riga mostrata solo con satsVisible/satsUsed presenti.
                val satsText = when {
                    state.gnssActive == false -> stringResource(R.string.status_sats_not_used)
                    state.satsVisible != null && state.satsUsed != null ->
                        stringResource(R.string.status_sats_format, state.satsVisible, state.satsUsed)
                    else -> null
                }
                if (satsText != null) {
                    InfoLine(
                        label = stringResource(R.string.status_sats_label),
                        value = satsText +
                            (state.satsAtMillis?.let { " · " + formatRelativeTime(it, nowMillis) } ?: ""),
                    )
                }
                // 2026-09-23: stato del watch (modalita' aereo / spento / non
                // raggiungibile), con da quanto tempo.
                watchStatus(state, nowMillis)?.let { (_, labelRes, sinceMillis) ->
                    InfoLine(
                        label = stringResource(R.string.status_watch_label),
                        value = stringResource(labelRes) + " · " + formatLastSeen(sinceMillis, nowMillis),
                        valueColor = Color(0xFFC62828),
                    )
                }
                // 2026-09-23: posizione non ricevuta, nuovo tentativo in arrivo.
                retry?.let { RetryCountdown(it) }
                state.battery?.let { battery ->
                    InfoLine(
                        label = stringResource(R.string.battery_label),
                        value = stringResource(R.string.battery_format, battery) +
                            if (state.charging == true) " ⚡" else "",
                        // 2026-09-23: verde scuro al posto di Color.Green
                        // (verde fluo illeggibile, segnalato dall'utente).
                        // Precedente: battery > 50 -> Color.Green
                        valueColor = when {
                            battery > 50 -> Color(0xFF2E7D32)
                            battery > 25 -> Color(0xFFFFA000)
                            else -> Color.Red
                        },
                    )
                }
                // v0.15.0 (2026-09-19): autonomia residua, richiesta
                // dall'utente. Assente (null) mentre in carica o se il
                // watch non riesce a chiederla in modo affidabile al
                // proprio sistema operativo (vedi BatteryInfo.kt lato
                // watch) — nessun fallback qui, non abbiamo uno storico
                // lato phone-app per stimarla noi.
                state.batteryHoursRemaining?.let { hours ->
                    InfoLine(
                        label = stringResource(R.string.status_battery_hours_label),
                        value = formatHoursRemaining(hours),
                    )
                }
                state.batteryTemp?.let { temp ->
                    InfoLine(
                        label = stringResource(R.string.status_battery_temp_label),
                        value = String.format(Locale.getDefault(), "%.0f°C", temp),
                    )
                }
                // Location.getSpeed() e' in m/s, convertita qui in km/h
                // (unita' familiare) — la conversione e' l'unica cosa
                // fatta lato UI, il dato grezzo resta in m/s ovunque
                // altro (modello, backend, watch). Assente (null) finche'
                // il watch non manda un fix GPS con velocita' valida
                // (Location.hasSpeed()==true, tipicamente richiede un
                // minimo di movimento reale, non solo un fix stazionario).
                state.speedMps?.let { speedMps ->
                    InfoLine(
                        label = stringResource(R.string.status_speed_label),
                        value = String.format(Locale.getDefault(), "%.0f km/h", speedMps * 3.6),
                    )
                }
            }
        }
    }
}

/**
 * Una riga "etichetta: valore" per i dettagli di StatusCard — etichetta
 * in stile normale, valore in grassetto (v0.14.0, richiesto dall'utente
 * per distinguere a colpo d'occhio il dato dall'etichetta). valueColor
 * di default Color.Unspecified fa usare il colore di testo standard del
 * tema (stesso comportamento del parametro "color" di Text quando non
 * specificato), usato per il colore verde/arancio/rosso della batteria.
 */
/**
 * "~Xh Ymin" (o solo "~Ymin" sotto l'ora) per l'autonomia residua
 * stimata dal watch (vedi state.batteryHoursRemaining) — arrotondata
 * al minuto, con "~" davanti perche' e' comunque una stima del sistema
 * operativo del watch, non un valore esatto.
 */
private fun formatHoursRemaining(hours: Double): String {
    val totalMinutes = (hours * 60).roundToInt().coerceAtLeast(0)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "~${h}h ${m}min"
        h > 0 -> "~${h}h"
        else -> "~${m}min"
    }
}

/**
 * "5 min fa · 22/09 14:35" per "Ultima posizione" — il relativo da solo
 * (formatRelativeTime) non dice "di che giorno" quando il dato e'
 * vecchio di ore, richiesto dall'utente. Stesso formato "dd/MM HH:mm"
 * gia' usato in EventsList, per coerenza nell'app.
 */
private val lastSeenAbsoluteFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

// 2026-09-23: nowMillis per l'aggiornamento periodico. Precedente:
// private fun formatLastSeen(millis: Long): String =
//     "${formatRelativeTime(millis)} · ${lastSeenAbsoluteFormat.format(Date(millis))}"
private fun formatLastSeen(millis: Long, nowMillis: Long): String =
    "${formatRelativeTime(millis, nowMillis)} · ${lastSeenAbsoluteFormat.format(Date(millis))}"

/**
 * 2026-09-23: richiesta utente — barra traslucida con "Nuovo tentativo tra
 * Ns" che si svuota fino alla nuova richiesta automatica. Disegnata a mano
 * (Box + fillMaxWidth(frazione)), senza LinearProgressIndicator ne'
 * Modifier.weight (vincoli gia' incontrati in questo progetto). Ha un suo
 * orologio a 250 ms, indipendente da quello a 30 s della StatusCard.
 */
@Composable
private fun RetryCountdown(retry: LocationRetry) {
    val now by produceState(System.currentTimeMillis(), retry) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(250)
        }
    }
    val remainingMs = (retry.nextRetryAtMillis - now).coerceAtLeast(0L)
    val fraction = (remainingMs.toFloat() / retry.totalWaitMillis).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.Gray.copy(alpha = 0.15f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(Color(0xFF1976D2).copy(alpha = 0.30f)),
        )
        // 2026-09-23: testo diverso durante l'attesa della risposta del watch.
        val seconds = ((remainingMs + 999) / 1000).toInt()
        Text(
            text = if (retry.waitingForWatch) {
                stringResource(R.string.status_waiting_watch, seconds)
            } else {
                stringResource(R.string.status_retry_countdown, seconds, retry.attempt)
            },
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(text = "$label ", style = MaterialTheme.typography.bodySmall)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
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

/**
 * 2026-09-23: stato del watch da mostrare, null se tutto normale.
 * Ritorna (icona, testo, dal-quando). "Non raggiungibile" e' la rete di
 * sicurezza: vale anche quando l'avviso di modalita' aereo/spegnimento non
 * e' riuscito a partire dal watch (la rete sparisce quasi subito).
 */
private fun watchStatus(state: DeviceState, nowMillis: Long): Triple<String, Int, Long>? {
    val stateAt = state.watchStateAtMillis
    // Aereo/spento valgono solo se dopo non e' arrivato nient'altro dal
    // watch (l'avviso di rientro potrebbe non essere partito). Margine di
    // 60 s: lo stesso invio dell'avviso aggiorna lastStatusAt (ora del
    // server) pochi secondi dopo stateAt (ora del watch).
    val newerContact = listOfNotNull(state.lastSeenMillis, state.lastStatusMillis)
        .any { stateAt != null && it > stateAt + STATE_CONTACT_MARGIN_MS }
    if (stateAt != null && !newerContact) {
        when (state.watchState) {
            "airplane" -> return Triple("✈️", R.string.status_watch_airplane, stateAt)
            "off" -> return Triple("⏻", R.string.status_watch_off, stateAt)
        }
    }
    val lastContact = listOfNotNull(state.lastSeenMillis, state.lastStatusMillis, stateAt).maxOrNull()
        ?: return null
    return if (nowMillis - lastContact > UNREACHABLE_AFTER_MS) {
        Triple("📵", R.string.status_watch_unreachable, lastContact)
    } else {
        null
    }
}

// 2026-09-23: senza notizie dal watch da piu' di cosi' → "non raggiungibile".
// Il tracking da fermo manda punti almeno ogni 10', l'upload ogni 15'.
private const val UNREACHABLE_AFTER_MS = 30 * 60 * 1000L
private const val STATE_CONTACT_MARGIN_MS = 60 * 1000L

private fun eventLabel(type: String, zoneName: String?): String = when (type) {
    "sos" -> "🆘 SOS"
    "geofence_enter" -> "→ Entrato in ${zoneName ?: "zona"}"
    "geofence_exit" -> "← Uscito da ${zoneName ?: "zona"}"
    "location_request" -> "📍 Posizione inviata su richiesta"
    // 2026-09-23: eventi di stato del watch (trigger-event.js v0.22.0).
    "watch_airplane_on" -> "✈️ Watch in modalita' aereo"
    "watch_airplane_off" -> "✈️ Modalita' aereo disattivata"
    "watch_shutdown" -> "⏻ Watch spento"
    "watch_boot" -> "⏻ Watch riacceso"
    else -> type
}
