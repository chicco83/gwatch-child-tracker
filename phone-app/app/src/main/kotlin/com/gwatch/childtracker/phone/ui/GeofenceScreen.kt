package com.gwatch.childtracker.phone.ui

// Storico versioni
// v0.1.0 (2026-09-09): prima versione — mappa ad altezza fissa
//   (Modifier.height(220.dp)) sopra un form statico e la lista zone,
//   tutto in una Column.
// v0.2.0 (2026-09-10): feedback utente dopo test su device reale: "la
//   mappa occupa meta' schermo, il resto e' bianco" (la Column con
//   Divider/LazyColumn sotto non riempiva lo spazio restante quando le
//   zone erano poche/assenti) e "manca una barra con degli strumenti
//   per disegnare le zone, cancellarle ecc". Riscritto sul pattern
//   Box+align gia' usato in MapScreen.kt (mappa a Modifier.fillMaxSize(),
//   niente piu' altezza fissa): pannello "strumenti" flottante in alto
//   (Salva/Annulla + nome/raggio quando si sta piazzando una zona,
//   altrimenti solo il suggerimento di toccare la mappa), lista zone
//   flottante in basso con sfondo opaco (prima trasparente in
//   MapScreen — stesso appunto ricevuto li', applicato qui subito).
//   In piu': le zone esistenti ora si vedono come cerchi sulla mappa
//   anche qui (prima solo su MapScreen, qui invisibili — impossibile
//   valutare sovrapposizioni mentre se ne aggiunge una nuova), e il
//   raggio scelto con lo slider ha un'anteprima live come cerchio
//   intorno al punto scelto.
// v0.3.0 (2026-09-10): richiesta esplicita — toggle separati "notifica
//   solo in ingresso"/"solo in uscita" per zona, e un toggle "allarme
//   ripetuto sul telefono" per l'uscita (vedi
//   phone-app/.../alarm/ExitAlarmService.kt, backend/api/
//   trigger-event.js). Il pannello di creazione (NewZoneToolbar) ora
//   ha 3 switch in piu'; la lista zone ha un pulsante "Modifica" oltre
//   ad "Elimina" (prima non esisteva un modo per cambiare nome/raggio/
//   notifiche di una zona gia' salvata, solo attivarla/disattivarla o
//   cancellarla) — tocca la zona in lista, il pannello in alto si apre
//   precompilato, "Salva" aggiorna la zona esistente invece di crearne
//   una nuova (editingZone != null -> stesso id, "active" preservato).
// v0.4.0 (2026-09-10): tre correzioni dopo altro giro di feedback:
//   1) la mappa partiva sempre centrata su Roma (coordinate fisse nel
//      codice) senza modo di cercare un indirizzo — aggiunta una barra
//      di ricerca (Nominatim/OpenStreetMap, vedi GeocodingClient.kt)
//      in alto: selezionare un risultato centra la mappa li' e apre
//      direttamente il pannello di creazione zona su quel punto.
//   2) il pannello di creazione zona (NewZoneToolbar) era ancorato in
//      alto a schermo fisso: se si toccava la mappa vicino alla cima,
//      il pannello finiva esattamente sopra al punto appena scelto,
//      nascondendolo. Spostato in basso (Alignment.BottomCenter),
//      sostituendo la lista zone li' mentre e' aperto (i due non
//      possono stare nello stesso posto contemporaneamente, e mentre
//      si sta creando/modificando una zona la lista non serve).
//   3) il raggio minimo dello slider (50m) non era un limite tecnico
//      di alcun tipo (ne' della Geofencing API di Android ne' di
//      osmdroid), solo il range scelto nel codice — allargato a
//      20-2000m. Sotto ai 30-50m circa il rischio di falsi ingressi/
//      uscite per il solo rumore del GPS aumenta (mitigato in parte dal
//      loitering delay di 30s sull'uscita, vedi GeofenceSyncWorker.kt
//      sul watch), quindi il minimo resta comunque non-zero.
// v0.5.0 (2026-09-10): richiesta utente — "nelle zone quando clicco
//   sulla modifica di una zona la mappa deve autocentrarsi sulla
//   posizione di tale zona". Prima onEdit (vedi ZoneList piu' sotto)
//   aggiornava solo lo stato Compose (pickedPoint, name, radius, ecc.)
//   ma non spostava la camera della mappa: se lo zoom/pan corrente era
//   lontano dalla zona da modificare, il pannello si apriva ma la zona
//   restava fuori schermo. Aggiunta la stessa chiamata gia' usata in
//   pickSearchResult() (mapView.controller.animateTo + setZoom(17.0))
//   dentro onEdit.
// v0.6.0 (2026-09-11): supporto N bambini (fase 2/4, vedi CONTEXT.md) —
//   le geofence sono ora una risorsa condivisa (GeofenceZone.childIds),
//   non piu' implicitamente "del" singolo device. Aggiunta una riga di
//   toggle per bambino sia nel pannello di creazione/modifica
//   (NewZoneToolbar) sia su ogni riga della lista (ZoneList), cosi' il
//   genitore sceglie a chi si applica ciascuna zona. Per retrocompatibilita'
//   una nuova zona parte con tutti i bambini gia' selezionati (stesso
//   comportamento di oggi con un solo bambino), una zona in modifica
//   riparte dalla sua selezione salvata.
// v0.7.0 (2026-09-18): tre correzioni UI richieste dall'utente dopo il
//   primo test reale con le zone finalmente visibili (fix regole
//   Firestore, vedi CHANGELOG.md):
//   1) AddressSearchBar: il campo di ricerca aveva la label fluttuante
//      "Cerca un indirizzo" (che riserva spazio sopra al testo) PIU' una
//      riga separata sotto con il pulsante testuale "Cerca" — due
//      elementi che allungavano la card. Sostituita la label con un
//      placeholder (piu' basso) e spostata la lente di ingrandimento
//      DENTRO il campo come trailingIcon: eliminata la riga pulsante,
//      un solo controllo invece di due.
//   2) ZoneList: righe zona compattate (meno padding verticale) e
//      aggiunta una vera scrollbar (drawWithContent su LazyListState,
//      nessuna dipendenza esterna: Compose Material3 in questa versione
//      non ha uno scrollbar built-in per Android, solo per desktop) per
//      far capire visivamente che ci sono altre zone oltre quelle a
//      schermo, invece di doverlo scoprire scorrendo alla cieca.
//   3) ToggleRow (usata sia per i toggle notifica/allarme sia per il
//      toggle "assegna a"): lo switch di "Allarme sonoro all'uscita"
//      risultava disallineato/tagliato a schermo. Causa: label+hint
//      stavano in una Column senza vincolo di larghezza (niente
//      Modifier.weight() disponibile in questa versione di Compose,
//      vedi nota storica altrove nel progetto) nella stessa Row dello
//      Switch con SpaceBetween — con un hint lungo la Column cresceva
//      oltre lo spazio disponibile, spingendo lo Switch fuori dai
//      margini della card invece di andare a capo. Stessa soluzione
//      gia' usata per il pulsante "Aggiorna posizione" schiacciato in
//      MapScreen.kt/StatusCard: due righe impilate invece di una sola
//      (label+switch sopra, hint sotto a piena larghezza, libero di
//      andare a capo).
// v0.8.0 (2026-09-18): DND automatico per zona, richiesto dall'utente
//   ("quando arriva a scuola va in dnd in automatico"). Nuovo toggle
//   "dndOnZone" nel pannello di creazione/modifica — un solo flag per
//   entrambe le direzioni: il watch attiva il "Non disturbare" di
//   sistema all'ingresso e lo disattiva all'uscita (vedi
//   backend/api/trigger-event.js v0.14.0, che risolve il campo e lo
//   restituisce al watch nella risposta della stessa chiamata gia'
//   fatta per notificare la transizione, e watch-app/.../dnd/
//   DndController.kt, che applica il cambio). Nessun cambiamento
//   nell'endpoint /api/device-config: la decisione se attivare/
//   disattivare il DND si risolve interamente al momento della
//   transizione (trigger-event), non serve al watch conoscere in
//   anticipo quali zone hanno il flag per registrare le geofence.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gwatch.childtracker.phone.R
import com.gwatch.childtracker.phone.data.GeocodingClient
import com.gwatch.childtracker.phone.data.GeocodingResult
import com.gwatch.childtracker.phone.data.model.ChildInfo
import com.gwatch.childtracker.phone.data.model.GeofenceZone
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon

