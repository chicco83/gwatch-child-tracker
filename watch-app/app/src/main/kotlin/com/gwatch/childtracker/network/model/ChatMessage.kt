package com.gwatch.childtracker.network.model

import org.json.JSONObject

// v0.3.0 (2026-09-11): fase 4/4, supporto N bambini (vedi CONTEXT.md).
// Aggiunto senderName (nickname del genitore che ha scritto, denormalizzato
// lato backend) per mostrare il nome vero sopra i messaggi ricevuti invece
// di un'etichetta generica "Genitore" fissa. "sender" resta sufficiente per
// l'allineamento delle bolle: ogni watch ha il proprio thread dedicato
// (devices/{childId}/messages), quindi sender=="child" significa sempre "io",
// senza bisogno di un'identita' esplicita del bambino qui.
data class ChatMessage(
    val sender: String, // "parent" | "child"
    val senderName: String? = null,
    val text: String,
    val timestampMillis: Long,
) {
    companion object {
        fun fromJson(json: JSONObject): ChatMessage = ChatMessage(
            sender = json.getString("sender"),
            senderName = json.optString("senderName").takeIf { it.isNotBlank() },
            text = json.getString("text"),
            timestampMillis = json.getLong("timestamp"),
        )
    }
}
