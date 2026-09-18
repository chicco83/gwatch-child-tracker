package com.gwatch.childtracker.phone.alarm

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gwatch.childtracker.phone.R
import com.gwatch.childtracker.phone.ui.MainActivity

/**
 * Schermata a tutto schermo mostrata sopra il lockscreen quando parte
 * l'allarme SOS (full-screen intent da SosAlarmService) — stesso
 * pattern di ExitAlarmActivity.kt. Un solo pulsante grande: ferma il
 * suono E apre subito la mappa (dove il banner SOS gia' esistente
 * mostra posizione + pulsante "Disattiva", vedi MapScreen.kt), invece
 * di una schermata dedicata separata — l'azione utile in un momento di
 * ansia e' vedere dove si trova il bambino, non solo silenziare.
 */
class SosAlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )

        val childName = intent.getStringExtra(SosAlarmService.EXTRA_CHILD_NAME) ?: ""
        setContent {
            SosAlarmScreen(childName = childName, onOpenMap = { stopAlarmAndOpenMap() })
        }
    }

    private fun stopAlarmAndOpenMap() {
        startService(Intent(this, SosAlarmService::class.java).setAction(SosAlarmService.ACTION_STOP))
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}

@Composable
private fun SosAlarmScreen(childName: String, onOpenMap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.error)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.sos_alarm_title, childName),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onError,
        )
        Text(
            text = stringResource(R.string.sos_alarm_body),
            color = MaterialTheme.colorScheme.onError,
        )
        Button(onClick = onOpenMap, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.sos_alarm_open_map))
        }
    }
}