private val RADIUS_RANGE = 20f..2000f

/**
 * Gestione zone (casa/scuola, MVP): ricerca indirizzo o tocco sulla
 * mappa per scegliere il centro, pannello con nome + raggio (con
 * anteprima), lista delle zone esistenti con attiva/disattiva,
 * modifica e cancellazione. Scrittura diretta su Firestore (permessa
 * dalle regole solo al genitore autenticato, vedi
 * backend/firestore.rules) — nessun endpoint backend dedicato: il
 * watch legge le zone da /api/device-config in autonomia.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val geofences by viewModel.geofences.collectAsState()
    val children by viewModel.children.collectAsState()

    var pickedPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    var notifyOnEnter by remember { mutableStateOf(true) }
    var notifyOnExit by remember { mutableStateOf(true) }
    var alarmOnExit by remember { mutableStateOf(false) }
    // v0.8.0 (2026-09-18): DND automatico per zona, richiesto
    // dall'utente ("quando arriva a scuola va in dnd in automatico") —
    // vedi Storico versioni in cima al file.
    var dndOnZone by remember { mutableStateOf(false) }
    // A chi si applica la zona in creazione/modifica (v0.6.0) — di
    // default tutti i bambini conosciuti per una zona nuova (stesso
    // comportamento di oggi con un solo bambino), oppure la selezione
    // gia' salvata quando si modifica una zona esistente (vedi
    // LaunchedEffect piu' sotto).
    var selectedChildIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Zona in modifica (tocco su "Modifica" in lista) invece che nuova
    // (tocco sulla mappa/ricerca indirizzo): null -> "Salva" crea,
    // non-null -> aggiorna lo stesso documento preservando lo stato
    // active esistente.
    var editingZone by remember { mutableStateOf<GeofenceZone?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeocodingResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val geocodingClient = remember { GeocodingClient() }
    val scope = rememberCoroutineScope()

    fun resetForm() {
        name = ""
        pickedPoint = null
        radius = 150f
        notifyOnEnter = true
        notifyOnExit = true
        alarmOnExit = false
        dndOnZone = false
        selectedChildIds = emptySet()
        editingZone = null
    }

    // Precompila la selezione dei bambini quando si apre il pannello: la
    // selezione salvata se si sta modificando una zona esistente, tutti i
    // bambini conosciuti se se ne sta piazzando una nuova.
    LaunchedEffect(pickedPoint) {
        if (pickedPoint != null) {
            selectedChildIds = editingZone?.childIds?.toSet() ?: children.map { it.id }.toSet()
        }
    }

    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(13.0)
            controller.setCenter(GeoPoint(41.9028, 12.4964))
            overlays.add(
                MapEventsOverlay(
                    object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            editingZone = null
                            pickedPoint = p
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint): Boolean = false
                    },
                ),
            )
        }
    }
    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

    fun runSearch() {
        val query = searchQuery
        if (query.isBlank()) return
        searching = true
        scope.launch {
            searchResults = geocodingClient.search(query)
            searching = false
        }
    }

    fun pickSearchResult(result: GeocodingResult) {
        val point = GeoPoint(result.lat, result.lon)
        mapView.controller.animateTo(point)
        mapView.controller.setZoom(17.0)
        editingZone = null
        pickedPoint = point
        searchQuery = ""
        searchResults = emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.geofences_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { map ->
                    map.overlays.removeAll { it is Marker || it is Polygon }

                    // Zone gia' salvate: visibili anche qui, non solo su
                    // MapScreen, per poter valutare sovrapposizioni
                    // mentre se ne aggiunge una nuova.
                    geofences.forEach { zone ->
                        map.overlays.add(
                            Polygon().apply {
                                setPoints(Polygon.pointsAsCircle(GeoPoint(zone.lat, zone.lon), zone.radiusMeters))
                                fillColor = if (zone.active) 0x334285F4.toInt() else 0x339E9E9E.toInt()
                                strokeColor = if (zone.active) 0xFF4285F4.toInt() else 0xFF9E9E9E.toInt()
                                strokeWidth = 3f
                            },
                        )
                    }

                    // Punto appena scelto/in modifica, non ancora
                    // salvato: marker + anteprima del raggio scelto con
                    // lo slider, colore diverso (verde) per distinguerlo
                    // dalle zone gia' salvate.
                    pickedPoint?.let { point ->
                        map.overlays.add(Marker(map).apply { position = point })
                        map.overlays.add(
                            Polygon().apply {
                                setPoints(Polygon.pointsAsCircle(point, radius.toDouble()))
                                fillColor = 0x3334A853.toInt()
                                strokeColor = 0xFF34A853.toInt()
                                strokeWidth = 4f
                            },
                        )
                    }

                    map.invalidate()
                },
            )

            // Barra di ricerca indirizzo + suggerimento tocco: solo
            // quando non si sta creando/modificando una zona (in quel
            // caso il pannello sotto occupa gia' la parte bassa, e
            // questa barra in piu' in alto affollerebbe lo schermo
            // senza motivo — per cercare un altro punto si puo' sempre
            // Annullare prima).
            if (pickedPoint == null) {
                Column(modifier = Modifier.align(Alignment.TopCenter)) {
                    AddressSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { runSearch() },
                        searching = searching,
                        results = searchResults,
                        onResultClick = { pickSearchResult(it) },
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        elevation = CardDefaults.cardElevation(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.geofence_tap_hint),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // In basso: il pannello di creazione/modifica zona quando
            // c'e' un punto scelto, altrimenti la lista delle zone
            // esistenti — mai insieme, non c'e' spazio per entrambi e
            // mentre si piazza una zona la lista non serve. Prima il
            // pannello era ancorato in alto a schermo fisso: se si
            // toccava la mappa vicino alla cima, finiva esattamente
            // sopra al punto appena scelto, nascondendolo.
            if (pickedPoint != null) {
                NewZoneToolbar(
                    name = name,
                    onNameChange = { name = it },
                    radius = radius,
                    onRadiusChange = { radius = it },
                    notifyOnEnter = notifyOnEnter,
                    onNotifyOnEnterChange = { notifyOnEnter = it },
                    notifyOnExit = notifyOnExit,
                    onNotifyOnExitChange = { notifyOnExit = it },
                    alarmOnExit = alarmOnExit,
                    onAlarmOnExitChange = { alarmOnExit = it },
                    dndOnZone = dndOnZone,
                    onDndOnZoneChange = { dndOnZone = it },
                    children = children,
                    selectedChildIds = selectedChildIds,
                    onChildToggle = { childId, checked ->
                        selectedChildIds = if (checked) selectedChildIds + childId else selectedChildIds - childId
                    },
                    onSave = {
                        val picked = pickedPoint ?: return@NewZoneToolbar
                        if (name.isNotBlank()) {
                            val zone = GeofenceZone(
                                id = editingZone?.id ?: "",
                                name = name,
                                lat = picked.latitude,
                                lon = picked.longitude,
                                radiusMeters = radius.toDouble(),
                                active = editingZone?.active ?: true,
                                notifyOnEnter = notifyOnEnter,
                                notifyOnExit = notifyOnExit,
                                alarmOnExit = alarmOnExit,
                                childIds = selectedChildIds.toList(),
                                dndOnZone = dndOnZone,
                            )
                            viewModel.saveGeofence(zone) { resetForm() }
                        }
                    },
                    onCancel = { resetForm() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                ZoneList(
                    geofences = geofences,
                    children = children,
                    onToggle = { zone, checked -> viewModel.saveGeofence(zone.copy(active = checked)) {} },
                    onDelete = { zone -> viewModel.deleteGeofence(zone.id) },
                    onAssignChange = { zone, childId, checked ->
                        val updated = if (checked) zone.childIds + childId else zone.childIds - childId
                        viewModel.saveGeofence(zone.copy(childIds = updated)) {}
                    },
                    onEdit = { zone ->
                        // v0.4.0 (precedente): solo stato Compose, la
                        // mappa restava dove si trovava prima del tocco
                        // su "Modifica" — se la zona era fuori dallo
                        // zoom/pan corrente, il pannello si apriva su un
                        // punto non visibile a schermo.
                        // v0.5.0 (2026-09-10): stessa chiamata di
                        // pickSearchResult() per centrare la mappa sulla
                        // zona che si sta per modificare.
                        val point = GeoPoint(zone.lat, zone.lon)
                        mapView.controller.animateTo(point)
                        mapView.controller.setZoom(17.0)
                        pickedPoint = point
                        name = zone.name
                        radius = zone.radiusMeters.toFloat()
                        notifyOnEnter = zone.notifyOnEnter
                        notifyOnExit = zone.notifyOnExit
                        alarmOnExit = zone.alarmOnExit
                        dndOnZone = zone.dndOnZone
                        editingZone = zone
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/**
 * Ricerca indirizzo (Nominatim/OpenStreetMap, vedi GeocodingClient.kt):
 * la mappa partiva sempre centrata su Roma senza alcun modo di
 * spostarsi rapidamente su un indirizzo vero. Selezionare un risultato
 * centra la mappa li' e apre direttamente il pannello di creazione zona
 * (stesso comportamento di un tocco sulla mappa in quel punto).
 */
