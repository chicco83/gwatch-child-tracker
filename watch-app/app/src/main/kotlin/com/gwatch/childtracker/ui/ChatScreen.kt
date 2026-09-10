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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Divider
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

    LaunchedEffect(Unit) {
        val history = backendClient.fetchMessages()
        if (history.isNotEmpty()) MessageStore.replaceAll(history)
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

        item { Divider(modifier = Modifier.padding(vertical = 4.dp)) }

        item { Text(stringResourceCompat(R.string.chat_history_header)) }
        if (messages.isEmpty()) {
            item { Text(stringResourceCompat(R.string.chat_no_messages)) }
        } else {
            items(messages) { message -> MessageRow(message) }
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

@Composable
private fun MessageRow(message: ChatMessage) {
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val prefix = if (message.sender == "child") "Tu" else "Genitore"
    Text(text = "$prefix ${formatter.format(Date(message.timestampMillis))}: ${message.text}")
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

// stringResourceCompat e CenteredChipLabel sono condivise da
// MainActivity.kt (stesso package com.gwatch.childtracker.ui).
