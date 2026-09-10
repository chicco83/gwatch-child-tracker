package com.gwatch.childtracker.ui

// Storico versioni
// v0.2.0 (2026-09-10): prima versione, layout a Column con
//   Modifier.weight(1f) per dare alla lista messaggi lo spazio
//   restante sopra ai pulsanti. In build reale (Android Studio,
//   dependency androidx.wear.compose:compose-foundation:1.3.1 +
//   androidx.compose.ui:ui:1.6.8) dava errore di compilazione:
//   "Cannot access 'weight': it is internal in
//   'androidx.compose.foundation.layout'" — non risolto nemmeno
//   fissando esplicitamente androidx.compose.foundation:foundation
//   alla stessa versione 1.6.8 (vedi build.gradle.kts). Causa esatta
//   non isolabile senza un ambiente di build reale a disposizione.
// v0.2.1 (2026-09-10): rimosso ogni uso di Modifier.weight. Layout
//   riscritto con Box + Modifier.align(...) al posto di Column
//   pesato: stesso risultato visivo (lista messaggi al centro,
//   pulsante indietro in alto, dettatura/risposte rapide in basso),
//   ma senza dipendere da quell'API.
// v0.2.2 (2026-09-10): su device reale i Button (indietro, dettatura,
//   risposte rapide) apparivano come cerchi col testo che tracimava —
//   Button in Wear Compose e' pensato per icone a dimensione fissa,
//   non per etichette di testo. Sostituiti tutti con Chip.
// v0.2.3 (2026-09-10): su device reale i Chip "flottanti" (Box +
//   align, dettatura/risposte rapide ancorati in basso sopra la lista
//   messaggi) si sovrapponevano fra loro, con lo storico che
//   trapelava nelle fessure. Il Box a due livelli e' stato eliminato:
//   ora tutto (indietro, storico, dettatura, risposte rapide) e' in
//   un'unica LazyColumn verticale con spaziatura fissa fra gli
//   elementi — nessun elemento sovrapposto da posizionare a mano,
//   pattern standard per schermi rotondi Wear.
// v0.2.4 (2026-09-10): mancava conferma visiva dell'invio riuscito —
//   c'era solo un Toast in caso di fallimento. Aggiunto Toast anche
//   sul successo ("Messaggio inviato").
// v0.3.0 (2026-09-10): riordino su richiesta utente dopo test su
//   device reale — "Indietro" era il primo elemento in alto, ma e'
//   un'azione secondaria (lo swipe di sistema, gia' gestito da
//   BackHandler in MainActivity.kt, copre gia' il caso comune).
//   Spostato in fondo alle azioni primarie (dettatura/risposte
//   rapide), come CompactChip piu' piccolo e di colore secondario
//   (ChipDefaults.secondaryChipColors) per non competere visivamente
//   con le azioni principali — pattern Wear OS standard per le azioni
//   di navigazione/secondarie. Aggiunta una Divider e un'intestazione
//   ("Messaggi inviati") a separare i pulsanti dallo storico
//   sottostante. Anche qui il testo dei Chip era allineato a
//   sinistra: centrato con CenteredChipLabel (condivisa con
//   MainActivity.kt, stesso package).
// v0.3.1 (2026-09-10): in build reale androidx.wear.compose.material.
//   Divider risultava "Unresolved reference" con la versione di
//   compose-material di questo progetto (1.3.1) — sostituito con
//   HorizontalSeparator(), una riga disegnata a mano (Box sottile).
// v0.4.0 (2026-09-10): bug segnalato dall'utente dopo test reale — un
//   messaggio in arrivo compariva in fondo alla LazyColumn (dopo i
//   pulsanti azione e l'intestazione storico) senza alcuno scroll
//   automatico: se non si entrava in "Messaggi" e non si scorreva a
//   mano fino in fondo, il nuovo messaggio restava invisibile.
//   Aggiunto rememberLazyListState() + scroll automatico all'ultimo
//   messaggio quando la lista cambia, stesso pattern gia' in uso nella
//   ChatScreen.kt della phone-app.
// v0.5.0 (2026-09-10): richiesta utente — migliorare la visualizzazione
//   della chat stile WhatsApp. Prima ogni messaggio era una singola
//   riga di testo piatta ("Tu 22:51: ciao"), senza alcuna distinzione
//   visiva fra mittente e destinatario. Sostituita con MessageBubble:
//   bolla colorata (verde per i messaggi propri, allineata a destra —
//   grigio scuro per quelli ricevuti, allineata a sinistra, stessi
//   colori del tema scuro di WhatsApp dato che Wear OS e' sempre a
//   sfondo nero), nome del mittente sopra il testo solo per i messaggi
//   ricevuti (sul watch arrivano sempre e solo dal genitore, ma
//   comunque piu' leggibile di "Genitore 22:51: testo" tutto su una
//   riga).

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Text
import com.gwatch.childtracker.R
import com.gwatch.childtracker.data.MessageStore
import com.gwatch.childtracker.network.BackendClient
import com.gwatch.childtracker.network.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Chat MVP: niente tastiera (impraticabile su un display cosi'
 * piccolo per un bambino) — solo risposte rapide preimpostate e
 * dettatura vocale (RecognizerIntent, gestito dall'app di sistema:
 * nessun permesso RECORD_AUDIO da dichiarare qui). Lo storico arriva
 * dal backend all'apertura (BackendClient.fetchMessages) e si
 * aggiorna in tempo reale via push (MessageStore, alimentato da
 * FcmService). Tutto in un'unica lista scorrevole (vedi v0.2.3 sopra).
 */