@Composable
private fun AddressSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    searching: Boolean,
    results: List<GeocodingResult>,
    onResultClick: (GeocodingResult) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // v0.7.0: placeholder invece di label (niente piu' spazio
            // riservato sopra al testo per la label fluttuante) + lente
            // di ingrandimento come trailingIcon dentro il campo stesso,
            // al posto della riga separata col pulsante testuale "Cerca"
            // che c'era prima sotto — un solo controllo, card piu' bassa.
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.geofence_search_hint)) },
                singleLine = true,
                trailingIcon = {
                    if (searching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onSearch, enabled = query.isNotBlank()) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.geofence_search_button),
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            results.forEach { result ->
                TextButton(onClick = { onResultClick(result) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = result.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/**
 * Pannello "strumenti" per la zona in fase di creazione/modifica: nome,
 * raggio (con anteprima live sulla mappa, vedi update() sopra), i toggle
 * di notifica/allarme e i due pulsanti Salva/Annulla — prima non
 * esisteva un modo esplicito per annullare un punto scelto per errore,
 * solo ritoccare la mappa.
 */
@Composable
private fun NewZoneToolbar(
    name: String,
    onNameChange: (String) -> Unit,
    radius: Float,
    onRadiusChange: (Float) -> Unit,
    notifyOnEnter: Boolean,
    onNotifyOnEnterChange: (Boolean) -> Unit,
    notifyOnExit: Boolean,
    onNotifyOnExitChange: (Boolean) -> Unit,
    alarmOnExit: Boolean,
    onAlarmOnExitChange: (Boolean) -> Unit,
    dndOnZone: Boolean,
    onDndOnZoneChange: (Boolean) -> Unit,
    children: List<ChildInfo>,
    selectedChildIds: Set<String>,
    onChildToggle: (String, Boolean) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(12.dp), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.geofence_name_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(text = stringResource(R.string.geofence_radius_label, radius.toInt()))
            Slider(value = radius, onValueChange = onRadiusChange, valueRange = RADIUS_RANGE)

            ToggleRow(
                label = stringResource(R.string.geofence_notify_enter),
                checked = notifyOnEnter,
                onCheckedChange = onNotifyOnEnterChange,
            )
            ToggleRow(
                label = stringResource(R.string.geofence_notify_exit),
                checked = notifyOnExit,
                onCheckedChange = onNotifyOnExitChange,
            )
            ToggleRow(
                label = stringResource(R.string.geofence_alarm_on_exit),
                hint = stringResource(R.string.geofence_alarm_on_exit_hint),
                checked = alarmOnExit,
                onCheckedChange = onAlarmOnExitChange,
            )
            // v0.8.0 (2026-09-18): DND automatico per zona, richiesto
            // dall'utente — un solo toggle per entrambe le direzioni
            // (vedi Storico versioni in cima al file e
            // backend/api/trigger-event.js v0.14.0).
            ToggleRow(
                label = stringResource(R.string.geofence_dnd_on_zone),
                hint = stringResource(R.string.geofence_dnd_on_zone_hint),
                checked = dndOnZone,
                onCheckedChange = onDndOnZoneChange,
            )

            ChildToggleSection(
                children = children,
                selectedChildIds = selectedChildIds,
                onChildToggle = onChildToggle,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSave) { Text(stringResource(R.string.save_geofence)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.geofence_cancel)) }
            }
        }
    }
}

