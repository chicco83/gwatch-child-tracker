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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// v0.22.0 (2026-09-10): bug segnalato dall'utente — "i messaggi partono
// e compare il banner di invio ma non si vedono, devono comparire come
// una chat di wa". Prima "messages" veniva popolato solo dal listener
// Firestore: un messaggio appena inviato compariva solo dopo il giro
// completo scrittura-backend -> lettura-listener, invece che subito
// come nel watch-app (che ha gia' un invio ottimistico in
// ChatScreen.kt, MessageStore.append prima della chiamata di rete).
// Aggiunto lo stesso pattern qui: _optimisticMessages tiene i messaggi
// mandati dal genitore non ancora confermati dal listener, "messages"
// li combina con quelli reali e scarta i duplicati quando il listener
// li recupera (stesso sender+testo).
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

    private val _optimisticMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    val messages: StateFlow<List<ChatMessage>> = combine(
        deviceRepository.observeMessages(),
        _optimisticMessages,
    ) { remote, optimistic ->
        val remoteKeys = remote.map { it.sender to it.text }.toSet()
        val stillPending = optimistic.filterNot { (it.sender to it.text) in remoteKeys }
        (remote + stillPending).sortedBy { it.timestampMillis }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /**
     * onSent(true) se il messaggio e' stato accettato dal backend.
     * Ottimista: il messaggio appare subito in "messages" (vedi sopra),
     * senza aspettare che il listener Firestore lo recuperi — se
     * l'invio fallisce viene tolto di nuovo, non e' mai partito
     * davvero.
     */
    fun sendMessage(text: String, onSent: (Boolean) -> Unit) {
        val user = _user.value ?: return onSent(false)
        val optimistic = ChatMessage(sender = "parent", text = text, timestampMillis = System.currentTimeMillis())
        _optimisticMessages.value = _optimisticMessages.value + optimistic
        viewModelScope.launch {
            val ok = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.sendMessageToChild(idToken, text)
            }.getOrDefault(false)
            if (!ok) {
                _optimisticMessages.value = _optimisticMessages.value.filterNot { it === optimistic }
            }
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

    /** Disattiva un SOS in corso (vedi backend/api/cancel-sos.js). */
    fun cancelSos(onResult: (Boolean) -> Unit) {
        val user = _user.value ?: return onResult(false)
        viewModelScope.launch {
            val ok = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.cancelSos(idToken)
            }.getOrDefault(false)
            onResult(ok)
        }
    }

    /**
     * Marca un evento come "visto" — chiamato da MapScreen.kt quando
     * mostra un evento "posizione inviata dal bambino" non ancora
     * marcato (vedi backend/api/ack-event.js, che notifica il watch).
     * Fire-and-forget: nessun feedback in UI, non e' un'azione che
     * l'utente ha scelto esplicitamente di fare.
     */
    fun ackEvent(eventId: String) {
        val user = _user.value ?: return
        viewModelScope.launch {
            runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.ackEvent(idToken, eventId)
            }
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
