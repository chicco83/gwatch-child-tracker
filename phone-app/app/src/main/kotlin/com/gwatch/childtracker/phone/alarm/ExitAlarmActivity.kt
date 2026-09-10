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

/**
 * Schermata a tutto schermo mostrata sopra il lockscreen quando parte
 * l'allarme di uscita zona (full-screen intent dalla notifica di
 * ExitAlarmService): un pulsante "Ferma" grande e immediato, piu'
 * rapido da raggiungere in un momento di ansia rispetto a sbloccare il
 * telefono e aprire la tendina notifiche.
 *
 * I flag di finestra sotto sono deprecati dalla API 27 in favore di
 * setShowWhenLocked()/setTurnScreenOn(), ma restano funzionanti e
 * coprono senza distinzioni tutto il minSdk 26 del progetto, evitando
 * un branch per versione solo per questa schermata.
 */
class ExitAlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )

        val zoneName = intent.getStringExtra(ExitAlarmService.EXTRA_ZONE_NAME) ?: ""
        setContent {
            ExitAlarmScreen(zoneName = zoneName, onStop = { stopAlarmAndFinish() })
        }
    }

    private fun stopAlarmAndFinish() {
        startService(Intent(this, ExitAlarmService::class.java).setAction(ExitAlarmService.ACTION_STOP))
        finish()
    }
}

@Composable
private fun ExitAlarmScreen(zoneName: String, onStop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.exit_alarm_title), style = MaterialTheme.typography.headlineMedium)
        Text(text = stringResource(R.string.exit_alarm_body, zoneName))
        Button(onClick = onStop, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.exit_alarm_stop))
        }
    }
}
