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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.gwatch.childtracker.phone.data.model.GeofenceZone
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon

/**
 * Gestione zone (casa/scuola, MVP): tocco sulla mappa per scegliere il
 * centro, pannello con nome + raggio (con anteprima), lista delle zone
 * esistenti con attiva/disattiva, modifica e cancellazione. Scrittura
 * diretta su Firestore (permessa dalle regole solo al genitore
 * autenticato, vedi backend/firestore.rules) — nessun endpoint backend
 * dedicato: il watch legge le zone da /api/device-config in autonomia.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val geofences by viewModel.geofences.collectAsState()

    var pickedPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    var notifyOnEnter by remember { mutableStateOf(true) }
    var notifyOnExit by remember { mutableStateOf(true) }
    var alarmOnExit by remember { mutableStateOf(false) }
    // Zona in modifica (tocco su "Modifica" in lista) invece che nuova
    // (tocco sulla mappa): null -> "Salva" crea, non-null -> aggiorna
    // lo stesso documento preservando lo stato active esistente.
    var editingZone by remember { mutableStateOf<GeofenceZone?>(null) }

    fun resetForm() {
        name = ""
        pickedPoint = null
        radius = 150f
        notifyOnEnter = true
        notifyOnExit = true
        alarmOnExit = false
        editingZone = null
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

            Column(modifier = Modifier.align(Alignment.TopCenter)) {
                if (pickedPoint == null) {
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
                } else {
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
                                )
                                viewModel.saveGeofence(zone) { resetForm() }
                            }
                        },
                        onCancel = { resetForm() },
                    )
                }
            }

            ZoneList(
                geofences = geofences,
                onToggle = { zone, checked -> viewModel.saveGeofence(zone.copy(active = checked)) {} },
                onDelete = { zone -> viewModel.deleteGeofence(zone.id) },
                onEdit = { zone ->
                    pickedPoint = GeoPoint(zone.lat, zone.lon)
                    name = zone.name
                    radius = zone.radiusMeters.toFloat()
                    notifyOnEnter = zone.notifyOnEnter
                    notifyOnExit = zone.notifyOnExit
                    alarmOnExit = zone.alarmOnExit
                    editingZone = zone
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.geofence_name_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(text = stringResource(R.string.geofence_radius_label, radius.toInt()))
            Slider(value = radius, onValueChange = onRadiusChange, valueRange = 50f..1000f)

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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSave) { Text(stringResource(R.string.save_geofence)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.geofence_cancel)) }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label)
            if (hint != null) {
                Text(text = hint, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ZoneList(
    geofences: List<GeofenceZone>,
    onToggle: (GeofenceZone, Boolean) -> Unit,
    onDelete: (GeofenceZone) -> Unit,
    onEdit: (GeofenceZone) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (geofences.isEmpty()) return
    Card(
        modifier = modifier.fillMaxWidth().heightIn(max = 220.dp).padding(12.dp),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        LazyColumn {
            itemsIndexed(geofences) { index, zone ->
                if (index > 0) Divider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
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
            }
        }
    }
}
