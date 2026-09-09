package com.gwatch.childtracker.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.gwatch.childtracker.phone.R
import com.gwatch.childtracker.phone.data.model.DeviceEvent
import com.gwatch.childtracker.phone.data.model.DeviceState
import com.gwatch.childtracker.phone.util.formatRelativeTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // Roma come default finche' non arriva il primo fix dal watch.
    val defaultPosition = LatLng(41.9028, 12.4964)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(defaultPosition, 12f)
    }

    // Centra la mappa sull'ultima posizione nota solo alla prima
    // ricezione: dopo, l'utente deve poter muovere liberamente la mappa
    // senza che uno scatto GPS successivo la ricentri da sotto le dita.
    var centered by remember { mutableStateOf(false) }
    LaunchedEffect(deviceState.lastLocation) {
        val loc = deviceState.lastLocation
        if (loc != null && !centered) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(loc.lat, loc.lon), 15f)
            centered = true
        }
    }

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

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                ) {
                    deviceState.lastLocation?.let { loc ->
                        Marker(
                            state = MarkerState(position = LatLng(loc.lat, loc.lon)),
                            title = stringResource(R.string.last_known_position),
                        )
                    }
                    if (history.size >= 2) {
                        Polyline(points = history.map { LatLng(it.lat, it.lon) })
                    }
                    geofences.forEach { zone ->
                        val color = if (zone.active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                        Circle(
                            center = LatLng(zone.lat, zone.lon),
                            radius = zone.radiusMeters,
                            strokeColor = color,
                            fillColor = color.copy(alpha = 0.15f),
                        )
                    }
                }
            }

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
