package com.gwatch.childtracker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.firebase.messaging.FirebaseMessaging
import com.gwatch.childtracker.R
import com.gwatch.childtracker.geofence.GeofenceSyncWorker
import com.gwatch.childtracker.location.LocationTrackingService
import com.gwatch.childtracker.network.BackendClient
import com.gwatch.childtracker.sos.SosWorker
import com.gwatch.childtracker.upload.LocationUploadWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {

    private val requestCorePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationGranted) {
            requestBackgroundLocationIfNeeded()
        } else {
            Toast.makeText(this, "Permesso posizione necessario", Toast.LENGTH_LONG).show()
        }
    }

    private val requestBackgroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Concesso o no, avviamo comunque: senza background location il
        // tracking funziona solo ad app aperta, meglio che niente.
        startTrackingAndScheduleWork()
    }

    private val backendClient = BackendClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf("main") }
                when (screen) {
                    "chat" -> ChatScreen(backendClient = backendClient, onBack = { screen = "main" })
                    else -> MainScreen(onSosClick = ::sendSos, onChatClick = { screen = "chat" })
                }
            }
        }
        requestPermissionsAndStart()
        registerFcmToken()
    }

    // Ad ogni avvio, non solo su onNewToken: cosi' un token gia'
    // generato prima che il servizio fosse registrato sul backend (es.
    // primo avvio dopo l'aggiornamento a questa versione) viene comunque
    // inviato. Idempotente lato backend (set con merge).
    private fun registerFcmToken() {
        lifecycleScope.launch {
            runCatching { FirebaseMessaging.getInstance().token.await() }
                .onSuccess { token -> backendClient.registerFcmToken(token) }
        }
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            requestBackgroundLocationIfNeeded()
        } else {
            requestCorePermissions.launch(permissions.toTypedArray())
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startTrackingAndScheduleWork()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startTrackingAndScheduleWork()
        } else {
            requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun startTrackingAndScheduleWork() {
        ContextCompat.startForegroundService(
            this,
            android.content.Intent(this, LocationTrackingService::class.java),
        )

        val workManager = WorkManager.getInstance(this)

        // 15 minuti e' il minimo consentito per il lavoro periodico di
        // WorkManager: l'upload piu' frequente in caso di movimento e'
        // gestito a parte dal trigger one-shot nel service.
        workManager.enqueueUniquePeriodicWork(
            LocationUploadWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LocationUploadWorker>(15, TimeUnit.MINUTES).build(),
        )

        // Le geofence cambiano raramente: sync ogni 6 ore basta, piu'
        // una volta subito per non aspettare al primo avvio.
        workManager.enqueueUniquePeriodicWork(
            GeofenceSyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GeofenceSyncWorker>(6, TimeUnit.HOURS).build(),
        )
        workManager.enqueueUniqueWork(
            GeofenceSyncWorker.ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<GeofenceSyncWorker>().build(),
        )
    }

    private fun sendSos() {
        val work = OneTimeWorkRequestBuilder<SosWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            SosWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE, // un nuovo SOS ha sempre priorita' su uno in coda
            work,
        )
        Toast.makeText(this, getString(R.string.sos_sent), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MainScreen(onSosClick: () -> Unit, onChatClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = stringResourceCompat(R.string.app_name))
        Button(onClick = onSosClick) {
            Text(text = stringResourceCompat(R.string.sos_button))
        }
        Button(onClick = onChatClick) {
            Text(text = stringResourceCompat(R.string.chat_button))
        }
    }
}

@Composable
private fun stringResourceCompat(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
