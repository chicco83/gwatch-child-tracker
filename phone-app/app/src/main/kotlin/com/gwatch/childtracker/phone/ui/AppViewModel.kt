package com.gwatch.childtracker.phone.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.messaging.FirebaseMessaging
import com.gwatch.childtracker.phone.auth.AuthRepository
import com.gwatch.childtracker.phone.data.BackendClient
import com.gwatch.childtracker.phone.data.DeviceRepository
import com.gwatch.childtracker.phone.data.FamilyInviteResult
import com.gwatch.childtracker.phone.data.IncomingMessageStore
import com.gwatch.childtracker.phone.data.KnownChildrenCache
import com.gwatch.childtracker.phone.data.NewChildResult
import com.gwatch.childtracker.phone.data.model.ChatMessage
import com.gwatch.childtracker.phone.data.model.ChildInfo
import com.gwatch.childtracker.phone.data.model.DeviceEvent
import com.gwatch.childtracker.phone.data.model.DeviceState
import com.gwatch.childtracker.phone.data.model.GeofenceZone
import com.gwatch.childtracker.phone.data.model.LocationRetry
import com.gwatch.childtracker.phone.data.model.LocationPoint
import com.gwatch.childtracker.phone.util.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
// v0.30.0 (2026-09-11): fase 4/4 (ultima) — selettore destinatario
// chat. "messages" non e' piu' un flow fisso su Constants.DEVICE_ID: fa
// flatMapLatest su "selectedChatChildId" (scelto dall'utente in
// ChatScreen.kt, o il primo bambino noto se non ancora scelto), ricrea
// il listener Firestore sul thread giusto quando cambia destinatario.
// sendMessage ora scrive senderId (proprio uid)/senderName (proprio
// nickname)/childId sul messaggio ottimistico, per allinearlo allo
// stesso schema del documento reale (ChatScreen.kt allinea le bolle su
// senderId == proprio uid, non piu' sul solo ruolo "parent"/"child" —
// necessario ora che due genitori condividono lo stesso thread).
// v0.31.0 (2026-09-23): due interventi distinti.
// (1) Bug trovato lavorando sul punto (2) sotto, NON dal documento di
// review: "children"/"geofences" leggevano deviceRepository.
// observeChildren()/observeGeofences() SENZA filtro familyId — le
// regole v0.7.0 rifiutano in blocco una query non provabilmente
// vincolata (vedi DeviceRepository.kt v0.8.0), quindi appena
// pubblicate quelle regole la lista bambini/zone avrebbe smesso di
// caricarsi per chiunque. Entrambe ora derivano da "ownFamilyId" con
// flatMapLatest (null finche' non risolto -> lista vuota), passato
// come parametro alle query.
// (2) Fase 2 di qwen_plan.md (individuato da qwen3.8-27B-UD-IQ4_XS,
// implementato da Sonnet 5): topic FCM per-bambino al posto del topic
// globale "parents" (vedi Constants.kt/backend). Un collector interno
// su "children" (avviato nell'init, vive quanto il ViewModel — la
// sottoscrizione FCM e' un'operazione server-side una tantum, non ha
// bisogno di restare legata alla UI in primo piano) mantiene allineate
// le iscrizioni FirebaseMessaging: iscrive i topic dei bambini nuovi,
// disiscrive quelli non piu' nella lista, e scrive la stessa lista in
// KnownChildrenCache (letta da FcmService.onMessageReceived come
// seconda barriera). Su signOut(), disiscrizione di tutti i topic
// correnti e pulizia della cache: altrimenti il telefono resterebbe
// iscritto ai topic della famiglia precedente anche dopo un cambio
// account.
class AppViewModel(
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val application: Application,
    private val backendClient: BackendClient = BackendClient(),
) : ViewModel() {

    private val _user = MutableStateFlow(authRepository.currentUser)
    val user: StateFlow<FirebaseUser?> = _user.asStateFlow()

    // Proprio nickname (parents/{uid}.nickname) — segue l'utente loggato,
    // null se nessuno ha ancora fatto login o non l'ha ancora impostato.
    @OptIn(ExperimentalCoroutinesApi::class)
    val ownNickname: StateFlow<String?> = user
        .flatMapLatest { u -> if (u == null) flowOf(null) else deviceRepository.observeOwnNickname(u.uid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // v0.31.0 (2026-09-22): individuato da qwen3.8-27B-UD-IQ4_XS,
    // implementato da Sonnet 5 — isolamento famiglie (vedi
    // backend/firestore.rules v0.7.0). Null finche' il genitore non ha
    // ancora creato/unito una famiglia (nessun bambino/invito ancora
    // fatto) — saveGeofence() sotto lo stampa su ogni zona nuova, le
    // regole lo richiedono per leggere/scrivere.
    @OptIn(ExperimentalCoroutinesApi::class)
    val ownFamilyId: StateFlow<String?> = user
        .flatMapLatest { u -> if (u == null) flowOf(null) else deviceRepository.observeOwnFamilyId(u.uid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // v0.31.0 (2026-09-23): ora derivati da "ownFamilyId", non piu' da
    // una query senza filtro — vedi Storico versioni sopra (punto 1).
    @OptIn(ExperimentalCoroutinesApi::class)
    val children: StateFlow<List<ChildInfo>> = ownFamilyId
        .flatMapLatest { familyId -> if (familyId == null) flowOf(emptyList()) else deviceRepository.observeChildren(familyId) }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val geofences: StateFlow<List<GeofenceZone>> = ownFamilyId
        .flatMapLatest { familyId -> if (familyId == null) flowOf(emptyList()) else deviceRepository.observeGeofences(familyId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // v0.31.0 (2026-09-23): Fase 2 di qwen_plan.md — vedi Storico
    // versioni sopra (punto 2). Collector indipendente dalla UI:
    // mantiene le iscrizioni FCM per-bambino allineate a "children" per
    // tutta la vita del ViewModel, non solo mentre una schermata lo
    // osserva (le iscrizioni FCM devono restare valide anche ad app in
    // background). subscribedChildIds tiene l'ultimo insieme noto in
    // questo processo, per disiscrivere solo i topic che sono davvero
    // usciti dalla lista.
    private var subscribedChildIds: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            children.collect { list -> syncChildTopics(list.map { it.id }.toSet()) }
        }
    }

    private suspend fun syncChildTopics(currentIds: Set<String>) {
        val messaging = FirebaseMessaging.getInstance()
        val added = currentIds - subscribedChildIds
        val removed = subscribedChildIds - currentIds
        added.forEach { id -> runCatching { messaging.subscribeToTopic(Constants.fcmChildTopic(id)).await() } }
        removed.forEach { id -> runCatching { messaging.unsubscribeFromTopic(Constants.fcmChildTopic(id)).await() } }
        subscribedChildIds = currentIds
        KnownChildrenCache.update(application, currentIds)
    }

    private val _optimisticMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    private val _selectedChatChildId = MutableStateFlow<String?>(null)

    /** Thread di chat scelto dall'utente in ChatScreen.kt ("Scrivi a: ..."), null se non ancora scelto. */
    val selectedChatChildId: StateFlow<String?> = _selectedChatChildId.asStateFlow()

    /** Cambia il destinatario della chat corrente (selettore in ChatScreen.kt). */
    fun selectChatChild(childId: String) {
        _selectedChatChildId.value = childId
    }

    // Thread effettivo: quello scelto esplicitamente, o il primo bambino
    // noto finche' l'utente non ne sceglie uno (stesso comportamento di
    // oggi con un solo bambino, nessuna scelta richiesta finche' non ce
    // n'e' piu' di uno).
    @OptIn(ExperimentalCoroutinesApi::class)
    private val effectiveChatChildId: Flow<String?> = combine(_selectedChatChildId, children) { selected, list ->
        selected ?: list.firstOrNull()?.id
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<ChatMessage>> = effectiveChatChildId
        .flatMapLatest { childId ->
            if (childId == null) {
                flowOf(emptyList())
            } else {
                combine(
                    deviceRepository.observeMessages(childId),
                    _optimisticMessages,
                    IncomingMessageStore.messages,
                ) { remote, outgoingPending, incomingPending ->
                    val remoteKeys = remote.map { (it.senderId ?: it.sender) to it.text }.toSet()
                    val stillPending = (outgoingPending + incomingPending)
                        .filter { it.childId == childId }
                        .filterNot { ((it.senderId ?: it.sender) to it.text) in remoteKeys }
                    (remote + stillPending).sortedBy { it.timestampMillis }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /**
     * v0.31.0: prima di disconnettersi, disiscrive tutti i topic
     * per-bambino correnti e pulisce KnownChildrenCache — altrimenti il
     * telefono resterebbe iscritto ai topic della famiglia precedente
     * (e la cache conterrebbe ancora quei childId) anche dopo il login
     * di un genitore diverso sullo stesso dispositivo.
     */
    fun signOut() {
        val messaging = FirebaseMessaging.getInstance()
        subscribedChildIds.forEach { id -> messaging.unsubscribeFromTopic(Constants.fcmChildTopic(id)) }
        subscribedChildIds = emptySet()
        KnownChildrenCache.clear(application)
        authRepository.signOut()
        _user.value = null
    }

    /**
     * Una zona nuova (familyId ancora vuoto) viene stampata con la
     * propria famiglia prima di scrivere — mai scelta dall'utente, le
     * regole Firestore rifiutano altrimenti la creazione (vedi
     * backend/firestore.rules v0.7.0). Una zona esistente arriva gia'
     * col suo familyId (da observeGeofences), invariato in aggiornamento.
     */
    fun saveGeofence(zone: GeofenceZone, onDone: () -> Unit) {
        viewModelScope.launch {
            val stamped = if (zone.familyId.isBlank()) zone.copy(familyId = ownFamilyId.value.orEmpty()) else zone
            runCatching { deviceRepository.saveGeofence(stamped) }
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
     * davvero. Va al thread "effettivo" corrente (vedi
     * effectiveChatChildId sopra): quello scelto in ChatScreen.kt, o il
     * primo bambino noto se l'utente non ha ancora scelto.
     */
    fun sendMessage(text: String, onSent: (Boolean) -> Unit) {
        val user = _user.value ?: return onSent(false)
        val childId = _selectedChatChildId.value ?: children.value.firstOrNull()?.id ?: return onSent(false)
        val optimistic = ChatMessage(
            sender = "parent",
            senderId = user.uid,
            senderName = ownNickname.value,
            childId = childId,
            text = text,
            timestampMillis = System.currentTimeMillis(),
        )
        _optimisticMessages.value = _optimisticMessages.value + optimistic
        viewModelScope.launch {
            val ok = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.sendMessageToChild(idToken, childId, text)
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
    // 2026-09-23: richiesta utente — se la posizione non arriva, la app
    // "insiste": conto alla rovescia visibile (retryStates, barra nella
    // StatusCard) e nuova richiesta, fino a MAX_LOCATION_ATTEMPTS. Esito
    // ricavato da cio' che scrive il watch su devices/{id}:
    // - successo: lastSeen piu' recente della richiesta (nuova posizione);
    // - fallimento: lastStatusAt piu' recente senza nuova posizione (il
    //   watch ha provato e non ha trovato il fix, vedi trigger-event.js
    //   type "status"), oppure nulla entro RESPONSE_TIMEOUT_MS.
    // Una nuova richiesta manuale per lo stesso bambino riparte da capo.
    // Precedente: una sola richiesta, esito = solo "push partita".
    private val _retryStates = MutableStateFlow<Map<String, LocationRetry>>(emptyMap())
    val retryStates: StateFlow<Map<String, LocationRetry>> = _retryStates.asStateFlow()
    private val insistJobs = mutableMapOf<String, Job>()

    fun requestLocation(childId: String, onResult: (Boolean) -> Unit) {
        val user = _user.value ?: return onResult(false)
        insistJobs.remove(childId)?.cancel()
        _retryStates.value = _retryStates.value - childId
        insistJobs[childId] = viewModelScope.launch {
            var attempt = 1
            while (true) {
                val requestedAt = System.currentTimeMillis()
                val ok = sendLocationRequest(user, childId)
                if (attempt == 1) onResult(ok)
                // 2026-09-23: barra "in attesa del watch" durante l'attesa della
                // risposta. Precedente: nessuno stato visibile per 2 minuti.
                if (ok) {
                    _retryStates.value = _retryStates.value + (
                        childId to LocationRetry(
                            nextRetryAtMillis = requestedAt + RESPONSE_TIMEOUT_MS,
                            totalWaitMillis = RESPONSE_TIMEOUT_MS,
                            attempt = attempt - 1,
                            waitingForWatch = true,
                        )
                    )
                }
                val succeeded = ok && waitForLocation(childId, requestedAt)
                _retryStates.value = _retryStates.value - childId
                if (succeeded || attempt >= MAX_LOCATION_ATTEMPTS) break
                _retryStates.value = _retryStates.value + (
                    childId to LocationRetry(
                        nextRetryAtMillis = System.currentTimeMillis() + RETRY_DELAY_MS,
                        totalWaitMillis = RETRY_DELAY_MS,
                        attempt = attempt,
                    )
                )
                // 2026-09-24: durante il conto alla rovescia si continua a
                // guardare se arriva una posizione (es. dal tracking dopo la
                // riaccensione del watch): se arriva, stop. Prima un semplice
                // delay(RETRY_DELAY_MS) lasciava la barra a schermo anche con
                // la posizione gia' ricevuta (segnalato dall'utente).
                val arrived = withTimeoutOrNull(RETRY_DELAY_MS) {
                    deviceStates.first { states ->
                        (states[childId]?.lastSeenMillis ?: 0L) > requestedAt - CLOCK_MARGIN_MS
                    }
                } != null
                _retryStates.value = _retryStates.value - childId
                if (arrived) break
                attempt++
            }
            _retryStates.value = _retryStates.value - childId
            insistJobs.remove(childId)
        }
    }

    private suspend fun sendLocationRequest(user: FirebaseUser, childId: String): Boolean =
        runCatching {
            val idToken = user.getIdToken(false).await().token ?: error("token nullo")
            backendClient.requestLocation(idToken, childId)
        }.getOrDefault(false)

    // true = arrivata una posizione nuova; false = il watch ha risposto
    // senza posizione, oppure nessuna risposta entro il timeout.
    // CLOCK_MARGIN_MS tollera piccole differenze fra orologio del telefono,
    // del watch e del server.
    private suspend fun waitForLocation(childId: String, requestedAt: Long): Boolean {
        val threshold = requestedAt - CLOCK_MARGIN_MS
        val state = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
            deviceStates.first { states ->
                val s = states[childId] ?: return@first false
                // 2026-09-24: lastNoFixMillis al posto di lastStatusMillis
                // (vedi DeviceState.lastNoFixMillis).
                (s.lastSeenMillis ?: 0L) > threshold || (s.lastNoFixMillis ?: 0L) > threshold
            }[childId]
        } ?: return false
        return (state.lastSeenMillis ?: 0L) > threshold
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

    // Bug (2026-09-18, trovato dalla build reale in Android Studio): il
    // KDoc qui sotto era "/** ... devices/* ... */" — quel "devices/*"
    // (wildcard Firestore nella prosa) apre un commento annidato in
    // Kotlin (i block comment si annidano), che il "*/" di fine riga
    // chiudeva al posto di quello esterno: il commento restava aperto
    // fino a fine file ("Unclosed comment"), con conseguente "Missing
    // '}'" alla prima graffa che il parser non vedeva piu'. Riscritto
    // senza l'asterisco per non riaprire il problema.
    /** Rinomina un bambino (passa dal backend, la collezione devices e' scrivibile solo da li'). */
    // 2026-09-24: alta precisione del tracking da fermo (SettingsScreen).
    fun setTrackingHighAccuracy(childId: String, highAccuracy: Boolean, onDone: (Boolean) -> Unit) {
        val user = _user.value ?: return onDone(false)
        viewModelScope.launch {
            val ok = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.setTrackingMode(idToken, childId, highAccuracy)
            }.getOrDefault(false)
            onDone(ok)
        }
    }

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

    /** Genera un codice d'invito per un secondo genitore (vedi SettingsScreen.kt). */
    fun createFamilyInvite(onResult: (FamilyInviteResult?) -> Unit) {
        val user = _user.value ?: return onResult(null)
        viewModelScope.launch {
            val result = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.createFamilyInvite(idToken)
            }.getOrNull()
            onResult(result)
        }
    }

    /** Unisce il genitore loggato alla famiglia del codice d'invito (vedi SettingsScreen.kt). */
    fun acceptFamilyInvite(inviteCode: String, onResult: (Boolean) -> Unit) {
        val user = _user.value ?: return onResult(false)
        viewModelScope.launch {
            val ok = runCatching {
                val idToken = user.getIdToken(false).await().token ?: error("token nullo")
                backendClient.acceptFamilyInvite(idToken, inviteCode)
            }.getOrDefault(false)
            onResult(ok)
        }
    }

    class Factory(
        private val authRepository: AuthRepository,
        private val deviceRepository: DeviceRepository,
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(authRepository, deviceRepository, application) as T
    }
}

// 2026-09-23: insistenza sulla richiesta di posizione (AppViewModel.requestLocation).
// Attesa della risposta del watch: copre i 90s massimi del suo tentativo GPS
// (LocationRequestWorker.FIX_TIMEOUT_MS) piu' il tempo di consegna della push.
private const val RESPONSE_TIMEOUT_MS = 120_000L
private const val RETRY_DELAY_MS = 60_000L
// 20 tentativi: circa un'ora di insistenza con l'app aperta.
private const val MAX_LOCATION_ATTEMPTS = 20
private const val CLOCK_MARGIN_MS = 10_000L
