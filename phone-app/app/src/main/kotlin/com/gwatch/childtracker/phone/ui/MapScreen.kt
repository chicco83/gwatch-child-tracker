package com.gwatch.childtracker.phone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: AppViewModel,
    onOpenGeofences: () -> Unit,
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

    // Centra la mappa sull'ultima posizione nota solo alla prima
    // ricezione: dopo, l'utente deve poter muovere liberamente la mappa
    // senza che un punto GPS successivo la "strappi" da sotto le dita.
    var centered by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onOpenGeofences) { Text(stringResource(R.string.geofences_title)) }
                    TextButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            StatusCard(deviceState)

            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxWidth().weight(1f),
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

                    if (history.size >= 2) {
                        map.overlays.add(
                            Polyline().apply {
                                setPoints(history.map { GeoPoint(it.lat, it.lon) })
                            },
                        )
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

            EventsList(events = events)
        }
    }
}

@Composable
private fun StatusCard(state: DeviceState) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
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
    }
}

@Composable
private fun EventsList(events: List<DeviceEvent>) {
    if (events.isEmpty()) return
    val formatter = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(horizontal = 12.dp)) {
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
    else -> type
}
