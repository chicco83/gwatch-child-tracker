package com.gwatch.childtracker.data

import com.gwatch.childtracker.network.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stato della chat in memoria, condiviso tra FcmService (che riceve i
 * messaggi in push) e ChatScreen (che li mostra). Niente persistenza
 * locale: allo stesso modo di come il watch non tiene uno storico
 * posizioni proprio, alla riapertura dell'app la cronologia arriva di
 * nuovo dal backend (BackendClient.fetchMessages) — piu' semplice che
 * sincronizzare un database locale con quello lato server.
 */
object MessageStore {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun replaceAll(messages: List<ChatMessage>) {
        _messages.value = messages
    }

    fun append(message: ChatMessage) {
        _messages.value = _messages.value + message
    }
}
