package com.gwatch.childtracker.phone.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
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
import com.gwatch.childtracker.phone.auth.AuthRepository
import com.gwatch.childtracker.phone.data.DeviceRepository

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val authRepository = AuthRepository(this)
        val deviceRepository = DeviceRepository()

        setContent {
            val viewModel: AppViewModel = viewModel(
                factory = AppViewModel.Factory(authRepository, deviceRepository),
            )
            val navController = rememberNavController()
            val user by viewModel.user.collectAsState()

            val signInLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                viewModel.onSignInResult(result.data) {
                    Toast.makeText(this, "Login fallito, riprova", Toast.LENGTH_LONG).show()
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
}
