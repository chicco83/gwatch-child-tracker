package com.gwatch.childtracker.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.gwatch.childtracker.phone.R
import com.gwatch.childtracker.phone.data.model.GeofenceZone

/**
 * Gestione zone (casa/scuola, MVP): tocco sulla mappa per scegliere il
 * centro, form con nome + raggio, lista delle zone esistenti con
 * attiva/disattiva e cancellazione. Scrittura diretta su Firestore
 * (permessa dalle regole solo al genitore autenticato, vedi
 * backend/firestore.rules) — nessun endpoint backend dedicato: il watch
 * legge le zone da /api/device-config in autonomia.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val geofences by viewModel.geofences.collectAsState()

    var pickedLatLng by remember { mutableStateOf<LatLng?>(null) }
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(41.9028, 12.4964), 13f)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.geofences_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = stringResource(R.string.geofence_tap_hint),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )

            GoogleMap(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                cameraPositionState = cameraPositionState,
                onMapClick = { latLng -> pickedLatLng = latLng },
            ) {
                pickedLatLng?.let { Marker(state = MarkerState(position = it)) }
            }

            pickedLatLng?.let { picked ->
                Column(modifier = Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.geofence_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(text = stringResource(R.string.geofence_radius_label, radius.toInt()))
                    Slider(value = radius, onValueChange = { radius = it }, valueRange = 50f..1000f)
                    TextButton(
                        onClick = {
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
                                    pickedLatLng = null
                                    radius = 150f
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.save_geofence)) }
                }
            }

            Divider()

            LazyColumn {
                items(geofences) { zone ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(zone.name)
                            Text(
                                text = stringResource(R.string.geofence_radius_label, zone.radiusMeters.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = zone.active,
                                onCheckedChange = { checked ->
                                    viewModel.saveGeofence(zone.copy(active = checked)) {}
                                },
                            )
                            TextButton(onClick = { viewModel.deleteGeofence(zone.id) }) {
                                Text(stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }
}
