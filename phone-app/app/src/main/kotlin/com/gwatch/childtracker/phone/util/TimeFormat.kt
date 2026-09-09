package com.gwatch.childtracker.phone.util

import java.util.concurrent.TimeUnit

fun formatRelativeTime(millis: Long): String {
    val diffMs = System.currentTimeMillis() - millis
    if (diffMs < 0) return "Aggiornato adesso"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    return when {
        minutes < 1 -> "Aggiornato adesso"
        minutes < 60 -> "Aggiornato $minutes min fa"
        minutes < 24 * 60 -> "Aggiornato ${minutes / 60} h fa"
        else -> "Aggiornato ${minutes / (24 * 60)} giorni fa"
    }
}
