package com.gwatch.childtracker.sos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stato "SOS attivo" osservabile dalla UI (MainActivity) — prima non
 * c'era alcun riscontro visivo sul watch, ne' alla conferma ne' alla
 * disattivazione: SosLocationService aggiorna questo stato al proprio
 * avvio/arresto, qualunque sia la causa dell'arresto (push
 * "sos_cancel" dal genitore, 409 dal backend, o il cap di sicurezza a
 * 3 ore) — un solo punto di verita', stesso pattern di MessageStore.
 */
object SosState {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setActive(value: Boolean) {
        _active.value = value
    }
}