/**
 * Selettore "a chi si applica questa zona" (v0.6.0, supporto N bambini):
 * un toggle per bambino conosciuto, usato sia nel pannello di creazione/
 * modifica (NewZoneToolbar) sia su ogni riga della lista zone (ZoneList).
 */
@Composable
private fun ChildToggleSection(
    children: List<ChildInfo>,
    selectedChildIds: Set<String>,
    onChildToggle: (String, Boolean) -> Unit,
) {
    if (children.isEmpty()) {
        Text(
            text = stringResource(R.string.geofence_no_children_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Column {
        Text(text = stringResource(R.string.geofence_assign_label), style = MaterialTheme.typography.bodySmall)
        children.forEach { child ->
            ToggleRow(
                label = child.name,
                checked = child.id in selectedChildIds,
                onCheckedChange = { onChildToggle(child.id, it) },
            )
        }
    }
}

// v0.7.0 (2026-09-18): vedi Storico versioni in cima al file, punto 3 —
// prima label+hint stavano in una Column senza vincolo di larghezza
// nella stessa Row dello Switch (niente Modifier.weight() disponibile),
// per cui un hint lungo ("Allarme sonoro all'uscita") spingeva lo
// Switch fuori dai margini della card. Ora due righe impilate: label +
// switch sopra (corti, mai in conflitto), hint sotto a piena larghezza,
// libero di andare a capo.
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null,
) {
    // v0.7.0: padding verticale ridotto (4dp -> 2dp), stessa richiesta di
    // compattazione della lista zone qui sopra — questa Row e' riusata
    // anche per ogni riga "assegna a" dentro ZoneList.
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (hint != null) {
            Text(text = hint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// v0.7.0 (2026-09-18): vedi Storico versioni in cima al file, punto 2 —
// niente scrollbar built-in per Android in questa versione di Compose
// Material3 (solo per desktop, rememberScrollbarAdapter non e'
// disponibile qui), quindi disegnata a mano con drawWithContent sopra
// la LazyColumn: una barretta verticale la cui altezza/posizione riflette
// quanti elementi sono visibili rispetto al totale — cosi' si vede subito
// se ci sono altre zone oltre quelle a schermo invece di scoprirlo
// scorrendo alla cieca. Nessuna dipendenza esterna aggiunta.
private fun Modifier.verticalScrollbar(state: LazyListState, color: Color, width: Dp = 4.dp): Modifier =
    drawWithContent {
        drawContent()
        val layoutInfo = state.layoutInfo
        val totalItems = layoutInfo.totalItemsCount
        val visibleItems = layoutInfo.visibleItemsInfo
        if (totalItems == 0 || visibleItems.isEmpty() || visibleItems.size >= totalItems) return@drawWithContent

        val thumbHeightPx = size.height * (visibleItems.size.toFloat() / totalItems.toFloat())
        val maxScrollableItems = (totalItems - visibleItems.size).coerceAtLeast(1)
        val scrollProgress = state.firstVisibleItemIndex.toFloat() / maxScrollableItems
        val thumbOffsetY = (size.height - thumbHeightPx) * scrollProgress
        val widthPx = width.toPx()

        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - widthPx, thumbOffsetY),
            size = Size(widthPx, thumbHeightPx),
            cornerRadius = CornerRadius(widthPx / 2f),
        )
    }

@Composable
private fun ZoneList(
    geofences: List<GeofenceZone>,
    children: List<ChildInfo>,
    onToggle: (GeofenceZone, Boolean) -> Unit,
    onDelete: (GeofenceZone) -> Unit,
    onAssignChange: (GeofenceZone, String, Boolean) -> Unit,
    onEdit: (GeofenceZone) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (geofences.isEmpty()) return
    val listState = rememberLazyListState()
    val scrollbarColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Card(
        modifier = modifier.fillMaxWidth().heightIn(max = 320.dp).padding(12.dp),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().verticalScrollbar(listState, scrollbarColor).padding(end = 8.dp),
        ) {
            itemsIndexed(geofences) { index, zone ->
                if (index > 0) Divider()
                // v0.7.0: padding verticale ridotto (12dp -> 6dp) per
                // compattare ogni riga zona, richiesto dall'utente dopo
                // aver visto poche zone occupare gia' quasi tutta la
                // card — piu' righe visibili senza scorrere.
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(zone.name)
                            Text(
                                text = stringResource(R.string.geofence_radius_label, zone.radiusMeters.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = zone.active, onCheckedChange = { onToggle(zone, it) })
                            TextButton(onClick = { onEdit(zone) }) { Text(stringResource(R.string.geofence_edit)) }
                            TextButton(onClick = { onDelete(zone) }) { Text(stringResource(R.string.delete)) }
                        }
                    }
                    ChildToggleSection(
                        children = children,
                        selectedChildIds = zone.childIds.toSet(),
                        onChildToggle = { childId, checked -> onAssignChange(zone, childId, checked) },
                    )
                }
            }
        }
    }
}
