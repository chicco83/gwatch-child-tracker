package com.gwatch.childtracker.phone.ui

// Storico versioni
// v0.25.0 (2026-09-10): l'utente segnalava che l'app doveva restare
//   aperta in primo piano per ricevere le notifiche dal watch (chat,
//   SOS, geofence...). Con FCM questo non dovrebbe servire: i messaggi
//   "data" arrivano e svegliano il processo anche ad app in background,
//   MA solo se il sistema non ha gia' sospeso/congelato l'app per
//   risparmio batteria. Su Android (soprattutto Samsung, coerente con
//   l'uso di un Galaxy Watch4) questo e' il colpevole piu' comune:
//   l'app viene messa "a dormire" dopo un po' di inattivita' e le
//   push FCM non la svegliano piu'. Aggiunta richiesta esplicita di
//   esenzione dalle ottimizzazioni batteria all'avvio (una tantum: se
//   gia' concessa non viene richiesta di nuovo). NB: sui telefoni
//   Samsung esiste ANCHE una lista separata "Metti in sospensione le
//   app inutilizzate" (Impostazioni > Cura del dispositivo > Batteria >
//   Limiti di utilizzo in background) che non e' coperta da questo
//   permesso standard Android: va disattivata a mano per questa app,
//   va segnalato all'utente perche' non e' automatizzabile da codice.
// v0.26.1 (2026-09-10): bug segnalato — toccando la notifica di un
//   messaggio dal watch si apriva la Home (mappa) invece della chat.
//   FcmService.kt non impostava nessun contentIntent sulla notifica:
//   senza, il tocco non porta a nessuna destinazione specifica.
//   Aggiunto un extra booleano sull'Intent che avvia MainActivity
//   (EXTRA_OPEN_CHAT); letto sia a freddo (onCreate) sia ad app gia'
//   aperta (onNewIntent, richiede launchMode="singleTop" nel Manifest
//   per non ricreare l'Activity) tramite un MutableStateFlow collezionato
//   in Compose, che innesca la navigazione verso "chat" non appena il
//   NavController esiste.

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gwatch.childtracker.phone.R
import com.gwatch.childtracker.phone.auth.AuthRepository
import com.gwatch.childtracker.phone.data.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    private val openChatRequested = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        requestIgnoreBatteryOptimizations()
        handleIntent(intent)

        val authRepository = AuthRepository(this)
        val deviceRepository = DeviceRepository()

        setContent {
            val viewModel: AppViewModel = viewModel(
                factory = AppViewModel.Factory(authRepository, deviceRepository),
            )
            val navController = rememberNavController()
            val user by viewModel.user.collectAsState()
            val openChat by openChatRequested.asStateFlow().collectAsState()

            val signInLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                viewModel.onSignInResult(result.data) {
                    Toast.makeText(this, "Login fallito, riprova", Toast.LENGTH_LONG).show()
                }
            }

            LaunchedEffect(openChat, user) {
                if (openChat && user != null) {
                    navController.navigate("chat")
                    openChatRequested.value = false
                }
            }

            val startDestination = if (authRepository.currentUser != null) "map" else "login"
            NavHost(navController = navController, startDestination = startDestination) {
                composable("login") {
                    LaunchedEffect(user) {
                        if (user != null) {
                            navController.navigate("map") { popUpTo("login") { inclusive = true } }
                        }
                    }
                    LoginScreen(onSignInClick = { signInLauncher.launch(viewModel.signInIntent()) })
                }
                composable("map") {
                    MapScreen(
                        viewModel = viewModel,
                        onOpenGeofences = { navController.navigate("geofences") },
                        onOpenChat = { navController.navigate("chat") },
                        onSignOut = {
                            viewModel.signOut()
                            navController.navigate("login") { popUpTo("map") { inclusive = true } }
                        },
                    )
                }
                composable("geofences") {
                    GeofenceScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }
                composable("chat") {
                    ChatScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }
            }
        }
    }

    /**
     * Chiede l'esenzione dalle ottimizzazioni batteria (vedi storico
     * versioni v0.25.0 in cima al file). Se gia' concessa non fa nulla;
     * altrimenti mostra un Toast esplicativo e apre direttamente la
     * schermata di sistema di conferma (l'utente deve comunque
     * confermare li', non e' un permesso auto-concesso).
     */
    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        Toast.makeText(this, getString(R.string.battery_optimization_hint), Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
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

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat"
    }
}
