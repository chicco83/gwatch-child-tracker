package com.gwatch.childtracker.location

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Stato batteria (percentuale, temperatura, in carica) letto in un solo
 * punto invece che duplicato — prima ogni worker/service aveva la
 * propria copia di "currentBatteryPercent()" (solo percentuale, via
 * BatteryManager.getIntProperty). Richiesto dall'utente il 2026-09-18
 * ("aggiungi anche la temperatura batteria e lo stato di carica").
 *
 * L'intento sticky ACTION_BATTERY_CHANGED si legge in modo sincrono
 * passando null come BroadcastReceiver a registerReceiver — pattern
 * standard Android, non serve nessun receiver registrato davvero: il
 * sistema tiene sempre in cache l'ultimo intento di questo tipo e lo
 * restituisce subito. Nessun permesso richiesto, nessun costo di
 * batteria aggiuntivo (a differenza del GPS, qui non si avvia nessun
 * sensore, si legge solo lo stato gia' mantenuto dal sistema).
 */
data class BatterySnapshot(
    val percent: Int?,
    val temperatureC: Double?,
    val isCharging: Boolean?,
)

object BatteryInfo {
    fun read(context: Context): BatterySnapshot {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatterySnapshot(null, null, null)

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

        return BatterySnapshot(percent, temperatureC, isCharging)
    }
}
