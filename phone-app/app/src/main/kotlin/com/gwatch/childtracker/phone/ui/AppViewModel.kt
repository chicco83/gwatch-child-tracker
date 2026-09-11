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
import com.gwatch.childtracker.phone.data.IncomingMessageStore
import com.gwatch.childtracker.phone.data.NewChildResult
import com.gwatch.childtracker.phone.data.model.ChatMessage
import com.gwatch.childtracker.phone.data.model.ChildInfo
import com.gwatch.childtracker.phone.data.model.DeviceEvent
import com.gwatch.childtracker.phone.data.model.DeviceState
import com.gwatch.childtracker.phone.data.model.GeofenceZone
import com.gwatch.childtracker.phone.data.model.LocationPoint
import com.gwatch.childtracker.phone.util.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
// v0.25.1 (2026-09-10): bug segnalato — i messaggi RICEVUTI dal watch
// comparivano solo come notifica, mai nella schermata Chat (il
// listener Firestore da solo non bastava, vedi
// backend/api/send-message.js v0.3.0 e messaging/FcmService.kt per la
// causa reale). Aggiunta la stessa logica di echo locale usata per i
// messaggi in uscita, ma per quelli in entrata: IncomingMessageStore
// (nuovo, in data/), popolato da FcmService.kt alla ricezione della
// push, combinato qui esattamente come _optimisticMessages.
// v0.29.0 (2026-09-11): fase 3/4 — supporto N bambini (vedi CONTEXT.md).
// "deviceState"/"history"/"events" (singolari, un solo bambino fisso)
// diventano "deviceStates"/"historyByChild"/"eventsByChild" (mappe
// childId -> dato), derivate da "children" con flatMapLatest+combine:
// quando la lista bambini cambia, ri-crea l'insieme di listener
// Firestore, uno per bambino. requestLocation/cancelSos/ackEvent ora
// richiedono un childId esplicito (il backend lo richiede gia' dalla
// fase 1 — bug latente corretto in BackendClient.kt v0.4.0). sendMessage
// resta invece sul solo Constants.DEVICE_ID: il selettore destinatario
// per la chat e' rimandato alla fase 4. Aggiunti anche ownNickname/
// updateOwnNickname/setChildNickname/createChild per la nuova
// SettingsScreen.kt.
class AppViewModel(
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val backendClient: BackendClient = BackendClient(),
) : ViewModel() {

    private val _user = MutableStateFlow(authRepository.currentUser)
    val user: StateFlow<FirebaseUser?> = _user.asStateFlow()

    val children: StateFlow<List<ChildInfo>> = deviceRepository.observeChildren()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val deviceStates: StateFlow<Map<String, DeviceState>> = children
        .flatMapLatest { list -> combineByChild(list) { deviceRepository.observeDeviceState(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    val historyByChild: StateFlow<Map<String, List<LocationPoint>>> = children
        .flatMapLatest { list -> combineByChild(list) { deviceRepository.observeHistory(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    val eventsByChild: StateFlow<Map<String, List<DeviceEvent>>> = children
        .flatMapLatest { list -> combineByChild(list) { deviceRepository.observeRecentEvents(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val geofences: StateFlow<List<GeofenceZone>> = deviceRepository.observeGeofences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Proprio nickname (parents/{uid}.nickname) — segue l'utente loggato,
    // null se nessuno ha ancora fatto login o non l'ha ancora impostato.
    @OptIn(ExperimentalCoroutinesApi::class)
    val ownNickname: StateFlow<String?> = user
        .flatMapLatest { u -> if (u == null) flowOf(null) else deviceRepository.observeOwnNickname(u.uid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _optimisticMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    val messages: StateFlow<List<ChatMessage>> = combine(
        deviceRepository.observeMessages(),
        _optimisticMessages,
        IncomingMessageStore.messages,
    ) { remote, outgoingPending, incomingPending ->
        val remoteKeys = remote.map { it.sender to it.text }.toSet()
        val stillPending = (outgoingPending + incomingPending).filterNot { (it.sender to it.text) in remoteKeys }
        (remote + stillPending).sortedBy { it.timestampMillis }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Combina un flow per bambino (stesso pattern per deviceStates/historyByChild/eventsByChild). */
    private fun <T> combineByChild(
        children: List<ChildInfo>,
        observe: (childId: String) -> Flow<T>,
    ): Flow<Map<String, T>> {
        if (children.isEmpty()) return flowOf(emptyMap())
        val perChild = children.map { child -> observe(child.id).map { child.id to it } }
        return combine(perChild) { pairs -> pairs.toMap() }
    }

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
     * davvero. Destinatario ancora fisso su Constants.DEVICE_ID: il
     * selettore "Scrivi a: ..." arriva in fase 4.
     */
    fun sendMessage(text: String, onSent: (Boolean) -> Unit) {
        val user = _user.value ?: return onSent(false)
        val optimistic = ChatMessage(sender = "parent", text = text, timestampMillis = System.currentTimeMillis())
        _optimisticMessages.value = _optimisticMessages.value + optimistic
        viewModelScope.launch {
            val ok = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.sendMessageToChild(idToken, Constants.DEVICE_ID, text)
            }.getOrDefault(false)
            if (!ok) {
                _optimisticMessages.value = _optimisticMessages.value.filterNot { it === optimistic }
            }
            onSent(ok)
        }
    }

    /**
     * Chiede al watch di un bambino di inviare subito la posizione
     * attuale (push FCM, vedi backend/api/parent-command.js), invece di
     * aspettare il prossimo upload periodico. onResult(true) se il
     * backend ha accettato la richiesta — la posizione vera e propria
     * arriva poi come aggiornamento separato di deviceStates[childId]
     * via Firestore.
     */
    fun requestLocation(childId: String, onResult: (Boolean) -> Unit) {
        val user = _user.value ?: return onResult(false)
        viewModelScope.launch {
            val ok = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.requestLocation(idToken, childId)
            }.getOrDefault(false)
            onResult(ok)
        }
    }

    /** Disattiva un SOS in corso per un bambino (vedi backend/api/parent-command.js, azione cancel_sos). */
    fun cancelSos(childId: String, onResult: (Boolean) -> Unit) {
        val user = _user.value ?: return onResult(false)
        viewModelScope.launch {
            val ok = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.cancelSos(idToken, childId)
            }.getOrDefault(false)
            onResult(ok)
        }
    }

    /**
     * Marca un evento come "visto" — chiamato da MapScreen.kt quando
     * mostra un evento "posizione inviata dal bambino" non ancora
     * marcato (vedi backend/api/parent-command.js, azione ack_event).
     * Fire-and-forget: nessun feedback in UI, non e' un'azione che
     * l'utente ha scelto esplicitamente di fare.
     */
    fun ackEvent(childId: String, eventId: String) {
        val user = _user.value ?: return
        viewModelScope.launch {
            runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.ackEvent(idToken, childId, eventId)
            }
        }
    }

    /** Aggiorna il proprio nickname (scrittura diretta Firestore, vedi DeviceRepository). */
    fun updateOwnNickname(nickname: String, onDone: (Boolean) -> Unit) {
        val user = _user.value ?: return onDone(false)
        viewModelScope.launch {
            val ok = runCatching { deviceRepository.updateOwnNickname(user.uid, nickname) }.isSuccess
            onDone(ok)
        }
    }

    /** Rinomina un bambino (passa dal backend, devices/* e' scrivibile solo da li'). */
    fun setChildNickname(childId: String, nickname: String, onDone: (Boolean) -> Unit) {
        val user = _user.value ?: return onDone(false)
        viewModelScope.launch {
            val ok = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.setChildNickname(idToken, childId, nickname)
            }.getOrDefault(false)
            onDone(ok)
        }
    }

    /**
     * Registra un nuovo bambino. onResult(null) se la chiamata fallisce,
     * altrimenti il childId + il token in chiaro da mostrare una volta
     * sola (vedi BackendClient.createChild).
     */
    fun createChild(nickname: String, onResult: (NewChildResult?) -> Unit) {
        val user = _user.value ?: return onResult(null)
        viewModelScope.launch {
            val result = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.createChild(idToken, nickname)
            }.getOrNull()
            onResult(result)
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
