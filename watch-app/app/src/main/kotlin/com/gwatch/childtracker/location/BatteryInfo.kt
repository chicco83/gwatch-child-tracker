package com.gwatch.childtracker.location

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

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
 *
 * v0.11.0 (2026-09-24): segnalato dall'utente — l'autonomia non e' MAI
 * comparsa sulla phone-app. Verificato con diag-device-history.js: il
 * campo arriva al backend ma sempre null, quindi il calcolo qui scartava
 * ogni valore. Cause probabili (Galaxy Watch4, kernel Samsung): (1)
 * CURRENT_NOW riportato in mA invece che in µA → ore 1000 volte troppo
 * alte, scartate dal controllo di plausibilita'; (2) segno della
 * corrente in scarica positivo invece che negativo → scartato dal
 * controllo "currentNowUA >= 0"; (3) proprieta' non supportate. Due
 * correzioni:
 *  - lettura hardware normalizzata (unita' e segno), vedi
 *    readHardwareHours(); i valori grezzi finiscono nel log
 *    (tag "BatteryInfo") per verificarli con adb logcat;
 *  - stima dall'andamento della percentuale da quando il watch e' stato
 *    scollegato (readTrendHours()), che non dipende dal kernel e ha la
 *    precedenza: la corrente istantanea viene letta proprio mentre l'app
 *    usa GPS/LTE e sottostima molto l'autonomia (es. 2h invece di 30h).
 *    La lettura hardware resta come valore iniziale finche' la
 *    percentuale non e' scesa abbastanza per una stima dall'andamento.
 *
 * v0.12.0 (2026-09-24): richiesta utente — "non devi calcolare la durata
 * stimata ma chiederla a Wear OS". Tolte entrambe le stime calcolate da
 * noi (andamento e formula hardware, lasciate sotto come commento).
 * L'autonomia ora e' SOLO quella del sistema: PowerManager
 * .getBatteryDischargePrediction() (API 31+, nessun permesso per
 * leggerla), la stessa previsione che il sistema mostra nelle
 * impostazioni della batteria. Se il sistema non ne ha una (ritorna
 * null, o Wear OS 3 = API 30) l'autonomia non viene inviata e la riga
 * non compare sulla phone-app: nessun valore inventato da noi.
 */
data class BatterySnapshot(
    val percent: Int?,
    val temperatureC: Double?,
    val isCharging: Boolean?,
    val hoursRemaining: Double?,
)

object BatteryInfo {
    private const val TAG = "BatteryInfo"
    // v0.12.0 (2026-09-24): costanti della v0.11.0 non piu' usate (stime
    // nostre tolte su richiesta dell'utente), lasciate come commento:
    //
    // // v0.11.0: ancoraggio per la stima dall'andamento (readTrendHours).
    // private const val PREFS = "battery_trend"
    // private const val KEY_ANCHOR_TIME = "anchor_time"
    // private const val KEY_ANCHOR_PERCENT = "anchor_percent"
    // // Serve un calo minimo per una stima sensata: la percentuale e' a
    // // scatti di 1%, con 1 solo punto di calo l'errore sarebbe enorme.
    // private const val MIN_TREND_DROP_PERCENT = 2
    // private const val MIN_TREND_ELAPSED_MS = 20 * 60 * 1000L
    //
    // // v0.11.0: sotto questi valori il kernel sta usando mA/mAh invece di
    // // µA/µAh (nessun watch in uso assorbe meno di 2 mA = 2000 µA, e la
    // // batteria del Watch4 e' 247-361 mAh = 247000-361000 µAh).
    // private const val MAX_CURRENT_IN_MA = 2_000
    // private const val MAX_CHARGE_IN_MAH = 5_000

    // Intervallo di plausibilita' per l'autonomia (dalla v0.12.0 applicato
    // alla previsione di Wear OS, readSystemPrediction): scarta i valori
    // chiaramente assurdi invece di mostrarli in app.
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
        // v0.11.0: prima la stima dall'andamento, poi quella hardware; in
        // carica si azzera l'ancoraggio (la prossima scarica riparte da zero).
        // Precedente (2026-09-19):
        // val hoursRemaining = if (isCharging == false) readHoursRemaining(context) else null
        // v0.12.0: solo la previsione di Wear OS (vedi readSystemPrediction).
        // Precedente (2026-09-24, v0.11.0):
        // val hoursRemaining = when (isCharging) {
        //     false -> readTrendHours(context, percent) ?: readHardwareHours(context)
        //     true -> {
        //         resetTrend(context)
        //         null
        //     }
        //     null -> null
        // }
        val hoursRemaining = if (isCharging == false) readSystemPrediction(context) else null

        return BatterySnapshot(percent, temperatureC, isCharging, hoursRemaining)
    }

    // Precedente (2026-09-19), sostituita dalla v0.11.0 con
    // readHardwareHours() (unita'/segno normalizzati) + readTrendHours():
    // /**
    //  * Stima dell'autonomia residua chiesta direttamente al sistema
    //  * operativo (come richiesto dall'utente), non calcolata da noi con
    //  * uno storico di campioni: BatteryManager espone due proprieta'
    //  * hardware — BATTERY_PROPERTY_CHARGE_COUNTER (capacita' residua in
    //  * microampere-ora) e BATTERY_PROPERTY_CURRENT_NOW (corrente
    //  * istantanea in microampere, negativa in scarica) — lo stesso dato
    //  * grezzo che il sistema usa per le proprie stime di batteria in
    //  * Impostazioni. ore = capacita' residua / corrente di scarica
    //  * (µAh / µA = h). Nessuno storico da accumulare, nessuna chiamata
    //  * di rete: il valore e' gia' pronto ad ogni lettura, e si aggiorna
    //  * da solo con l'uso reale (schermo/GPS/LTE) invece di essere
    //  * un'estrapolazione lineare dai campioni precedenti.
    //  *
    //  * Non tutti i dispositivi/kernel espongono correttamente queste
    //  * proprieta': BatteryManager.getIntProperty ritorna Int.MIN_VALUE
    //  * se la proprieta' non e' supportata, e su alcuni kernel
    //  * CURRENT_NOW e' riportato in un'unita' sbagliata (bug noto,
    //  * es. nanoampere invece di microampere). Il controllo di
    //  * plausibilita' (MIN/MAX_PLAUSIBLE_HOURS) scarta i risultati
    //  * chiaramente assurdi: se il dato hardware non e' affidabile su
    //  * questo dispositivo, meglio non mostrare nulla che un numero
    //  * sbagliato.
    //  */
    // private fun readHoursRemaining(context: Context): Double? {
    //     val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    //         ?: return null
    //
    //     val chargeCounterUAh = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
    //     val currentNowUA = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    //     // currentNowUA >= 0 (o Int.MIN_VALUE, non supportata) non e'
    //     // scarica utilizzabile per questo calcolo.
    //     if (chargeCounterUAh <= 0 || currentNowUA >= 0) return null
    //
    //     val hours = chargeCounterUAh.toDouble() / -currentNowUA.toDouble()
    //     return hours.takeIf { it in MIN_PLAUSIBLE_HOURS..MAX_PLAUSIBLE_HOURS }
    // }

    /**
     * v0.12.0: autonomia residua chiesta a Wear OS
     * (PowerManager.getBatteryDischargePrediction, API 31). null se il
     * sistema non ha una previsione o se e' fuori dall'intervallo di
     * plausibilita'. Il valore (o la sua assenza) va nel log, tag
     * "BatteryInfo", per verificarlo con adb logcat.
     */
    private fun readSystemPrediction(context: Context): Double? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.i(TAG, "previsione di sistema non disponibile (API ${Build.VERSION.SDK_INT} < 31)")
            return null
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        val prediction = runCatching { powerManager.batteryDischargePrediction }
            .onFailure { Log.w(TAG, "lettura previsione fallita", it) }
            .getOrNull()
        if (prediction == null) {
            Log.i(TAG, "Wear OS non fornisce una previsione di autonomia")
            return null
        }
        val hours = prediction.toMinutes() / 60.0
        Log.i(TAG, "previsione Wear OS: %.1f h".format(hours))
        return hours.takeIf { it in MIN_PLAUSIBLE_HOURS..MAX_PLAUSIBLE_HOURS }
    }

    // Precedente (2026-09-24, v0.11.0), sostituito dalla v0.12.0 con
    // readSystemPrediction() su richiesta dell'utente (niente stime nostre):
    // /**
    //  * v0.11.0: stessa idea della vecchia readHoursRemaining() (capacita'
    //  * residua / corrente di scarica, dati del sistema operativo) ma
    //  * tollerante ai kernel che non rispettano la documentazione: segno
    //  * ignorato (siamo gia' sicuri di essere in scarica, EXTRA_PLUGGED=0),
    //  * valori piccoli interpretati come mA/mAh. Resta il controllo di
    //  * plausibilita': meglio nessun numero che uno sbagliato.
    //  */
    // private fun readHardwareHours(context: Context): Double? {
    //     val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    //         ?: return null
    //
    //     val rawCharge = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
    //     val rawCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    //     Log.i(TAG, "grezzi: CHARGE_COUNTER=$rawCharge CURRENT_NOW=$rawCurrent")
    //     // Int.MIN_VALUE = proprieta' non supportata; 0 = nessun dato.
    //     if (rawCharge == Int.MIN_VALUE || rawCharge <= 0) return null
    //     if (rawCurrent == Int.MIN_VALUE || rawCurrent == 0) return null
    //
    //     val chargeUAh = if (rawCharge < MAX_CHARGE_IN_MAH) rawCharge * 1000.0 else rawCharge.toDouble()
    //     val absCurrent = kotlin.math.abs(rawCurrent.toLong())
    //     val currentUA = if (absCurrent < MAX_CURRENT_IN_MA) absCurrent * 1000.0 else absCurrent.toDouble()
    //
    //     val hours = chargeUAh / currentUA
    //     Log.i(TAG, "stima hardware: %.1f h".format(hours))
    //     return hours.takeIf { it in MIN_PLAUSIBLE_HOURS..MAX_PLAUSIBLE_HOURS }
    // }
    //
    // /**
    //  * v0.11.0: autonomia dall'andamento reale della percentuale da quando
    //  * il watch e' stato scollegato (media su tutto il periodo, include
    //  * GPS/LTE/schermo come li usa davvero il bambino). Ancoraggio
    //  * (ora, percentuale) in SharedPreferences, creato alla prima lettura
    //  * in scarica e azzerato in carica o se la percentuale risale.
    //  * null finche' il calo non e' almeno MIN_TREND_DROP_PERCENT in almeno
    //  * MIN_TREND_ELAPSED_MS.
    //  */
    // private fun readTrendHours(context: Context, percent: Int?): Double? {
    //     if (percent == null) return null
    //     val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    //     val now = System.currentTimeMillis()
    //     val anchorTime = prefs.getLong(KEY_ANCHOR_TIME, 0L)
    //     val anchorPercent = prefs.getInt(KEY_ANCHOR_PERCENT, -1)
    //     // Nessun ancoraggio, oppure ricaricato senza che lo vedessimo.
    //     if (anchorTime <= 0L || anchorPercent < 0 || percent > anchorPercent || anchorTime > now) {
    //         prefs.edit().putLong(KEY_ANCHOR_TIME, now).putInt(KEY_ANCHOR_PERCENT, percent).apply()
    //         return null
    //     }
    //     val drop = anchorPercent - percent
    //     val elapsedMs = now - anchorTime
    //     if (drop < MIN_TREND_DROP_PERCENT || elapsedMs < MIN_TREND_ELAPSED_MS) return null
    //
    //     val percentPerHour = drop / (elapsedMs / 3_600_000.0)
    //     val hours = percent / percentPerHour
    //     Log.i(TAG, "stima andamento: -$drop% in ${elapsedMs / 60_000} min → %.1f h".format(hours))
    //     return hours.takeIf { it in MIN_PLAUSIBLE_HOURS..MAX_PLAUSIBLE_HOURS }
    // }
    //
    // private fun resetTrend(context: Context) {
    //     context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    // }
}
