package com.gwatch.childtracker.phone.data

// Storico versioni
// v0.1.0 (2026-09-23): Fase 2 di qwen_plan.md — "minimo sindacale da
//   fare comunque" (individuato da qwen3.8-27B-UD-IQ4_XS, implementato
//   da Sonnet 5): seconda barriera oltre al topic FCM per-bambino
//   (vedi Constants.fcmChildTopic). FcmService.onMessageReceived() gira
//   fuori da qualunque contesto Compose/ViewModel e non puo' aspettare
//   una lettura Firestore dentro l'handler di una push (budget di
//   tempo stretto, e deve funzionare anche offline) — questa cache
//   locale (SharedPreferences) tiene gli id dei propri bambini,
//   scritta da AppViewModel ad ogni cambio di "children", letta qui in
//   modo sincrono per scartare una push di un childId non piu' (o mai
//   stato) proprio, es. una sottoscrizione al topic rimasta residua.

import android.content.Context

object KnownChildrenCache {
    private const val PREFS_NAME = "known_children"
    private const val KEY_IDS = "child_ids"

    fun update(context: Context, childIds: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_IDS, childIds)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_IDS)
            .apply()
    }

    fun contains(context: Context, childId: String): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_IDS, emptySet())
            ?.contains(childId) == true
}
