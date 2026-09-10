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
// v0.5.0 (2026-09-10): due richieste dell'utente su come si comportano
//   le notifiche sul watch:
//   1) dovevano restare visibili finche' non le si rimuove a mano,
//      invece di sparire da sole al tocco. postNotification() ora usa
//      setAutoCancel(false) su tutte (prima "true" su chat/sos_cancel).
//   2) la card "a comparsa" che appare poi svanisce doveva essere piu'
//      grande/centrata invece che un peek in basso. L'unica leva che
//      l'app ha verso il renderer di sistema di Wear OS e'
//      importanza canale (gia' IMPORTANCE_HIGH) + priorita' massima +
//      categoria della notifica: alzata a PRIORITY_MAX (prima HIGH) e
//      aggiunta CATEGORY_MESSAGE per la chat (CATEGORY_STATUS per le
//      altre) — la resa finale a schermo (dimensione/posizione esatta)
//      resta decisa dal sistema, non e' un parametro impostabile
//      pixel per pixel dall'app.
//   Aggiunto anche "location_seen": il genitore ha guardato la
//   posizione inviata dal bambino (SOS o "Invia posizione", vedi
//   backend/api/ack-event.js, chiamato da MapScreen.kt sulla
//   phone-app) — notifica "Il genitore ha visto la tua posizione".

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
 * Riceve dal genitore: messaggi di chat, richieste di posizione
 * immediata, cancellazione SOS e conferma di lettura posizione. Il
 * payload e' sempre "data" (mai "notification", vedi
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
            "location_seen" -> handleLocationSeen()
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

        postNotification(
            title = getString(R.string.chat_notification_title),
            text = text,
            category = NotificationCompat.CATEGORY_MESSAGE,
        )
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
        postNotification(getString(R.string.sos_deactivated_title), getString(R.string.sos_deactivated_text))
    }

    private fun handleLocationSeen() {
        postNotification(getString(R.string.location_seen_title), getString(R.string.location_seen_text))
    }

    /**
     * Notifica visibile/sonora condivisa da chat, sos_cancel e
     * location_seen — vedi storico versioni v0.5.0 sopra per il perche'
     * di PRIORITY_MAX/setAutoCancel(false)/categoria.
     */
    private fun postNotification(
        title: String,
        text: String,
        category: String = NotificationCompat.CATEGORY_STATUS,
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(this, TrackerApplication.MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(category)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(false)
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
