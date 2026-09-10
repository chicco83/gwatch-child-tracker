package com.gwatch.childtracker.messaging

import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.gwatch.childtracker.R
import com.gwatch.childtracker.TrackerApplication
import com.gwatch.childtracker.data.MessageStore
import com.gwatch.childtracker.network.BackendClient
import com.gwatch.childtracker.network.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Riceve i messaggi di chat dal genitore. A differenza di FcmService
 * della phone-app, qui il payload e' sempre "data" (mai "notification",
 * vedi backend/api/send-message-to-child.js): il sistema non mostra
 * nulla da solo, la notifica va costruita qui sempre, anche ad app in
 * background — e' il comportamento che vogliamo, cosi' il messaggio
 * finisce anche in MessageStore per la ChatScreen.
 */
class FcmService : FirebaseMessagingService() {

    private val backendClient = BackendClient()

    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            backendClient.registerFcmToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "chat") return
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
}
