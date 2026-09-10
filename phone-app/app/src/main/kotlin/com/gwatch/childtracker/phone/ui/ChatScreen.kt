package com.gwatch.childtracker.phone.ui

// Storico versioni
// v0.1.0 (2026-09-10): prima versione, layout a Column con
//   Modifier.weight(1f) (lista messaggi sopra, riga di input sotto) e un
//   secondo Modifier.weight(1f) nella Row di input per far espandere il
//   campo di testo lasciando spazio fisso al pulsante "Invia".
// v0.2.0 (2026-09-10): stesso errore di compilazione gia' incontrato in
//   MapScreen.kt e nel watch-app (ChatScreen.kt li'): "Cannot access
//   'weight': it is internal in 'androidx.compose.foundation.layout'"
//   con le versioni di Compose fissate in questo progetto — vale per
//   ogni uso di Modifier.weight, non solo quello nella Column. Riscritto
//   senza Modifier.weight in nessun punto:
//   - lista messaggi/stato vuoto ora riempie tutto lo schermo
//     (Modifier.fillMaxSize() in un Box) con la riga di input "ancorata"
//     sopra via Modifier.align(Alignment.BottomCenter) e uno sfondo
//     opaco (stile barra di input delle app di chat); la lista ha un
//     padding inferiore per non far restare l'ultimo messaggio nascosto
//     sotto la barra.
//   - il campo di testo nella riga di input non usa piu' Modifier.weight
//     per espandersi: la larghezza disponibile viene letta con
//     BoxWithConstraints (API stabile, gia' verificata funzionante in
//     questo progetto a differenza di weight) e il campo prende quella
//     larghezza meno lo spazio riservato al pulsante "Invia".
// v0.2.1 (2026-09-10): errore di compilazione "'val maxWidth: Dp' can't
//   be called in this context by implicit receiver" — maxWidth veniva
//   letto dentro alla Row annidata, dove il receiver implicito piu'
//   vicino e' RowScope, non BoxWithConstraintsScope. Spostata la lettura
//   di maxWidth (val fieldWidth = maxWidth - SEND_BUTTON_WIDTH) subito
//   dentro il content di BoxWithConstraints, prima di aprire la Row.
// v0.3.0 (2026-09-10): il pulsante "Invia" ignorava l'esito del backend
//   (onSent(Boolean) -> {}), quindi un fallimento (token scaduto, quota
//   giornaliera, rete) spariva senza nessun feedback: il campo si
//   svuotava comunque e sembrava che "i messaggi non partissero" senza
//   nessun indizio del perche'. Aggiunto un Toast di
//   successo/fallimento, stesso pattern del watch-app.
// v0.4.0 (2026-09-10): richiesta utente — migliorare la visualizzazione
//   della chat stile WhatsApp, replicata su entrambe le app. Bolle gia'
//   colorate/allineate in precedenza (primaryContainer/
//   secondaryContainer del tema Material) sono ora sui colori
//   riconoscibili di WhatsApp (verde per i propri, bianco/grigio scuro
//   per quelli ricevuti, con varianti light/dark — a differenza del
//   watch qui il telefono segue il tema di sistema), piu' il nome del
//   mittente sopra il testo per i messaggi ricevuti (solo "Bambino" per
//   ora: coi campi attuali un messaggio "ricevuto" puo' venire solo dal
//   figlio, ma se in futuro si tracciasse quale genitore ha scritto
//   cosa nel multi-genitore, sarebbe la stessa etichetta a distinguerli).

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gwatch.childtracker.phone.R
import com.gwatch.childtracker.phone.data.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Larghezza riservata al pulsante "Invia" nella riga di input, cosi' il
// campo di testo (vedi BoxWithConstraints sotto) puo' prendersi il resto
// senza bisogno di Modifier.weight.
private val SEND_BUTTON_WIDTH = 88.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val messages by viewModel.messages.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = stringResource(R.string.chat_no_messages),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 76.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(messages) { message -> MessageBubble(message) }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
            ) {
                // maxWidth va letto qui, nello scope diretto di
                // BoxWithConstraints: dentro alla Row sotto, il compilatore
                // Kotlin da' errore ("can't be called in this context by
                // implicit receiver") perche' il receiver piu' vicino li'
                // e' RowScope, non BoxWithConstraintsScope.
                val fieldWidth = maxWidth - SEND_BUTTON_WIDTH
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(R.string.chat_input_hint)) },
                        modifier = Modifier.width(fieldWidth),
                    )
                    TextButton(
                        enabled = draft.isNotBlank(),
                        onClick = {
                            // v0.3.0 (2026-09-10): prima l'esito veniva
                            // ignorato ({} come onSent) — un fallimento
                            // (401/429/rete) spariva senza nessun segnale,
                            // il campo si svuotava comunque e sembrava
                            // "non essere partito niente". Ora mostra un
                            // Toast in entrambi i casi, stesso pattern
                            // gia' usato nel watch-app (ChatScreen.kt li').
                            val text = draft
                            draft = ""
                            viewModel.sendMessage(text) { ok ->
                                val feedbackRes = if (ok) R.string.chat_send_success else R.string.chat_send_failed
                                Toast.makeText(context, context.getString(feedbackRes), Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.width(SEND_BUTTON_WIDTH),
                    ) { Text(stringResource(R.string.send)) }
                }
            }
        }
    }
}

// Colori stile WhatsApp, con varianti light/dark (a differenza del
// watch, sempre a sfondo nero, il telefono segue il tema di sistema —
// vedi isSystemInDarkTheme() sotto). "FromParent" = i propri messaggi
// su questa app (il genitore e' chi la usa).
private val OwnBubbleLight = Color(0xFFDCF8C6)
private val OwnBubbleDark = Color(0xFF005C4B)
private val ReceivedBubbleLight = Color(0xFFFFFFFF)
private val ReceivedBubbleDark = Color(0xFF202C33)
private val SenderNameColor = Color(0xFF128C7E)

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromParent = message.sender == "parent"
    val darkTheme = isSystemInDarkTheme()
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val bubbleColor = if (fromParent) {
        if (darkTheme) OwnBubbleDark else OwnBubbleLight
    } else {
        if (darkTheme) ReceivedBubbleDark else ReceivedBubbleLight
    }
    val textColor = if (darkTheme) Color.White else Color.Black
    val bubbleShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomStart = if (fromParent) 12.dp else 2.dp,
        bottomEnd = if (fromParent) 2.dp else 12.dp,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromParent) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(10.dp),
        ) {
            Column {
                if (!fromParent) {
                    Text(
                        text = stringResource(R.string.chat_sender_child),
                        color = SenderNameColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
                Text(text = message.text, color = textColor, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = formatter.format(Date(message.timestampMillis)),
                    color = textColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
