package com.gwatch.childtracker.phone.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gwatch.childtracker.phone.R
import com.gwatch.childtracker.phone.TrackerApplication

/**
 * Allarme sonoro/vibrazione quando il bambino preme SOS sul watch —
 * stesso identico pattern di ExitAlarmService.kt (AudioAttributes.
 * USAGE_ALARM per suonare anche a telefono in silenzioso/DND,
 * full-screen intent, foreground Service perche' deve suonare finche'
 * il genitore non lo ferma, non un singolo bip). Avviato da FcmService
 * su un messaggio data-only "sos_alarm" (backend/api/trigger-event.js).
 *
 * A differenza dell'allarme di uscita zona (opt-in per-zona), questo
 * parte sempre al primo SOS di un episodio: e' la funzione di
 * sicurezza piu' critica dell'app, richiesto esplicitamente
 * dall'utente il 2026-09-18 ("puo' bypassare la modalita' silenziosa e
 * far suonare il cellulare?").
 */
class SosAlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val stopHandler = Handler(mainLooper)
    private val stopRunnable = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val childName = intent?.getStringExtra(EXTRA_CHILD_NAME) ?: getString(R.string.sos_alarm_unknown_child)
        startForeground(NOTIFICATION_ID, buildNotification(childName))
        startAlarmSound()
        startVibration()
        stopHandler.postDelayed(stopRunnable, SAFETY_CAP_MS)

        return START_NOT_STICKY
    }

    private fun startAlarmSound() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            runCatching {
                setDataSource(this@SosAlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        }
    }

    private fun startVibration() {
        val v = ContextCompat.getSystemService(this, Vibrator::class.java) ?: return
        vibrator = v
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
    }

    private fun buildNotification(childName: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SosAlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SosAlarmActivity::class.java)
                .putExtra(EXTRA_CHILD_NAME, childName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, TrackerApplication.SOS_ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.sos_alarm_title, childName))
            .setContentText(getString(R.string.sos_alarm_body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .addAction(0, getString(R.string.exit_alarm_stop), stopIntent)
            .build()
    }

    override fun onDestroy() {
        stopHandler.removeCallbacks(stopRunnable)
        player?.let { runCatching { it.stop() }; it.release() }
        vibrator?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 4

        // Piu' lungo del cap dell'allarme di uscita zona (5 min): un SOS
        // e' l'evento piu' critico dell'app, ma resta comunque una rete
        // di sicurezza contro un mancato stop, non un suono indefinito.
        private const val SAFETY_CAP_MS = 10 * 60_000L

        const val ACTION_STOP = "com.gwatch.childtracker.phone.action.STOP_SOS_ALARM"
        const val EXTRA_CHILD_NAME = "childName"

        fun start(context: Context, childName: String) {
            val intent = Intent(context, SosAlarmService::class.java).putExtra(EXTRA_CHILD_NAME, childName)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
