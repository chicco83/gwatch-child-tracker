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
 *
 * - 0.2.0 (2026-11-21): lo storico e' limitato alle ultime 24 ore e a 20
 *   messaggi (come il backend), anche per quelli aggiunti in locale; in
 *   piu' needsRefresh() evita di riscaricarlo se l'ultimo scaricamento
 *   riuscito ha meno di 5 minuti. Motivo: dal watch non ha senso vedere
 *   messaggi vecchi e ogni apertura della chat costava una chiamata al
 *   backend (con quota e 30 letture su Firestore).
 */
object MessageStore {
    const val MAX_AGE_MS = 24L * 3_600_000
    const val MAX_MESSAGES = 20
    const val REFRESH_MIN_INTERVAL_MS = 5L * 60_000

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    @Volatile
    private var lastFetchMillis = 0L

    /** true se non c'e' uno scaricamento riuscito negli ultimi 5 minuti. */
    fun needsRefresh(now: Long = System.currentTimeMillis()): Boolean =
        now - lastFetchMillis >= REFRESH_MIN_INTERVAL_MS

    fun replaceAll(messages: List<ChatMessage>) {
        lastFetchMillis = System.currentTimeMillis()
        _messages.value = trim(messages)
    }

    fun append(message: ChatMessage) {
        _messages.value = trim(_messages.value + message)
    }

    private fun trim(list: List<ChatMessage>): List<ChatMessage> {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        return list.filter { it.timestampMillis >= cutoff }.takeLast(MAX_MESSAGES)
    }
}
