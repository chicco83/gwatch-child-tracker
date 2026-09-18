package com.gwatch.childtracker.dnd

// v0.9.0 (2026-09-18): nuovo file — DND automatico per zona, richiesto
// dall'utente ("quando arriva a scuola va in dnd in automatico"). Il
// cambio del "Non disturbare" di sistema richiede il permesso speciale
// ACCESS_NOTIFICATION_POLICY, che Android NON permette di concedere via
// codice: va autorizzato una tantum toccando fisicamente il watch
// (Impostazioni > Accesso speciale > Non disturbare). Se manca, non ha
// senso fallire silenziosamente (il genitore configurerebbe una zona
// "a scuola va in DND" che non fa mai nulla, senza sapere perche'):
// mostriamo una notifica esplicita con un tasto diretto a quella
// schermata di sistema, riusando lo stesso canale "messages_v2" gia'
// usato da FcmService.kt per le notifiche visibili/sonore.

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.gwatch.childtracker.R
import com.gwatch.childtracker.TrackerApplication

object DndController {

    /**
     * Attiva/disattiva il "Non disturbare" di sistema. INTERRUPTION_FILTER_PRIORITY
     * e' lo stesso filtro usato dal "Non disturbare" standard di Android
     * (silenzia le notifiche normali, non solo quelle di quest'app) —
     * scelta deliberata: l'obiettivo e' che il bambino non venga
     * distratto a scuola da NESSUNA notifica, non solo da quelle
     * dell'app. Se il permesso non e' stato concesso, non puo' fare
     * nulla: mostra una notifica che spiega perche' e porta dritti alla
     * schermata di sistema per concederlo, invece di fallire in
     * silenzio (il genitore vedrebbe una zona "DND" configurata che
     * pero' non fa mai niente).
     */
    fun setDnd(context: Context, enabled: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted) {
            postPermissionNeededNotification(context)
            return
        }
        manager.setInterruptionFilter(
            if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
    }

    private fun postPermissionNeededNotification(context: Context) {
        val settingsIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(context, TrackerApplication.MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap())
            .setContentTitle(context.getString(R.string.dnd_permission_needed_title))
            .setContentText(context.getString(R.string.dnd_permission_needed_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private const val NOTIFICATION_ID = 5
}
