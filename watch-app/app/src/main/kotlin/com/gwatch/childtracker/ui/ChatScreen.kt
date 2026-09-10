package com.gwatch.childtracker.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
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
import androidx.wear.compose.material.Button
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
 * FcmService).
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

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Button(onClick = onBack) { Text(stringResourceCompat(R.string.back)) }

        if (messages.isEmpty()) {
            Text(
                text = stringResourceCompat(R.string.chat_no_messages),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(messages) { message -> MessageRow(message) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = {
                voiceLauncher.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                    },
                )
            }) { Text(stringResourceCompat(R.string.chat_voice_reply)) }

            QuickReplyButton(R.string.chat_quick_reply_ok, backendClient, scope, context)
            QuickReplyButton(R.string.chat_quick_reply_coming, backendClient, scope, context)
            QuickReplyButton(R.string.chat_quick_reply_call_me, backendClient, scope, context)
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
    Button(onClick = { sendChatMessage(label, backendClient, scope, context) }) {
        Text(label)
    }
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
        if (!ok) {
            Toast.makeText(context, context.getString(R.string.chat_send_failed), Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun stringResourceCompat(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
