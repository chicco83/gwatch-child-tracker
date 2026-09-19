package com.gwatch.childtracker.location

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Stato batteria (percentuale, temperatura, in carica, autonomia
 * residua) letto in un solo punto invece che duplicato — prima ogni
 * worker/service aveva la propria copia di "currentBatteryPercent()"
 * (solo percentuale, via BatteryManager.getIntProperty). Richiesto
 * dall'utente il 2026-09-18 ("aggiungi anche la temperatura batteria e
 * lo stato di carica").
 *
 * L'intento sticky ACTION_BATTERY_CHANGED si legge in modo sincrono
 * passando null come BroadcastReceiver a registerReceiver — pattern
 * standard Android, non serve nessun receiver registrato davvero: il
 * sistema tiene sempre in cache l'ultimo intento di questo tipo e lo
 * restituisce subito. Nessun permesso richiesto, nessun costo di
 * batteria aggiuntivo (a differenza del GPS, qui non si avvia nessun
 * sensore, si legge solo lo stato gia' mantenuto dal sistema).
 *
 * v0.10.0 (2026-09-19): aggiunta "hoursRemaining" — richiesto
 * dall'utente ("autonomia in ore"), chiesta al sistema operativo
 * invece di stimata da noi da uno storico (vedi readHoursRemaining()).
 */
data class BatterySnapshot(
    val percent: Int?,
    val temperatureC: Double?,
    val isCharging: Boolean?,
    val hoursRemaining: Double?,
)

object BatteryInfo {
    // Intervallo di plausibilita' per la stima hardware (vedi
    // readHoursRemaining sotto): scarta i valori chiaramente assurdi
    // invece di mostrarli in app — meglio nessuna stima che una sbagliata.
    private const val MIN_PLAUSIBLE_HOURS = 0.1
    private const val MAX_PLAUSIBLE_HOURS = 100.0

    fun read(context: Context): BatterySnapshot {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatterySnapshot(null, null, null, null)

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else null

        // EXTRA_TEMPERATURE e' in decimi di grado Celsius (es. 315 = 31.5°C).
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperatureC = if (tenths != Int.MIN_VALUE) tenths / 10.0 else null

        // EXTRA_PLUGGED e' diverso da 0 se collegato a un alimentatore
        // (AC/USB/wireless — sul watch, il caricabatterie a induzione):
        // risponde direttamente a "sta caricando adesso", a differenza
        // di EXTRA_STATUS che distinguerebbe anche "pieno mentre ancora
        // collegato" come stato separato, distinzione non utile qui.
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val isCharging = if (plugged != -1) plugged != 0 else null

        // Autonomia residua: ha senso solo in scarica, non mentre e' in
        // carica (li' il sistema stimerebbe semmai un tempo di carica
        // completa, non richiesto qui).
        val hoursRemaining = if (isCharging == false) readHoursRemaining(context) else null

        return BatterySnapshot(percent, temperatureC, isCharging, hoursRemaining)
    }

    /**
     * Stima dell'autonomia residua chiesta direttamente al sistema
     * operativo (come richiesto dall'utente), non calcolata da noi con
     * uno storico di campioni: BatteryManager espone due proprieta'
     * hardware — BATTERY_PROPERTY_CHARGE_COUNTER (capacita' residua in
     * microampere-ora) e BATTERY_PROPERTY_CURRENT_NOW (corrente
     * istantanea in microampere, negativa in scarica) — lo stesso dato
     * grezzo che il sistema usa per le proprie stime di batteria in
     * Impostazioni. ore = capacita' residua / corrente di scarica
     * (µAh / µA = h). Nessuno storico da accumulare, nessuna chiamata
     * di rete: il valore e' gia' pronto ad ogni lettura, e si aggiorna
     * da solo con l'uso reale (schermo/GPS/LTE) invece di essere
     * un'estrapolazione lineare dai campioni precedenti.
     *
     * Non tutti i dispositivi/kernel espongono correttamente queste
     * proprieta': BatteryManager.getIntProperty ritorna Int.MIN_VALUE
     * se la proprieta' non e' supportata, e su alcuni kernel
     * CURRENT_NOW e' riportato in un'unita' sbagliata (bug noto,
     * es. nanoampere invece di microampere). Il controllo di
     * plausibilita' (MIN/MAX_PLAUSIBLE_HOURS) scarta i risultati
     * chiaramente assurdi: se il dato hardware non e' affidabile su
     * questo dispositivo, meglio non mostrare nulla che un numero
     * sbagliato.
     */
    private fun readHoursRemaining(context: Context): Double? {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null

        val chargeCounterUAh = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val currentNowUA = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        // currentNowUA >= 0 (o Int.MIN_VALUE, non supportata) non e'
        // scarica utilizzabile per questo calcolo.
        if (chargeCounterUAh <= 0 || currentNowUA >= 0) return null

        val hours = chargeCounterUAh.toDouble() / -currentNowUA.toDouble()
        return hours.takeIf { it in MIN_PLAUSIBLE_HOURS..MAX_PLAUSIBLE_HOURS }
    }
}
