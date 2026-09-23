package com.gwatch.childtracker.phone.ui

// Storico versioni
// v0.1.0 (2026-09-11): prima versione — fase 3/4 (vedi CONTEXT.md,
//   piano approvato in plan mode): nickname proprio (parents/{uid}.
//   nickname, scritto diretto su Firestore), nickname dei bambini
//   (passa dal backend, set_nickname), "Aggiungi bambino" (create_child
//   — mostra il token generato una volta sola, con invito a copiarlo
//   subito nel local.properties del nuovo build watch: dopo non sara'
//   piu' recuperabile, solo il suo hash resta salvato). Raggiungibile
//   dal menu hamburger di MapScreen.kt, accanto a "Logout".
//   NB: niente Modifier.weight() nei Row (vedi MapScreen.kt/
//   ChatScreen.kt: e' internal nelle versioni di Compose fissate in
//   questo progetto, causa errore di compilazione) — le righe con
//   campo+pulsante usano una Column con il pulsante allineato a destra
//   sotto il campo, non un Row pesato.
// v0.2.0 (2026-09-22): individuato da qwen3.8-27B-UD-IQ4_XS,
//   implementato da Sonnet 5 — isolamento famiglie (vedi
//   backend/firestore.rules v0.7.0/parent-command.js v0.4.0). Nuova
//   sezione "Genitori": genera un codice d'invito per un secondo
//   genitore (stesso pattern di dialogo "copia e chiudi" gia' usato per
//   il token di un nuovo bambino sopra) e un campo per incollare un
//   codice ricevuto e unirsi alla stessa famiglia.

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.gwatch.childtracker.phone.R
import com.gwatch.childtracker.phone.data.FamilyInviteResult
import com.gwatch.childtracker.phone.data.NewChildResult
import com.gwatch.childtracker.phone.data.model.ChildInfo

