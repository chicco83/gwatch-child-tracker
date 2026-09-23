package com.gwatch.childtracker.phone.messaging

// Storico versioni
// v0.1.0 (2026-09-10): solo "exit_alarm" gestito esplicitamente; il
//   resto mostrato dal payload "notification" quando l'app e' in primo
//   piano (il sistema mostra gia' da solo quello ad app in background,
//   ma SOLO se il messaggio include ancora "notification" — vedi sotto
//   per il problema che questo causava con la chat).
// v0.2.0 (2026-09-10): gestito "exit_alarm" (avvia ExitAlarmService
//   invece di una semplice notifica).
// v0.3.0 (2026-09-10): bug segnalato — i messaggi di chat ricevuti dal
//   watch comparivano SOLO come notifica di sistema, mai nella
//   schermata Chat. Causa: backend/api/send-message.js mandava una
//   push "mista" (notification + data); con l'app in background
//   Android consegna la parte "notification" al tray di sistema e NON
//   invoca onMessageReceived() sul client — quindi qui non girava
//   nessun codice, incluso quello che avrebbe dovuto popolare la chat
//   (che infatti dipendeva solo dal listener Firestore, mai toccato da
//   questo file). send-message.js ora manda solo "data" (stesso
//   pattern gia' in uso per send-message-to-child.js verso il watch):
//   onMessageReceived() gira sempre, anche in background. "chat" e'
//   quindi gestito esplicitamente qui: si costruisce a mano la notifica
//   (come faceva prima il fallback su message.notification) E si
//   aggiunge subito il messaggio a IncomingMessageStore, mostrato in
//   ChatScreen.kt senza aspettare il listener Firestore (stesso ruolo
//   del gia' esistente invio ottimistico per i messaggi in uscita, vedi
//   AppViewModel.kt).
// v0.4.0 (2026-09-10): bug segnalato — toccando la notifica di un
//   messaggio si apriva la Home invece della chat. Non veniva
//   impostato nessun contentIntent sulla notifica. Aggiunto un
//   PendingIntent verso MainActivity con l'extra EXTRA_OPEN_CHAT, letta
//   li' per navigare subito alla schermata Chat (vedi MainActivity.kt).
// v0.5.0 (2026-09-18): richiesto dall'utente — l'SOS deve poter
//   suonare anche a telefono in silenzioso/DND. Gestito "sos_alarm"
//   (push data-only separata mandata da trigger-event.js al primo SOS
//   di un episodio) come gia' fatto per "exit_alarm": avvia
//   SosAlarmService invece di affidarsi alla notifica passiva del
//   fallback sotto (che rispetta il volume suoneria come qualunque
//   altra notifica).
// v0.6.0 (2026-09-18): richiesto dall'utente — la notifica di un
//   messaggio in arrivo diceva solo "Messaggio dal watch", non da
//   QUALE bambino (send-message.js v0.4.0 gia' manda "senderName" nel
//   payload data). Titolo ora "Messaggio da {nickname}"; se un payload
//   piu' vecchio/senza senderName arrivasse comunque, fallback sul
//   vecchio testo generico invece di mostrare "null".
// v0.7.0 (2026-09-23): Fase 2 di qwen_plan.md (individuato da
//   qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — le push non
//   arrivano piu' dal topic globale "parents" ma da un topic
//   per-bambino (vedi Constants.fcmChildTopic/TrackerApplication.kt),
//   quindi onNewToken() non si iscrive piu' a nessun topic globale
//   qui (AppViewModel.kt gestisce le iscrizioni per-bambino quando
//   cambia la lista "children", non ad ogni rotazione del token).
//   Aggiunto anche il "minimo sindacale" del piano: prima di agire su
//   un evento con childId (exit_alarm/sos_alarm/chat), verifica che
//   sia uno dei propri figli (KnownChildrenCache) — seconda barriera
//   nel caso restasse una sottoscrizione residua a un topic non piu'
//   proprio (es. cambio famiglia, nessuna funzione di rimozione oggi,
//   ma meglio non fare affidamento solo sul topic).

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.gwatch.childtracker.phone.R
import com.gwatch.childtracker.phone.TrackerApplication
import com.gwatch.childtracker.phone.alarm.ExitAlarmService
import com.gwatch.childtracker.phone.alarm.SosAlarmService
import com.gwatch.childtracker.phone.data.DeviceRepository
import com.gwatch.childtracker.phone.data.IncomingMessageStore
import com.gwatch.childtracker.phone.data.KnownChildrenCache
import com.gwatch.childtracker.phone.data.model.ChatMessage
import com.gwatch.childtracker.phone.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FcmService : FirebaseMessagingService() {

    private val repository = DeviceRepository()

    override fun onNewToken(token: String) {
        // v0.7.0: vedi Storico versioni sopra — nessuna iscrizione a un
        // topic globale qui, le iscrizioni per-bambino le gestisce
        // AppViewModel quando cambia la lista "children".
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.registerFcmToken(uid, token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val childId = message.data["childId"]
        // v0.7.0: vedi Storico versioni sopra — se il payload porta un
        // childId e non e' tra i propri figli, scarta subito: seconda
        // barriera oltre al topic per-bambino.
        if (childId != null && !KnownChildrenCache.contains(this, childId)) return

        when (message.data["type"]) {
            "exit_alarm" -> {
                val zoneName = message.data["zoneName"] ?: getString(R.string.geofence_unknown_zone)
                ExitAlarmService.start(this, zoneName)
                return
            }
            "sos_alarm" -> {
                val childName = message.data["childName"] ?: getString(R.string.sos_alarm_unknown_child)
                SosAlarmService.start(this, childName)
                return
            }
            "chat" -> {
                handleChatMessage(message.data)
                return
            }
        }

        // Fallback per eventuali push che includono ancora "notification"
        // (es. trigger-event.js per SOS/geofence): serve solo ad app in
        // primo piano, dove il sistema non la mostra da solo.
        val notification = message.notification ?: return
        postNotification(notification.title ?: getString(R.string.app_name), notification.body ?: "")
    }

    private fun handleChatMessage(data: Map<String, String>) {
        val text = data["text"] ?: return
        val sender = data["sender"] ?: "child"
        val senderName = data["senderName"]

        IncomingMessageStore.append(
            ChatMessage(sender = sender, text = text, timestampMillis = System.currentTimeMillis()),
        )
        val title = if (senderName != null) {
            getString(R.string.chat_notification_title_from, senderName)
        } else {
            getString(R.string.chat_notification_title)
        }
        postNotification(title, text, openChat = true)
    }

    private fun postNotification(title: String, text: String, openChat: Boolean = false) {
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
        val builder = NotificationCompat.Builder(this, TrackerApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