@Composable
fun ChatScreen(backendClient: BackendClient, onBack: () -> Unit) {
    val messages by MessageStore.messages.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        val history = backendClient.fetchMessages()
        if (history.isNotEmpty()) MessageStore.replaceAll(history)
    }

    // Scorre automaticamente all'ultimo messaggio quando la lista
    // cambia (apertura schermo con storico gia' presente, o nuovo
    // messaggio in arrivo via push) — FIXED_HEADER_ITEMS e' il numero
    // di elementi fissi sopra lo storico nella LazyColumn sotto
    // (dettatura, 3 risposte rapide, indietro, separatore, intestazione).
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(FIXED_HEADER_ITEMS + messages.size - 1)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                sendChatMessage(spoken, backendClient, scope, context)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp, start = 10.dp, end = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Azioni primarie in cima: dettatura e risposte rapide, quelle
        // che si usano davvero per rispondere.
        item {
            Chip(onClick = {
                voiceLauncher.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                    },
                )
            }, modifier = Modifier.fillMaxWidth(), label = { CenteredChipLabel(stringResourceCompat(R.string.chat_voice_reply)) })
        }

        item { QuickReplyButton(R.string.chat_quick_reply_ok, backendClient, scope, context) }
        item { QuickReplyButton(R.string.chat_quick_reply_coming, backendClient, scope, context) }
        item { QuickReplyButton(R.string.chat_quick_reply_call_me, backendClient, scope, context) }

        // Indietro e' secondaria: piu' piccola, colore diverso, in
        // fondo alle azioni primarie (lo swipe di sistema resta la via
        // principale per tornare al menu, vedi BackHandler).
        item {
            CompactChip(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.secondaryChipColors(),
                label = { CenteredChipLabel(stringResourceCompat(R.string.back)) },
            )
        }

        item { HorizontalSeparator() }

        item { Text(stringResourceCompat(R.string.chat_history_header)) }
        if (messages.isEmpty()) {
            item { Text(stringResourceCompat(R.string.chat_no_messages)) }
        } else {
            items(messages) { message -> MessageBubble(message) }
        }
    }
}

@Composable
private fun QuickReplyButton(
    textRes: Int,
    backendClient: BackendClient,
    scope: CoroutineScope,
    context: Context,
) {
    val label = stringResourceCompat(textRes)
    Chip(
        onClick = { sendChatMessage(label, backendClient, scope, context) },
        modifier = Modifier.fillMaxWidth(),
        label = { CenteredChipLabel(label) },
    )
}

/**
 * v0.3.1 (2026-09-10): androidx.wear.compose.material.Divider non
 * risolveva in build reale ("Unresolved reference: Divider") con la
 * versione di compose-material fissata in build.gradle.kts (1.3.1) —
 * a differenza di Chip/CompactChip non fa parte dell'API di quella
 * release. Sostituito con una riga disegnata a mano (Box sottile),
 * senza dipendere da quel componente.
 */
@Composable
private fun HorizontalSeparator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.2f)),
    )
}

// Colori stile WhatsApp tema scuro (Wear OS e' sempre a sfondo nero,
// non ha senso qui un tema chiaro come sulla phone-app). "Own" = i
// propri messaggi (sender "child" su questo watch), "Received" =
// quelli del genitore.
private val OwnBubbleColor = Color(0xFF005C4B)
private val ReceivedBubbleColor = Color(0xFF202C33)
private val SenderNameColor = Color(0xFF06CF9C)

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isOwn = message.sender == "child"
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val bubbleShape = RoundedCornerShape(
        topStart = 10.dp,
        topEnd = 10.dp,
        bottomStart = if (isOwn) 10.dp else 2.dp,
        bottomEnd = if (isOwn) 2.dp else 10.dp,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 160.dp)
                .clip(bubbleShape)
                .background(if (isOwn) OwnBubbleColor else ReceivedBubbleColor)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Column {
                if (!isOwn) {
                    Text(
                        text = stringResourceCompat(R.string.chat_sender_parent),
                        color = SenderNameColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                }
                Text(text = message.text, color = Color.White)
                Text(
                    text = formatter.format(Date(message.timestampMillis)),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

private fun sendChatMessage(
    text: String,
    backendClient: BackendClient,
    scope: CoroutineScope,
    context: Context,
) {
    // Ottimista: il messaggio appare subito in lista, senza aspettare
    // la risposta del backend (il watch e' su LTE, la latenza puo'
    // essere percepibile).
    val message = ChatMessage(sender = "child", text = text, timestampMillis = System.currentTimeMillis())
    MessageStore.append(message)
    scope.launch {
        val ok = backendClient.sendMessage(text, message.timestampMillis)
        val feedbackRes = if (ok) R.string.chat_send_success else R.string.chat_send_failed
        Toast.makeText(context, context.getString(feedbackRes), Toast.LENGTH_SHORT).show()
    }
}

// Elementi fissi sopra lo storico nella LazyColumn di ChatScreen:
// dettatura, 3 risposte rapide, indietro, separatore, intestazione.
private const val FIXED_HEADER_ITEMS = 7

// stringResourceCompat e CenteredChipLabel sono condivise da
// MainActivity.kt (stesso package com.gwatch.childtracker.ui).
