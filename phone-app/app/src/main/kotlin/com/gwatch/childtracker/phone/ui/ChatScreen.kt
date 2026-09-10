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

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(R.string.chat_input_hint)) },
                        modifier = Modifier.width(maxWidth - SEND_BUTTON_WIDTH),
                    )
                    TextButton(
                        enabled = draft.isNotBlank(),
                        onClick = {
                            val text = draft
                            draft = ""
                            viewModel.sendMessage(text) {}
                        },
                        modifier = Modifier.width(SEND_BUTTON_WIDTH),
                    ) { Text(stringResource(R.string.send)) }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromParent = message.sender == "parent"
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromParent) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (fromParent) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = formatter.format(Date(message.timestampMillis)),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
