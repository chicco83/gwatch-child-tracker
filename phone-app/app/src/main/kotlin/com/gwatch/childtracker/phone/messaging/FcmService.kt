package com.gwatch.childtracker.phone.messaging

// v0.2.0 (2026-09-10): gestito un nuovo messaggio data-only "exit_alarm"
// (vedi backend/api/trigger-event.js) che avvia ExitAlarmService invece
// di limitarsi a mostrare una notifica — data-only apposta, cosi'
// arriva anche ad app in background/uccisa e non solo quando l'utente
// tocca una notifica di sistema gia' mostrata automaticamente.

import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.gwatch.childtracker.phone.R
import com.gwatch.childtracker.phone.TrackerApplication
import com.gwatch.childtracker.phone.alarm.ExitAlarmService
import com.gwatch.childtracker.phone.data.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FcmService : FirebaseMessagingService() {

    private val repository = DeviceRepository()

    override fun onNewToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.registerFcmToken(uid, token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "exit_alarm") {
            val zoneName = message.data["zoneName"] ?: getString(R.string.geofence_unknown_zone)
            ExitAlarmService.start(this, zoneName)
            return
        }

        // Ad app in background il payload "notification" viene gia'
        // mostrato dal sistema; qui serve solo per l'app in primo piano
        // (FCM non invoca onMessageReceived per la parte di visualizzazione
        // di sistema quando l'app e' in background).
        val notification = message.notification ?: return
        val manager = getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(this, TrackerApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
