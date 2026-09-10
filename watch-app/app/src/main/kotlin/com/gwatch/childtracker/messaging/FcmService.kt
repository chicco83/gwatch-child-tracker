package com.gwatch.childtracker.messaging

// Storico versioni
// v0.1.0 (2026-09-10): solo "chat" — messaggi testuali dal genitore.
// v0.2.0 (2026-09-10): aggiunto "location_request" — il genitore preme
//   "Aggiorna posizione" sulla phone-app (vedi
//   backend/api/request-location.js), che manda questa push data-only
//   al watch. Riusa LocationRequestWorker, lo stesso worker gia' usato
//   dal pulsante fisico "Invia posizione" su MainActivity: stesso
//   identico invio (fix GPS + trigger-event), solo innescato da remoto
//   invece che in locale, nessuna UI/notifica da mostrare qui (il fix
//   GPS parte silenzioso, il genitore vede il pin muoversi da solo).

import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.gwatch.childtracker.R
import com.gwatch.childtracker.TrackerApplication
import com.gwatch.childtracker.data.MessageStore
import com.gwatch.childtracker.location.LocationRequestWorker
import com.gwatch.childtracker.network.BackendClient
import com.gwatch.childtracker.network.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Riceve dal genitore: messaggi di chat e richieste di posizione
 * immediata. Il payload e' sempre "data" (mai "notification", vedi
 * backend/api/send-message-to-child.js e request-location.js): il
 * sistema non mostra/agisce nulla da solo, va gestito qui sempre,
 * anche ad app in background — necessario sia per mettere il messaggio
 * in MessageStore (ChatScreen) sia per far partire il worker della
 * posizione.
 */
class FcmService : FirebaseMessagingService() {

    private val backendClient = BackendClient()

    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            backendClient.registerFcmToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            "chat" -> handleChatMessage(message.data)
            "location_request" -> handleLocationRequest()
        }
    }

    private fun handleChatMessage(data: Map<String, String>) {
        val text = data["text"] ?: return

        val chatMessage = ChatMessage(
            sender = data["sender"] ?: "parent",
            text = text,
            timestampMillis = System.currentTimeMillis(),
        )
        MessageStore.append(chatMessage)

        val manager = getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(this, TrackerApplication.MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.chat_notification_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun handleLocationRequest() {
        val work = OneTimeWorkRequestBuilder<LocationRequestWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            LocationRequestWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            work,
        )
    }
}
