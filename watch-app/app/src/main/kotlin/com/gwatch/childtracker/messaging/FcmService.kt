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
// v0.5.1 (2026-09-10): la notifica continuava a comparire SOLO nel
//   pannello, senza vibrazione/popup, anche dopo aver dato al canale
//   (TrackerApplication.kt) un ID nuovo con enableVibration(true) —
//   quindi non era (solo) il problema del canale immutabile.
//   Aggiunta vibrazione esplicita anche sulla singola notifica
//   (setVibrate/setDefaults, normalmente ignorati a favore del canale
//   da Android 8+, ma alcuni skin OEM — incluso Wear OS di Samsung sul
//   Galaxy Watch4 — non rispettano sempre in modo affidabile solo le
//   impostazioni di canale). Se anche questo non basta, il problema e'
//   quasi certamente un'impostazione di sistema sul watch stesso, non
//   piu' risolvibile da codice: Non disturbare/Modalita' teatro
//   attivi, vibrazione disattivata globalmente (Impostazioni > Suoni e
//   vibrazione), o il toggle "Vibra" specifico del canale "Messaggi"
//   disattivato a mano in Impostazioni > App > Family Tracker >
//   Notifiche.
// v0.6.3 (2026-09-10): bug segnalato — la notifica non era cliccabile,
//   il tocco non portava da nessuna parte (mancava un contentIntent).
//   Aggiunto un PendingIntent verso MainActivity, con l'extra
//   EXTRA_OPEN_CHAT per i messaggi di chat (le altre notifiche aprono
//   semplicemente la schermata principale).
// v0.6.4 (2026-09-10): confermato via screenshot — tap ok ("Apri app"),
//   ma l'icona resta quella generica del fumetto (ic_notification, per
//   design solo una maschera monocromatica: e' cosi' che Android/Wear
//   OS vogliono la small icon) e nessun nome app e' visibile da
//   nessuna parte nella card. Aggiunto setLargeIcon() con l'icona reale
//   dell'app (mipmap/ic_launcher), che Wear OS mostra tipicamente
//   accanto/al posto della small icon per rendere piu' riconoscibile
//   la provenienza. ATTENZIONE: non esiste nessuna API per forzare un
//   testo "nome app" esplicito nella card — quello che Wear OS mostra
//   (se lo mostra) e' sempre e solo l'etichetta dal manifest
//   (android:label, gia' "Family Tracker"), resa a schermo in modo
//   completamente deciso dal sistema: se anche con l'icona reale non
//   compare alcun nome, e' un limite della skin Wear OS di questo
//   dispositivo, non piu' risolvibile lato app.

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
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
import com.gwatch.childtracker.ui.MainActivity
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
            openChat = true,
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
        openChat: Boolean = false,
    ) {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (openChat) putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val manager = getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(this, TrackerApplication.MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(ContextCompat.getDrawable(this, R.mipmap.ic_launcher)?.toBitmap())
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(category)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            // v0.5.1: ridondante rispetto alle impostazioni del canale
            // (che dovrebbero da sole bastare da Android 8+), ma alcuni
            // OEM non le rispettano sempre in modo affidabile — vedi
            // storico versioni sopra.
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
