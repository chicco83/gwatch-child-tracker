package com.gwatch.childtracker.phone.messaging

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.gwatch.childtracker.phone.R
import com.gwatch.childtracker.phone.TrackerApplication
import com.gwatch.childtracker.phone.data.BackendClient
import com.gwatch.childtracker.phone.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Versione: 0.1.0 (2026-10-12)
 *
 * Risposta "Sì, previsto" dalla notifica "viaggio lungo" (FcmService,
 * push "long_trip"): dichiara il viaggio previsto per TRIP_HOURS ore
 * (backend parent-command.js "set_trip_expected"), cosi' il watch in
 * movimento torna alla cadenza di quando e' fermo. Gira anche ad app chiusa:
 * il token Firebase si prende dall'utente gia' autenticato, e goAsync()
 * tiene vivo il ricevitore finche' la chiamata di rete non finisce. La
 * notifica viene sostituita da una di conferma (o di errore), con lo stesso id.
 */
class TripReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val childId = intent.getStringExtra(EXTRA_CHILD_ID) ?: return
        val childName = intent.getStringExtra(EXTRA_CHILD_NAME) ?: ""
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            val ok = try {
                val idToken = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
                idToken != null && BackendClient().setTripExpected(idToken, childId, true, TRIP_HOURS)
            } catch (e: Exception) {
                Log.w(TAG, "risposta al viaggio lungo fallita", e)
                false
            }
            showResult(appContext, notificationId, childName, ok)
            pendingResult.finish()
        }
    }

    private fun showResult(context: Context, notificationId: Int, childName: String, ok: Boolean) {
        val openIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, TrackerApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.trip_confirmed_title))
            .setContentText(
                if (ok) {
                    context.getString(R.string.trip_confirmed_text, childName, TRIP_HOURS)
                } else {
                    context.getString(R.string.trip_failed_text)
                },
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    companion object {
        private const val TAG = "TripReplyReceiver"
        const val EXTRA_CHILD_ID = "childId"
        const val EXTRA_CHILD_NAME = "childName"
        const val EXTRA_NOTIFICATION_ID = "notificationId"

        // Durata del "viaggio previsto" dichiarato dalla notifica (come il
        // default del backend, parent-command.js TRIP_DEFAULT_HOURS).
        const val TRIP_HOURS = 12
    }
}
