package com.gwatch.childtracker.phone.data

// Storico versioni
// v0.1.0 (2026-09-10): nuovo. Echo locale dei messaggi ricevuti dal
//   watch via push FCM (vedi messaging/FcmService.kt), stesso ruolo di
//   _optimisticMessages in AppViewModel.kt ma per la direzione opposta:
//   mostra il messaggio subito nella schermata Chat senza aspettare il
//   listener Firestore (che nella segnalazione dell'utente non lo
//   mostrava affatto: la chat dipendeva solo da quello, mai popolato
//   quando il messaggio arrivava come notifica "mista" e l'app era in
//   background — vedi backend/api/send-message.js v0.3.0). Stesso
//   pattern "object + MutableStateFlow" gia' usato sul watch per
//   MessageStore/SosState.

import com.gwatch.childtracker.phone.data.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object IncomingMessageStore {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun append(message: ChatMessage) {
        _messages.value = _messages.value + message
    }
}
