package com.gwatch.childtracker.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Versione: 0.1.0 (2026-09-23)
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
 */
object WatchStateReporter {
    private const val TAG = "WatchStateReporter"
    private const val PREFS = "watch_state"
    private const val KEY_AIRPLANE_SINCE = "airplane_since"
    private const val KEY_OFF_SINCE = "off_since"
    private const val IMMEDIATE_TIMEOUT_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Ricevitore da registrare a runtime (vedi LocationTrackingService). */
    fun createReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> onAirplaneModeChanged(context, isAirplaneOn(context, intent))
                Intent.ACTION_SHUTDOWN -> onShutdown(context)
            }
        }
    }

    fun intentFilter() = IntentFilter().apply {
        addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        addAction(Intent.ACTION_SHUTDOWN)
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
        val since = prefs.getLong(KEY_OFF_SINCE, 0L).takeIf { it > 0 }
        prefs.edit().remove(KEY_OFF_SINCE).apply()
        enqueueOnNetwork(context, "boot", System.currentTimeMillis(), since)
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
