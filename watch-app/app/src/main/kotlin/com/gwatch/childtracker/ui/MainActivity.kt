package com.gwatch.childtracker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.firebase.messaging.FirebaseMessaging
import com.gwatch.childtracker.R
import com.gwatch.childtracker.geofence.GeofenceSyncWorker
import com.gwatch.childtracker.location.LocationRequestWorker
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
                    // v0.2.5 (2026-09-10): lo swipe di sistema "indietro" su
                    // Wear OS chiudeva l'app invece di tornare al menu
                    // principale, perche' la navigazione interna (questo
                    // "screen" state) non intercettava il gesto — mancava
                    // un BackHandler.
                    "chat" -> {
                        BackHandler { screen = "main" }
                        ChatScreen(backendClient = backendClient, onBack = { screen = "main" })
                    }
                    else -> MainScreen(
                        onSosClick = ::sendSos,
                        onLocationClick = ::sendLocationNow,
                        onChatClick = { screen = "chat" },
                    )
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

    // v0.2.6 (2026-09-10): il Toast "SOS inviato" partiva subito dopo
    // l'accodamento del lavoro, non dopo l'invio effettivo al backend
    // (poteva mentire: il worker puo' fallire/ritentare dopo). Ora si
    // osserva l'esito reale del WorkInfo — conferma solo a consegna
    // avvenuta (SUCCEEDED) o fallimento definitivo (FAILED, es.
    // permesso posizione mancante); i tentativi (RETRY) restano
    // silenziosi, il worker ritenta da solo in background.
    private fun sendSos() {
        val work = OneTimeWorkRequestBuilder<SosWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            SosWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE, // un nuovo SOS ha sempre priorita' su uno in coda
            work,
        )
        Toast.makeText(this, getString(R.string.sos_sending), Toast.LENGTH_SHORT).show()

        WorkManager.getInstance(this).getWorkInfoByIdLiveData(work.id).observe(this) { info ->
            when (info?.state) {
                WorkInfo.State.SUCCEEDED ->
                    Toast.makeText(this, getString(R.string.sos_sent), Toast.LENGTH_LONG).show()
                WorkInfo.State.FAILED ->
                    Toast.makeText(this, getString(R.string.sos_failed), Toast.LENGTH_LONG).show()
                else -> Unit
            }
        }
    }

    // v0.3.0 (2026-09-10): pulsante "Invia posizione attuale" — invio
    // manuale su richiesta del bambino (a differenza dell'upload
    // periodico automatico, vedi LocationUploadWorker). Stessa logica
    // di conferma di sendSos(): Toast solo sull'esito reale del
    // WorkInfo, non alla sola messa in coda.
    private fun sendLocationNow() {
        val work = OneTimeWorkRequestBuilder<LocationRequestWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            LocationRequestWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            work,
        )
        Toast.makeText(this, getString(R.string.location_sending), Toast.LENGTH_SHORT).show()

        WorkManager.getInstance(this).getWorkInfoByIdLiveData(work.id).observe(this) { info ->
            when (info?.state) {
                WorkInfo.State.SUCCEEDED ->
                    Toast.makeText(this, getString(R.string.location_sent), Toast.LENGTH_LONG).show()
                WorkInfo.State.FAILED ->
                    Toast.makeText(this, getString(R.string.location_failed), Toast.LENGTH_LONG).show()
                else -> Unit
            }
        }
    }
}

@Composable
private fun MainScreen(
    onSosClick: () -> Unit,
    onLocationClick: () -> Unit,
    onChatClick: () -> Unit,
) {
    // v0.2.2 (2026-09-10): Button (Wear Compose) e' un tondo per icone
    // a dimensione fissa, non per etichette di testo — su device reale
    // "SOS"/"Messaggi" apparivano come cerchietti col testo troncato.
    // Sostituito con Chip, il componente Wear pensato per bottoni con
    // testo (rettangolare, si adatta alla larghezza).
    // v0.2.3 (2026-09-10): aggiunta spaziatura fra i due Chip, che su
    // device reale risultavano attaccati senza margine.
    // v0.2.6 (2026-09-10): SOS e Messaggi avevano lo stesso colore,
    // indistinguibili a colpo d'occhio. SOS ora rosso (emergenza),
    // Messaggi resta il colore di default del tema.
    // v0.3.0 (2026-09-10): il testo nei Chip risultava allineato a
    // sinistra invece che centrato — estratta CenteredChipLabel, usata
    // in tutti i Chip di questa schermata e di ChatScreen.kt. Aggiunto
    // anche il terzo pulsante "Invia posizione attuale": con tre Chip
    // pieni il contenuto puo' non stare piu' su schermi piccoli, per
    // cui la Column e' ora scorrevole (stessa lezione imparata in
    // ChatScreen.kt sui layout non scrollabili su device reale).
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(text = stringResourceCompat(R.string.app_name))
        Chip(
            onClick = onSosClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ChipDefaults.chipColors(backgroundColor = Color.Red, contentColor = Color.White),
            label = { CenteredChipLabel(stringResourceCompat(R.string.sos_button)) },
        )
        Chip(
            onClick = onLocationClick,
            modifier = Modifier.fillMaxWidth(),
            label = { CenteredChipLabel(stringResourceCompat(R.string.location_button)) },
        )
        Chip(
            onClick = onChatClick,
            modifier = Modifier.fillMaxWidth(),
            label = { CenteredChipLabel(stringResourceCompat(R.string.chat_button)) },
        )
    }
}

/**
 * Testo centrato per il label di un Chip/CompactChip: di default Wear
 * Compose lo allinea a sinistra, che su device reale risultava
 * incoerente/poco leggibile su bottoni a larghezza piena. Condivisa
 * con ChatScreen.kt.
 */
@Composable
internal fun CenteredChipLabel(text: String) {
    Text(text = text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

@Composable
internal fun stringResourceCompat(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
