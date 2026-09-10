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
// v0.3.0 (2026-09-10): aggiunto "sos_cancel" — il genitore disattiva
//   l'SOS dalla phone-app (vedi backend/api/cancel-sos.js), che manda
//   questa push per fermare subito SosLocationService (i ping ogni 30",
//   vedi location/SosLocationService.kt) invece di aspettare che il
//   watch se ne accorga da solo al prossimo ping rifiutato con 409.
// v0.4.0 (2026-09-10): "sos_cancel" ora mostra anche una notifica
//   "SOS disattivato" — prima fermava il service senza alcun riscontro
//   visibile sul watch (il banner "SOS ATTIVO" su MainActivity sparisce
//   gia' da solo, ma una notifica esplicita e' piu' difficile da perdere
//   se il bambino non ha la app in primo piano). "location_request" ora
//   passa source=SOURCE_PARENT a LocationRequestWorker, per distinguere
//   lato backend una richiesta remota del genitore da un invio manuale
//   del bambino (stesso identico worker, vedi LocationRequestWorker.kt) —
//   prima la notifica al genitore diceva sempre "il bambino ha inviato
//   la posizione", anche quando l'aveva chiesta lui stesso.

import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.gwatch.childtracker.R
import com.gwatch.childtracker.TrackerApplication
import com.gwatch.childtracker.data.MessageStore
import com.gwatch.childtracker.location.LocationRequestWorker
import com.gwatch.childtracker.location.SosLocationService
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
            "sos_cancel" -> handleSosCancel()
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
        val work = OneTimeWorkRequestBuilder<LocationRequestWorker>()
            .setInputData(workDataOf(LocationRequestWorker.KEY_SOURCE to LocationRequestWorker.SOURCE_PARENT))
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            LocationRequestWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            work,
        )
    }

    private fun handleSosCancel() {
        stopService(Intent(this, SosLocationService::class.java))

        val manager = getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(this, TrackerApplication.MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.sos_deactivated_title))
            .setContentText(getString(R.string.sos_deactivated_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
