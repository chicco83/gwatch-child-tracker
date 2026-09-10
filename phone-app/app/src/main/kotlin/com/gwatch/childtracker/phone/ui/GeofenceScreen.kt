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
 * esistenti con attiva/disattiva e cancellazione. Scrittura diretta su
 * Firestore (permessa dalle regole solo al genitore autenticato, vedi
 * backend/firestore.rules) — nessun endpoint backend dedicato: il watch
 * legge le zone da /api/device-config in autonomia.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val geofences by viewModel.geofences.collectAsState()

    var pickedPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }

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

                    // Punto appena scelto, non ancora salvato: marker +
                    // anteprima del raggio scelto con lo slider, colore
                    // diverso (verde) per distinguerlo dalle zone gia'
                    // salvate.
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
                        onSave = {
                            val picked = pickedPoint ?: return@NewZoneToolbar
                            if (name.isNotBlank()) {
                                val zone = GeofenceZone(
                                    name = name,
                                    lat = picked.latitude,
                                    lon = picked.longitude,
                                    radiusMeters = radius.toDouble(),
                                    active = true,
                                )
                                viewModel.saveGeofence(zone) {
                                    name = ""
                                    pickedPoint = null
                                    radius = 150f
                                }
                            }
                        },
                        onCancel = {
                            name = ""
                            pickedPoint = null
                            radius = 150f
                        },
                    )
                }
            }

            ZoneList(
                geofences = geofences,
                onToggle = { zone, checked -> viewModel.saveGeofence(zone.copy(active = checked)) {} },
                onDelete = { zone -> viewModel.deleteGeofence(zone.id) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Pannello "strumenti" per la zona in fase di creazione: nome, raggio
 * (con anteprima live sulla mappa, vedi update() sopra) e i due
 * pulsanti Salva/Annulla — prima non esisteva un modo esplicito per
 * annullare un punto scelto per errore, solo ritoccare la mappa.
 */
@Composable
private fun NewZoneToolbar(
    name: String,
    onNameChange: (String) -> Unit,
    radius: Float,
    onRadiusChange: (Float) -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSave) { Text(stringResource(R.string.save_geofence)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.geofence_cancel)) }
            }
        }
    }
}

@Composable
private fun ZoneList(
    geofences: List<GeofenceZone>,
    onToggle: (GeofenceZone, Boolean) -> Unit,
    onDelete: (GeofenceZone) -> Unit,
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
                        TextButton(onClick = { onDelete(zone) }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
}