/**
 * Impostazioni: nickname proprio, nickname dei bambini registrati,
 * "Aggiungi bambino". Nessun endpoint dedicato per la lista bambini:
 * riusa la stessa query live (AppViewModel.children) gia' usata da
 * GeofenceScreen.kt per il selettore di assegnazione zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val ownNickname by viewModel.ownNickname.collectAsState()
    val children by viewModel.children.collectAsState()
    // 2026-09-24: stato corrente dell'interruttore "alta precisione".
    val deviceStates by viewModel.deviceStates.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var newChildResult by remember { mutableStateOf<NewChildResult?>(null) }
    var familyInviteResult by remember { mutableStateOf<FamilyInviteResult?>(null) }

    familyInviteResult?.let { result ->
        AlertDialog(
            // Stesso motivo del dialogo del token bambino sotto: il
            // codice non sara' piu' recuperabile dopo, niente dismiss a
            // caso (tocco fuori/back).
            onDismissRequest = {},
            title = { Text(stringResource(R.string.settings_family_invite_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_family_invite_message))
                    Text(
                        text = result.inviteCode,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(result.inviteCode))
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_family_invite_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text(stringResource(R.string.settings_family_invite_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { familyInviteResult = null }) {
                    Text(stringResource(R.string.settings_family_invite_close))
                }
            },
        )
    }

    newChildResult?.let { result ->
        AlertDialog(
            // Nessuna dismiss "a caso" (tocco fuori/back): il token non
            // sara' piu' recuperabile dopo, deve essere chiuso solo col
            // pulsante esplicito qui sotto.
            onDismissRequest = {},
            title = { Text(stringResource(R.string.settings_add_child_token_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_add_child_token_message))
                    Text(
                        text = result.deviceToken,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(result.deviceToken))
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_add_child_token_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text(stringResource(R.string.settings_add_child_token_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { newChildResult = null }) {
                    Text(stringResource(R.string.settings_add_child_token_close))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                OwnNicknameSection(
                    nickname = ownNickname,
                    onSave = { nickname ->
                        viewModel.updateOwnNickname(nickname) { ok ->
                            val res = if (ok) R.string.settings_own_nickname_saved else R.string.settings_own_nickname_failed
                            Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                Divider()
                Text(
                    text = stringResource(R.string.settings_children_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
            items(children) { child ->
              // 2026-09-24: Column esplicita per nickname + interruttore.
              Column {
                ChildNicknameRow(
                    child = child,
                    onSave = { nickname ->
                        viewModel.setChildNickname(child.id, nickname) { ok ->
                            val res = if (ok) R.string.settings_child_nickname_saved else R.string.settings_child_nickname_failed
                            Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                // 2026-09-24: richiesta utente — alta precisione anche da fermo.
                TrackingModeRow(
                    highAccuracy = deviceStates[child.id]?.trackingHighAccuracy ?: false,
                    onChange = { value ->
                        viewModel.setTrackingHighAccuracy(child.id, value) { ok ->
                            val res = if (ok) R.string.settings_tracking_saved else R.string.settings_tracking_failed
                            Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
                        }
                    },
                )
              }
            }
            item {
                Divider()
                AddChildSection(
                    onAdd = { nickname ->
                        viewModel.createChild(nickname) { result ->
                            if (result != null) {
                                newChildResult = result
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_add_child_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }
            item {
                Divider()
                FamilySection(
                    onInvite = {
                        viewModel.createFamilyInvite { result ->
                            if (result != null) {
                                familyInviteResult = result
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_family_invite_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                    onAccept = { code ->
                        viewModel.acceptFamilyInvite(code) { ok ->
                            val res = if (ok) R.string.settings_family_accept_succeeded else R.string.settings_family_accept_failed
                            Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun OwnNicknameSection(nickname: String?, onSave: (String) -> Unit) {
    var text by remember(nickname) { mutableStateOf(nickname ?: "") }
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.settings_own_nickname_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { if (text.isNotBlank()) onSave(text.trim()) }) {
                    Text(stringResource(R.string.settings_own_nickname_save))
                }
            }
        }
    }
}

@Composable
private fun ChildNicknameRow(child: ChildInfo, onSave: (String) -> Unit) {
    var text by remember(child.id, child.name) { mutableStateOf(child.name) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { if (text.isNotBlank()) onSave(text.trim()) }) {
                    Text(stringResource(R.string.settings_own_nickname_save))
                }
            }
        }
    }
}

@Composable
private fun FamilySection(onInvite: () -> Unit, onAccept: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = stringResource(R.string.settings_family_section), style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onInvite) { Text(stringResource(R.string.settings_family_invite_button)) }
            }
            Text(
                text = stringResource(R.string.settings_family_accept_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text(stringResource(R.string.settings_family_accept_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    if (code.isNotBlank()) {
                        onAccept(code.trim())
                        code = ""
                    }
                }) { Text(stringResource(R.string.settings_family_accept_button)) }
            }
        }
    }
}

@Composable
private fun AddChildSection(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = stringResource(R.string.settings_add_child_section), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.settings_add_child_nickname_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    if (text.isNotBlank()) {
                        onAdd(text.trim())
                        text = ""
                    }
                }) { Text(stringResource(R.string.settings_add_child_button)) }
            }
        }
    }
}

/**
 * 2026-09-24: interruttore "Alta precisione da fermo" per bambino. Il
 * valore mostrato e' quello salvato sul device (Firestore), con un valore
 * locale ottimistico finche' la conferma non arriva. Etichetta corta sulla
 * riga dello Switch e spiegazione sotto: una Row con testo lungo spingeva
 * lo Switch fuori schermo (stesso problema gia' visto in GeofenceScreen,
 * niente Modifier.weight in questo progetto).
 */
@Composable
private fun TrackingModeRow(highAccuracy: Boolean, onChange: (Boolean) -> Unit) {
    var pending by remember(highAccuracy) { mutableStateOf<Boolean?>(null) }
    val checked = pending ?: highAccuracy
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.settings_tracking_label), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = checked,
                onCheckedChange = { value ->
                    pending = value
                    onChange(value)
                },
            )
        }
        Text(text = stringResource(R.string.settings_tracking_hint), style = MaterialTheme.typography.bodySmall)
    }
}
