/**
 * _lib/fcmTopics.js
 * Versione: 0.1.0
 *
 * Nome del topic FCM per-bambino (Fase 2 del piano di qwen_plan.md,
 * individuato da qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5).
 * Prima trigger-event.js/send-message.js/batteryAlerts.js mandavano
 * tutto sul topic globale "parents": qualunque telefono di qualunque
 * famiglia iscritto a quel topic riceveva SOS/geofence/chat di
 * QUALSIASI bambino di QUALSIASI famiglia sullo stesso progetto
 * Firebase, con sos_alarm/exit_alarm che facevano suonare allarme sul
 * telefono anche per un bambino non proprio. Ogni famiglia ha ora un
 * topic per bambino: la phone-app si iscrive solo ai topic dei propri
 * figli (vedi AppViewModel.kt, dopo aver risolto la propria famiglia).
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-23): versione iniziale.
 */
function childTopic(childId) {
  return `child-${childId}`;
}

module.exports = { childTopic };
