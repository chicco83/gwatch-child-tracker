package com.gwatch.childtracker.phone.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.messaging.FirebaseMessaging
import com.gwatch.childtracker.phone.auth.AuthRepository
import com.gwatch.childtracker.phone.data.BackendClient
import com.gwatch.childtracker.phone.data.DeviceRepository
import com.gwatch.childtracker.phone.data.model.ChatMessage
import com.gwatch.childtracker.phone.data.model.DeviceEvent
import com.gwatch.childtracker.phone.data.model.DeviceState
import com.gwatch.childtracker.phone.data.model.GeofenceZone
import com.gwatch.childtracker.phone.data.model.LocationPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AppViewModel(
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val backendClient: BackendClient = BackendClient(),
) : ViewModel() {

    private val _user = MutableStateFlow(authRepository.currentUser)
    val user: StateFlow<FirebaseUser?> = _user.asStateFlow()

    val deviceState: StateFlow<DeviceState> = deviceRepository.observeDeviceState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceState())

    val history: StateFlow<List<LocationPoint>> = deviceRepository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val geofences: StateFlow<List<GeofenceZone>> = deviceRepository.observeGeofences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val events: StateFlow<List<DeviceEvent>> = deviceRepository.observeRecentEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messages: StateFlow<List<ChatMessage>> = deviceRepository.observeMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun signInIntent(): Intent = authRepository.signInIntent()

    fun onSignInResult(data: Intent?, onError: (Throwable) -> Unit) {
        viewModelScope.launch {
            runCatching { authRepository.handleSignInResult(data) }
                .onSuccess { firebaseUser ->
                    _user.value = firebaseUser
                    registerFcmToken(firebaseUser.uid)
                }
                .onFailure(onError)
        }
    }

    private suspend fun registerFcmToken(uid: String) {
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            deviceRepository.registerFcmToken(uid, token)
        }
    }

    fun signOut() {
        authRepository.signOut()
        _user.value = null
    }

    fun saveGeofence(zone: GeofenceZone, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { deviceRepository.saveGeofence(zone) }
            onDone()
        }
    }

    fun deleteGeofence(id: String) {
        viewModelScope.launch { runCatching { deviceRepository.deleteGeofence(id) } }
    }

    /** onSent(true) se il messaggio e' stato accettato dal backend. */
    fun sendMessage(text: String, onSent: (Boolean) -> Unit) {
        val user = _user.value ?: return onSent(false)
        viewModelScope.launch {
            val ok = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.sendMessageToChild(idToken, text)
            }.getOrDefault(false)
            onSent(ok)
        }
    }

    /**
     * Chiede al watch di inviare subito la posizione attuale (push FCM,
     * vedi backend/api/request-location.js), invece di aspettare il
     * prossimo upload periodico. onResult(true) se il backend ha
     * accettato la richiesta — la posizione vera e propria arriva poi
     * come aggiornamento separato di deviceState via Firestore.
     */
    fun requestLocation(onResult: (Boolean) -> Unit) {
        val user = _user.value ?: return onResult(false)
        viewModelScope.launch {
            val ok = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.requestLocation(idToken)
            }.getOrDefault(false)
            onResult(ok)
        }
    }

    class Factory(
        private val authRepository: AuthRepository,
        private val deviceRepository: DeviceRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(authRepository, deviceRepository) as T
    }
}
