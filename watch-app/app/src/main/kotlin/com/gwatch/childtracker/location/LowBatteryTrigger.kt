package com.gwatch.childtracker.location

import android.content.Context

/**
 * Versione: 0.1.0 (2026-09-24)
 *
 * Segnalato dall'utente il 24/9: l'avviso "batteria scarica" e' arrivato
 * sul telefono solo alle 15:39, quando ha chiesto la posizione, anche se
 * il watch era sceso al 10% alle 15:21. L'avviso lo manda il backend
 * (_lib/batteryAlerts.js) quando riceve un dato di batteria, ma i punti
 * del tracking restano sul watch fino al caricamento a gruppi (ogni 15'
 * con WorkManager, o dopo 15 punti), che da fermo e a schermo spento
 * Android rinvia ancora di piu' (Doze/risparmio energetico, attivo
 * proprio a batteria bassa).
 *
 * Qui il watch decide da solo quando serve un caricamento subito: la
 * prima volta che la batteria scende a una delle soglie del backend
 * (10/5/2 %, stesse di batteryAlerts.js THRESHOLDS) in una scarica.
 * Si azzera in carica o sopra il 15% (come RESET_ABOVE_PERCENT del
 * backend). Stato in SharedPreferences: sopravvive ai riavvii del processo.
 */
object LowBatteryTrigger {
    private const val PREFS = "low_battery_trigger"
    private const val KEY_LAST_LEVEL = "last_level"
    private val THRESHOLDS = listOf(10, 5, 2)
    private const val RESET_ABOVE_PERCENT = 15

    /** true se questo dato di batteria ha appena superato una nuova soglia. */
    fun crossedNewThreshold(context: Context, percent: Int?, charging: Boolean?): Boolean {
        if (percent == null) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getInt(KEY_LAST_LEVEL, -1)
        if (charging == true || percent > RESET_ABOVE_PERCENT) {
            if (last != -1) prefs.edit().remove(KEY_LAST_LEVEL).apply()
            return false
        }
        // Soglia piu' severa raggiunta (THRESHOLDS e' dalla meno severa).
        val crossed = THRESHOLDS.lastOrNull { percent <= it } ?: return false
        if (last != -1 && last <= crossed) return false
        prefs.edit().putInt(KEY_LAST_LEVEL, crossed).apply()
        return true
    }
}
