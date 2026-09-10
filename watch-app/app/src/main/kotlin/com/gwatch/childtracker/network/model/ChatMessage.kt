package com.gwatch.childtracker.network.model

import org.json.JSONObject

data class ChatMessage(
    val sender: String, // "parent" | "child"
    val text: String,
    val timestampMillis: Long,
) {
    companion object {
        fun fromJson(json: JSONObject): ChatMessage = ChatMessage(
            sender = json.getString("sender"),
            text = json.getString("text"),
            timestampMillis = json.getLong("timestamp"),
        )
    }
}
