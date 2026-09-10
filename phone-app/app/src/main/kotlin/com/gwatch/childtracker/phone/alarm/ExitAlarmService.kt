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
 * Allarme sonoro/vibrazione ripetuto quando il figlio esce da una zona
 * con "allarme all'uscita" attivo (toggle per-zona, vedi
 * GeofenceScreen.kt). Avviato da FcmService su un messaggio data-only
 * "exit_alarm" (backend/api/trigger-event.js) — foreground Service
 * invece di una semplice notifica perche' deve suonare in loop finche'
 * il genitore non lo ferma esplicitamente, non un singolo bip.
 *
 * Rete di sicurezza: si ferma comunque da solo dopo SAFETY_CAP_MS anche
 * se il genitore non lo vede (stessa logica del cap sul tracking SOS
 * lato watch, vedi SosLocationService.kt), per non suonare
 * indefinitamente in caso di malfunzionamento.
 */
class ExitAlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val stopHandler = Handler(mainLooper)
    private val stopRunnable = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val zoneName = intent?.getStringExtra(EXTRA_ZONE_NAME) ?: getString(R.string.geofence_unknown_zone)
        startForeground(NOTIFICATION_ID, buildNotification(zoneName))
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
                setDataSource(this@ExitAlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        }
    }

    private fun startVibration() {
        val v = ContextCompat.getSystemService(this, Vibrator::class.java) ?: return
        vibrator = v
        // repeat=0 -> ricomincia dall'indice 0 del pattern, loop continuo.
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
    }

    private fun buildNotification(zoneName: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ExitAlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ExitAlarmActivity::class.java)
                .putExtra(EXTRA_ZONE_NAME, zoneName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, TrackerApplication.ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.exit_alarm_title))
            .setContentText(getString(R.string.exit_alarm_body, zoneName))
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
        private const val NOTIFICATION_ID = 3

        // Suona al massimo 5 minuti anche se nessuno lo ferma: un
        // allarme di uscita zona e' urgente ma non deve poter suonare
        // indefinitamente per un bug o una notifica mancata.
        private const val SAFETY_CAP_MS = 5 * 60_000L

        const val ACTION_STOP = "com.gwatch.childtracker.phone.action.STOP_EXIT_ALARM"
        const val EXTRA_ZONE_NAME = "zoneName"

        fun start(context: Context, zoneName: String) {
            val intent = Intent(context, ExitAlarmService::class.java).putExtra(EXTRA_ZONE_NAME, zoneName)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
