package com.gwatch.childtracker.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gwatch.childtracker.network.BackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Versione: 0.3.0 (2026-09-24)
 *
 * Richiesta utente: avvisare il telefono quando il watch va in modalita'
 * aereo o si spegne (icona corrispondente sulla phone-app).
 *
 * Limite fisico: entrando in modalita' aereo il watch perde la rete quasi
 * subito, e lo spegnimento concede pochi secondi. Per questo:
 * 1) "airplane_on"/"shutdown": tentativo di invio immediato (best effort,
 *    breve timeout), senza coda: se arrivasse in ritardo dopo il rientro
 *    mostrerebbe uno stato sbagliato;
 * 2) l'inizio del periodo offline viene salvato in SharedPreferences;
 * 3) al rientro ("airplane_off" alla disattivazione, "boot" alla
 *    riaccensione) un WatchStateWorker con vincolo di rete invia l'evento
 *    con "since" = inizio del periodo offline, cosi' il telefono ricostruisce
 *    comunque "in modalita' aereo dalle X alle Y" anche se il primo avviso
 *    non e' partito.
 * Il ricevitore e' registrato da LocationTrackingService (i broadcast di
 * modalita' aereo non arrivano a ricevitori dichiarati solo nel manifest).
 *
 * v0.2.0 (2026-09-24): richiesta utente, icona "in carica" sulla
 * phone-app. Lo stesso ricevitore ascolta ACTION_POWER_CONNECTED /
 * ACTION_POWER_DISCONNECTED (anche questi non arrivano ai ricevitori
 * del solo manifest da Android 8) e invia subito uno "status" con
 * reason "power" (trigger-event.js v0.24.0), cosi' il telefono vede
 * il cambio in pochi secondi invece di aspettare il prossimo punto di
 * tracking (fino a 10'). Worker con vincolo di rete e REPLACE: conta
 * solo l'ultimo stato del caricatore.
 *
 * v0.3.0 (2026-09-24): bug trovato nei logcat/diagnostica dell'utente —
 * "watch riacceso" inviato a OGNI avvio da Android Studio (00:27, 00:37,
 * 00:45, 01:01 del 24/9), mentre il watch si era riavviato davvero solo
 * alle 00:21. Da Android 15 (il watch e' su Android 16) un'app uscita
 * dallo stato "arresto forzato" riceve di nuovo BOOT_COMPLETED alla
 * riapertura, e Android Studio fa un arresto forzato a ogni Run. Ora
 * onBoot() invia "boot" solo se il numero di avvii del sistema
 * (Settings.Global.BOOT_COUNT) e' cambiato dall'ultimo avviso; se il
 * contatore non e' leggibile, solo se il sistema e' acceso da meno di
 * 10 minuti.
 */
object WatchStateReporter {
    private const val TAG = "WatchStateReporter"
    private const val PREFS = "watch_state"
    private const val KEY_AIRPLANE_SINCE = "airplane_since"
    private const val KEY_OFF_SINCE = "off_since"
    // v0.3.0: ultimo BOOT_COUNT gia' segnalato (vedi isRealBoot).
    private const val KEY_LAST_BOOT_COUNT = "last_boot_count"
    private const val FRESH_BOOT_MAX_UPTIME_MS = 10 * 60 * 1000L
    private const val IMMEDIATE_TIMEOUT_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Ricevitore da registrare a runtime (vedi LocationTrackingService). */
    fun createReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> onAirplaneModeChanged(context, isAirplaneOn(context, intent))
                Intent.ACTION_SHUTDOWN -> onShutdown(context)
                // v0.2.0: caricatore collegato/scollegato.
                Intent.ACTION_POWER_CONNECTED -> onPowerChanged(context, true)
                Intent.ACTION_POWER_DISCONNECTED -> onPowerChanged(context, false)
            }
        }
    }

    fun intentFilter() = IntentFilter().apply {
        addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        addAction(Intent.ACTION_SHUTDOWN)
        // v0.2.0: vedi onPowerChanged.
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
    }

    fun register(context: Context, receiver: BroadcastReceiver) {
        runCatching {
            ContextCompat.registerReceiver(context, receiver, intentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w(TAG, "registrazione ricevitore fallita", it) }
    }

    private fun isAirplaneOn(context: Context, intent: Intent): Boolean =
        if (intent.hasExtra("state")) {
            intent.getBooleanExtra("state", false)
        } else {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        }

    fun onAirplaneModeChanged(context: Context, on: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        Log.i(TAG, "modalita' aereo: ${if (on) "ATTIVATA" else "disattivata"}")
        if (on) {
            prefs.edit().putLong(KEY_AIRPLANE_SINCE, now).apply()
            sendImmediate(context, "airplane_on", now)
        } else {
            val since = prefs.getLong(KEY_AIRPLANE_SINCE, 0L).takeIf { it > 0 }
            prefs.edit().remove(KEY_AIRPLANE_SINCE).apply()
            enqueueOnNetwork(context, "airplane_off", now, since)
        }
    }

    fun onShutdown(context: Context) {
        val now = System.currentTimeMillis()
        Log.i(TAG, "spegnimento del watch")
        // commit() e non apply(): il processo sta per terminare.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_OFF_SINCE, now).commit()
        sendImmediate(context, "shutdown", now)
    }

    /** Da BootReceiver: invia "boot" con l'ora dello spegnimento, se nota. */
    fun onBoot(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // v0.3.0: BOOT_COMPLETED arriva anche dopo un arresto forzato
        // (Android 15+), non solo a una vera accensione: vedi Storico sopra.
        // Precedente (2026-09-23): nessun controllo, "boot" sempre inviato.
        if (!isRealBoot(context, prefs)) {
            Log.i(TAG, "BOOT_COMPLETED senza riavvio del watch (arresto forzato dell'app): nessun avviso")
            return
        }
        val since = prefs.getLong(KEY_OFF_SINCE, 0L).takeIf { it > 0 }
        prefs.edit().remove(KEY_OFF_SINCE).apply()
        enqueueOnNetwork(context, "boot", System.currentTimeMillis(), since)
    }

    /**
     * v0.2.0 (2026-09-24): caricatore collegato/scollegato. Lo stato di
     * carica viene passato esplicitamente al worker: l'intento sticky
     * della batteria letto da BatteryInfo puo' non essere ancora
     * aggiornato nell'istante del broadcast.
     */
    fun onPowerChanged(context: Context, charging: Boolean) {
        Log.i(TAG, "caricatore ${if (charging) "collegato" else "scollegato"}")
        val work = OneTimeWorkRequestBuilder<PowerStateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(PowerStateWorker.KEY_CHARGING to charging))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PowerStateWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            work,
        )
    }

    /**
     * v0.3.0: true se il watch si e' davvero riavviato dall'ultimo avviso.
     * BOOT_COUNT cresce di 1 a ogni accensione del sistema; lo si salva
     * dopo averlo usato, cosi' un secondo BOOT_COMPLETED con lo stesso
     * valore (app riaperta dopo un arresto forzato) viene ignorato.
     */
    private fun isRealBoot(context: Context, prefs: android.content.SharedPreferences): Boolean {
        val bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
        if (bootCount == null) {
            return SystemClock.elapsedRealtime() < FRESH_BOOT_MAX_UPTIME_MS
        }
        val lastReported = prefs.getInt(KEY_LAST_BOOT_COUNT, -1)
        prefs.edit().putInt(KEY_LAST_BOOT_COUNT, bootCount).apply()
        // Primo avvio dopo l'installazione di questa versione: nessun valore
        // salvato, si decide dal tempo di accensione.
        if (lastReported == -1) return SystemClock.elapsedRealtime() < FRESH_BOOT_MAX_UPTIME_MS
        return bootCount != lastReported
    }

    private fun sendImmediate(context: Context, watchState: String, stateAt: Long) {
        val appContext = context.applicationContext
        scope.launch {
            val ok = withTimeoutOrNull(IMMEDIATE_TIMEOUT_MS) { send(appContext, watchState, stateAt, null) } ?: false
            Log.i(TAG, "invio immediato $watchState: ${if (ok) "riuscito" else "non riuscito"}")
        }
    }

    private fun enqueueOnNetwork(context: Context, watchState: String, stateAt: Long, since: Long?) {
        val work = OneTimeWorkRequestBuilder<WatchStateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    WatchStateWorker.KEY_STATE to watchState,
                    WatchStateWorker.KEY_AT to stateAt,
                    WatchStateWorker.KEY_SINCE to (since ?: 0L),
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WatchStateWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            work,
        )
    }

    internal suspend fun send(context: Context, watchState: String, stateAt: Long, since: Long?): Boolean {
        val battery = BatteryInfo.read(context)
        return BackendClient().sendStatus(
            battery = battery.percent,
            batteryTemp = battery.temperatureC,
            charging = battery.isCharging,
            batteryHoursRemaining = battery.hoursRemaining,
            watchState = watchState,
            stateAt = stateAt,
            since = since,
        )
    }
}

