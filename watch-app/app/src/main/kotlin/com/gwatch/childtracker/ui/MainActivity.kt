package com.gwatch.childtracker.ui

// Storico versioni (solo modifiche recenti)
// v0.6.3 (2026-09-10): bug segnalato — la notifica di un messaggio in
//   arrivo non era cliccabile, non portava da nessuna parte (a
//   differenza della phone-app, gia' sistemata allo stesso modo).
//   FcmService.kt non impostava nessun contentIntent. Aggiunto un
//   PendingIntent verso questa Activity con l'extra EXTRA_OPEN_CHAT,
//   letto sia a freddo (onCreate) sia ad app gia' aperta (onNewIntent,
//   richiede launchMode="singleTop" nel Manifest) per navigare subito
//   alla schermata "chat" tramite lo stesso MutableStateFlow-pattern
//   gia' usato per SosState/MessageStore in questo progetto.
// v0.7.0 (2026-09-11): richiesta utente — lo scroll automatico in fondo
//   alla chat (ChatScreen.kt) deve avvenire solo aprendo la chat dalla
//   notifica di un nuovo messaggio, non dal Chip "Messaggi" del menu
//   principale. Aggiunto chatScrollToBottom: true solo nel ramo
//   LaunchedEffect(openChat) (apertura da notifica/EXTRA_OPEN_CHAT),
//   riportato a false ogni volta che si entra in chat dal Chip
//   "Messaggi" (onChatClick).

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.gwatch.childtracker.BuildConfig
import com.gwatch.childtracker.location.GpsAvailability
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import androidx.work.workDataOf
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.firebase.messaging.FirebaseMessaging
import com.gwatch.childtracker.R
import com.gwatch.childtracker.geofence.GeofenceSyncWorker
import com.gwatch.childtracker.location.LocationRequestWorker
import com.gwatch.childtracker.location.LocationTrackingService
import com.gwatch.childtracker.location.SosLocationService
import com.gwatch.childtracker.network.BackendClient
import com.gwatch.childtracker.sos.SosState
import com.gwatch.childtracker.sos.SosWorker
import com.gwatch.childtracker.upload.LocationUploadWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {

    private val openChatRequested = MutableStateFlow(false)

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
        handleIntent(intent)
        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf("main") }
                // v0.7.0: vedi storico versioni sopra — true solo quando
                // si entra in chat dalla notifica di un nuovo messaggio.
                var chatScrollToBottom by remember { mutableStateOf(false) }
                val openChat by openChatRequested.asStateFlow().collectAsState()
                LaunchedEffect(openChat) {
                    if (openChat) {
                        chatScrollToBottom = true
                        screen = "chat"
                        openChatRequested.value = false
                    }
                }
                when (screen) {
                    // v0.2.5 (2026-09-10): lo swipe di sistema "indietro" su
                    // Wear OS chiudeva l'app invece di tornare al menu
                    // principale, perche' la navigazione interna (questo
                    // "screen" state) non intercettava il gesto — mancava
                    // un BackHandler.
                    "chat" -> {
                        BackHandler { screen = "main" }
                        ChatScreen(
                            backendClient = backendClient,
                            onBack = { screen = "main" },
                            scrollToBottom = chatScrollToBottom,
                        )
                    }
                    // v0.4.0 (2026-09-10): l'SOS ora chiede conferma
                    // prima di attivarsi (richiesta utente, per evitare
                    // attivazioni accidentali di una funzione che avvia
                    // un tracking continuo finche' il genitore non lo
                    // disattiva) — stesso pattern "screen" gia' usato
                    // per la chat, nessun componente Dialog/Confirmation
                    // di Wear Compose (mai verificato in build reale in
                    // questo progetto, vedi la vicenda di Divider in
                    // ChatScreen.kt).
                    "sosConfirm" -> {
                        BackHandler { screen = "main" }
                        SosConfirmScreen(
                            onConfirm = {
                                screen = "main"
                                confirmSos()
                            },
                            onCancel = { screen = "main" },
                        )
                    }
                    // v0.15.0 (2026-09-23): schermata "Ricerca GPS" con le
                    // barre dei satelliti (richiesta utente), vedi
                    // GpsSearchScreen.kt. Al primo fix: GPS segnato
                    // disponibile, posizione inviata, ritorno al menu.
                    "gpsSearch" -> {
                        BackHandler { screen = "main" }
                        GpsSearchScreen(
                            onFixFound = {
                                GpsAvailability.markAvailable()
                                sendLocationNow()
                                screen = "main"
                            },
                            onBack = { screen = "main" },
                        )
                    }
                    else -> MainScreen(
                        onSosClick = { screen = "sosConfirm" },
                        // v0.15.0 (2026-09-23): con GPS assente il tocco apre la
                        // Ricerca GPS invece di rilanciare subito l'invio.
                        // Precedente: onLocationClick = ::sendLocationNow,
                        onLocationClick = {
                            if (GpsAvailability.available.value == false) {
                                screen = "gpsSearch"
                            } else {
                                sendLocationNow()
                            }
                        },
                        onChatClick = {
                            chatScrollToBottom = false
                            screen = "chat"
                        },
                    )
                }
            }
        }
        requestPermissionsAndStart()
        registerFcmToken()
        observeWorkOutcomes()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true) {
            openChatRequested.value = true
        }
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
        // 2026-09-18: era ExistingWorkPolicy.KEEP — se un lavoro con
        // questo stesso nome univoco esisteva gia' nel database di
        // WorkManager (anche completato mesi fa), riaprire l'app non
        // accodava una nuova esecuzione: bisognava aspettare il
        // prossimo giro periodico (6h) o un riavvio del watch (dove
        // BootReceiver.kt usa gia' REPLACE). Causa concreta del bug
        // "zone create in precedenza non ricompaiono sulla phone-app"
        // segnalato piu' volte dall'utente dopo il fix della
        // migrazione lato backend (device-config.js): il fix era gia'
        // live, ma il watch semplicemente non lo richiamava mai
        // riaprendo solo l'app. REPLACE forza sempre una sync fresca
        // ad ogni apertura, coerente con BootReceiver.
        workManager.enqueueUniqueWork(
            GeofenceSyncWorker.ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
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
    // v0.4.0 (2026-09-10): chiamato dopo la conferma sullo schermo
    // "sosConfirm". Oltre all'invio SOS gia' esistente (sendSos, fix +
    // notifica al genitore), avvia SosLocationService: da qui in poi il
    // watch invia la posizione ogni 30 secondi finche' il genitore non
    // disattiva l'SOS dalla phone-app (push "sos_cancel", vedi
    // FcmService.kt) o finche' il backend non segnala che l'SOS non e'
    // piu' attivo (vedi SosLocationService/sos-heartbeat.js).
    private fun confirmSos() {
        sendSos()
        ContextCompat.startForegroundService(
            this,
            android.content.Intent(this, SosLocationService::class.java),
        )
    }

    // v0.7.0 (2026-09-18): il Toast di conferma era osservato solo sul
    // work.id della singola pressione (getWorkInfoByIdLiveData), legato
    // al ciclo di vita di QUESTA Activity: se il GPS restava assente per
    // minuti/ore (caso reale confermato via Logcat) e nel frattempo
    // l'utente chiudeva/riapriva l'app, il "posizione/SOS inviata"
    // finale poteva non arrivare mai a schermo. Spostata la conferma su
    // observeWorkOutcomes() (chiamato una sola volta in onCreate), che
    // osserva il nome univoco del lavoro invece del singolo id: cosi'
    // riaprendo l'app si vede comunque l'esito, anche se il fix GPS e'
    // arrivato a watch in tasca. Qui restano solo l'accodamento e il
    // Toast immediato "in corso".
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
    }

    // v0.3.0 (2026-09-10): pulsante "Invia posizione attuale" — invio
    // manuale su richiesta del bambino (a differenza dell'upload
    // periodico automatico, vedi LocationUploadWorker). Stessa logica
    // di conferma di sendSos(): Toast solo sull'esito reale del
    // WorkInfo, non alla sola messa in coda.
    // v0.6.0 (2026-09-10): passa source=KEY_SOURCE_CHILD — prima questo
    // pulsante e la richiesta remota del genitore (FcmService, push
    // "location_request") mandavano lo stesso identico evento al
    // backend, che quindi non poteva distinguerli: la notifica al
    // genitore diceva sempre "il bambino ha inviato la posizione" anche
    // quando era stato lui stesso a chiederla da "Aggiorna posizione"
    // sulla phone-app. Vedi LocationRequestWorker.kt.
    // v0.7.0 (2026-09-18): vedi il commento su sendSos() sopra — stessa
    // ragione, stesso spostamento della conferma su observeWorkOutcomes().
    private fun sendLocationNow() {
        val work = OneTimeWorkRequestBuilder<LocationRequestWorker>()
            .setInputData(workDataOf(LocationRequestWorker.KEY_SOURCE to LocationRequestWorker.SOURCE_CHILD))
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            LocationRequestWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            work,
        )
        Toast.makeText(this, getString(R.string.location_sending), Toast.LENGTH_SHORT).show()
    }

    // v0.7.0 (2026-09-18): osservatore unico, registrato una sola volta
    // in onCreate, sul NOME del lavoro (getWorkInfosForUniqueWorkLiveData)
    // invece che sul singolo work.id di ogni pressione — vedi i commenti
    // su sendSos()/sendLocationNow() sopra per il motivo. lastNotified*
    // evita di ripetere lo stesso Toast se l'utente riapre l'app dopo
    // aver gia' visto l'esito di quel preciso lavoro; un nuovo lavoro
    // (nuova pressione, nuovo id via ExistingWorkPolicy.REPLACE) supera
    // sempre il controllo e notifica di nuovo.
    // v0.8.0 (2026-09-18): bug segnalato dall'utente — "ad ogni avvio
    // dell'app sul watch compare il banner 'SOS inviato', ma in realta'
    // non arriva sul cellulare (nessun nuovo SOS davvero inviato)".
    // Causa: lastNotified* sono variabili in memoria, azzerate ad ogni
    // nuova istanza di MainActivity (ogni riavvio dell'app/processo).
    // getWorkInfosForUniqueWorkLiveData emette SUBITO, alla sottoscrizione,
    // lo stato PIU' RECENTE gia' presente nel database di WorkManager —
    // anche se quel lavoro e' concluso da ore/giorni (un vecchio SOS di
    // test). Con lastNotified* resettato a null, quel primo stato
    // "vecchio" superava sempre il controllo id!=lastNotified e
    // rimostrava il Toast come se fosse un esito nuovo. Aggiunto un
    // "baseline" per lavoro: la primissima emissione dopo l'apertura
    // dell'app, SE gia' in uno stato finale, viene registrata come "gia'
    // vista" senza Toast (e' solo lo stato che WorkManager ricordava da
    // prima, non un esito nuovo) — se invece e' ancora in corso (es. un
    // SOS in RETRY per GPS assente, sopravvissuto a un riavvio
    // dell'app), non viene marcata: si continua ad aspettare il suo
    // esito reale come gia' previsto, nessuna regressione sul comportamento
    // v0.7.0 voluto ("riaprendo l'app si vede comunque l'esito").
    private var lastNotifiedSosWorkId: java.util.UUID? = null
    private var lastNotifiedLocationWorkId: java.util.UUID? = null
    private var sosBaselineChecked = false
    private var locationBaselineChecked = false

    private fun observeWorkOutcomes() {
        val workManager = WorkManager.getInstance(this)
        workManager.getWorkInfosForUniqueWorkLiveData(SosWorker.WORK_NAME).observe(this) { infos ->
            val info = infos.firstOrNull() ?: return@observe
            if (!sosBaselineChecked) {
                sosBaselineChecked = true
                if (info.state.isFinished()) {
                    lastNotifiedSosWorkId = info.id
                    return@observe
                }
            }
            if (info.id == lastNotifiedSosWorkId) return@observe
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    lastNotifiedSosWorkId = info.id
                    Toast.makeText(this, getString(R.string.sos_sent), Toast.LENGTH_LONG).show()
                }
                WorkInfo.State.FAILED -> {
                    lastNotifiedSosWorkId = info.id
                    Toast.makeText(this, getString(R.string.sos_failed), Toast.LENGTH_LONG).show()
                }
                else -> Unit
            }
        }
        workManager.getWorkInfosForUniqueWorkLiveData(LocationRequestWorker.WORK_NAME).observe(this) { infos ->
            val info = infos.firstOrNull() ?: return@observe
            if (!locationBaselineChecked) {
                locationBaselineChecked = true
                if (info.state.isFinished()) {
                    lastNotifiedLocationWorkId = info.id
                    return@observe
                }
            }
            if (info.id == lastNotifiedLocationWorkId) return@observe
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    lastNotifiedLocationWorkId = info.id
                    Toast.makeText(this, getString(R.string.location_sent), Toast.LENGTH_LONG).show()
                }
                WorkInfo.State.FAILED -> {
                    lastNotifiedLocationWorkId = info.id
                    Toast.makeText(this, getString(R.string.location_failed), Toast.LENGTH_LONG).show()
                }
                else -> Unit
            }
        }
    }

    private fun WorkInfo.State.isFinished(): Boolean =
        this == WorkInfo.State.SUCCEEDED || this == WorkInfo.State.FAILED || this == WorkInfo.State.CANCELLED

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat"
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
    // v0.6.0 (2026-09-10): aggiunto il banner "SOS ATTIVO" — prima non
    // c'era alcun modo di vedere sul watch se l'SOS fosse attivo, ne'
    // dopo la conferma ne' quando il genitore lo disattiva da remoto
    // (vedi SosState.kt/SosLocationService.kt).
    val sosActive by SosState.active.collectAsState()
    // v0.7.0 (2026-09-18): letto da GpsAvailability (vedi
    // location/GpsAvailability.kt) per disabilitare "Invia posizione"
    // quando il GPS non risponde, con un testo esplicito invece di
    // lasciare il pulsante premibile a vuoto. SOS resta SEMPRE
    // abilitato, a prescindere da questo stato — non va mai bloccato.
    val gpsAvailable by GpsAvailability.available.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        // 2026-09-18: aggiunta la versione (BuildConfig.VERSION_NAME) sotto
        // il nome app — richiesto dall'utente per verificare a colpo
        // d'occhio, senza aprire le Impostazioni di sistema, se l'ultima
        // build compilata sia davvero quella installata sul watch.
        Text(text = stringResourceCompat(R.string.app_name))
        Text(text = "v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.caption3)
        if (sosActive) {
            Text(
                text = stringResourceCompat(R.string.sos_active_banner),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = Color.Red,
                fontWeight = FontWeight.Bold,
            )
        }
        Chip(
            onClick = onSosClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ChipDefaults.chipColors(backgroundColor = Color.Red, contentColor = Color.White),
            label = { CenteredChipLabel(stringResourceCompat(R.string.sos_button)) },
        )
        // v0.14.0 (2026-09-23): bug segnalato dall'utente — il pulsante
        // restava bloccato su "Posizione non disponibile, segnale GPS
        // assente". Una volta disabilitato, tornava attivo solo con un
        // fix del tracking automatico (ogni 10' da fermo, mai se il GPS
        // continua a non agganciarsi), e il bambino non poteva nemmeno
        // riprovare. Ora resta SEMPRE premibile: l'etichetta avvisa del
        // GPS assente e invita a riprovare, la pressione rilancia il
        // tentativo (LocationRequestWorker, che aggiorna di nuovo
        // GpsAvailability). Precedente (2026-09-18):
        //     enabled = gpsAvailable != false,
        Chip(
            onClick = onLocationClick,
            modifier = Modifier.fillMaxWidth(),
            label = {
                CenteredChipLabel(
                    if (gpsAvailable == false) {
                        stringResourceCompat(R.string.location_gps_unavailable)
                    } else {
                        stringResourceCompat(R.string.location_button)
                    },
                )
            },
        )
        Chip(
            onClick = onChatClick,
            modifier = Modifier.fillMaxWidth(),
            label = { CenteredChipLabel(stringResourceCompat(R.string.chat_button)) },
        )
    }
}

/**
 * Schermo di conferma mostrato prima di attivare l'SOS (v0.4.0):
 * evita attivazioni accidentali di una funzione che, a differenza del
 * vecchio SOS one-shot, ora avvia un tracking continuo (ogni 30", vedi
 * SosLocationService) finche' il genitore non lo disattiva dal
 * telefono. "Conferma" e' il Chip primario (rosso, come il pulsante
 * SOS originale); "Annulla" e' un CompactChip secondario, stesso
 * pattern gia' usato per "Indietro" in ChatScreen.kt.
 */
@Composable
private fun SosConfirmScreen(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(text = stringResourceCompat(R.string.sos_confirm_title))
        Text(
            text = stringResourceCompat(R.string.sos_confirm_message),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Chip(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            colors = ChipDefaults.chipColors(backgroundColor = Color.Red, contentColor = Color.White),
            label = { CenteredChipLabel(stringResourceCompat(R.string.sos_confirm_button)) },
        )
        CompactChip(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            colors = ChipDefaults.secondaryChipColors(),
            label = { CenteredChipLabel(stringResourceCompat(R.string.sos_cancel_confirm_button)) },
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