/** Invio di "airplane_off"/"boot" appena c'e' rete, con ritentativi. */
class WatchStateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val state = inputData.getString(KEY_STATE) ?: return Result.failure()
        val at = inputData.getLong(KEY_AT, System.currentTimeMillis())
        val since = inputData.getLong(KEY_SINCE, 0L).takeIf { it > 0 }
        val ok = WatchStateReporter.send(applicationContext, state, at, since)
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        const val WORK_NAME = "watch-state"
        const val KEY_STATE = "state"
        const val KEY_AT = "at"
        const val KEY_SINCE = "since"
    }
}

/**
 * v0.2.0 (2026-09-24): invio dello stato del caricatore (reason "power",
 * trigger-event.js v0.24.0) appena c'e' rete, con ritentativi.
 */
class PowerStateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val charging = inputData.getBoolean(KEY_CHARGING, false)
        val battery = BatteryInfo.read(applicationContext)
        val ok = BackendClient().sendStatus(
            battery = battery.percent,
            batteryTemp = battery.temperatureC,
            charging = charging,
            // In carica l'autonomia residua non ha senso (vedi BatteryInfo).
            batteryHoursRemaining = if (charging) null else battery.hoursRemaining,
            reason = "power",
        )
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        const val WORK_NAME = "power-state"
        const val KEY_CHARGING = "charging"
    }
}
