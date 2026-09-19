/**
 * _lib/batteryAlerts.js
 * Versione: 0.1.0
 *
 * Notifiche automatiche di batteria scarica del watch. NON e' un
 * endpoint a se' stante (vedi il limite di 12 Serverless Function su
 * Vercel Hobby, spiegato nell'header di parent-command.js) — un helper
 * condiviso richiamato da ingest-location.js e trigger-event.js, i due
 * soli endpoint che scrivono lo stato batteria su devices/{childId}.
 *
 * Soglie (richieste dall'utente):
 * - 10%: notifica al genitore.
 * - 5%: notifica al genitore.
 * - 2%: notifica al genitore + richiesta forzata della posizione al
 *   watch (stesso "location_request" del pulsante "Aggiorna posizione")
 *   + messaggio automatico di chat al watch, testo fisso "non hai piu'
 *   batteria, aspettami dove sei.".
 *
 * Dedup per "episodio": devices/{childId}.batteryAlertLevel tiene la
 * soglia piu' severa gia' notificata (null | 10 | 5 | 2), per non
 * rimandare la stessa notifica a ogni singolo aggiornamento di
 * posizione mentre la batteria resta bassa. Si resetta (nuovo episodio
 * possibile) solo quando il watch torna in carica o la batteria risale
 * sopra il 15% — isteresi, per evitare notifiche ripetute se il valore
 * oscilla proprio intorno a una soglia (es. 10%/11%/10%).
 * Se la batteria scende di colpo sotto piu' soglie tra un campione e
 * l'altro (es. da 40% a 3%), viene notificata solo la piu' severa tra
 * quelle appena superate, non tutte in sequenza.
 *
 * Non deve mai poter far fallire la richiesta HTTP del chiamante: ogni
 * errore (Firestore, FCM) viene loggato e inghiottito qui, stesso
 * principio di sendPushSafe() in parent-command.js.
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-19): versione iniziale, richiesta dall'utente.
 */
const { getMessaging } = require("firebase-admin/messaging");
const { Timestamp, FieldValue } = require("firebase-admin/firestore");

const PARENTS_TOPIC = "parents";
const MESSAGE_RETENTION_HOURS = 24; // stesso valore di parent-command.js
const LOW_BATTERY_MESSAGE_TEXT = "non hai più batteria, aspettami dove sei.";
const LOW_BATTERY_SENDER_NAME = "gWatch";
const RESET_ABOVE_PERCENT = 15;
const THRESHOLDS = [10, 5, 2]; // dalla meno alla piu' severa

function buildBatteryNotification(level, childName) {
  if (level === 2) {
    return {
      title: `🔋 Batteria quasi scarica — ${childName}`,
      body: `Batteria al 2%. Richiesta la posizione e inviato un messaggio al watch.`,
    };
  }
  return {
    title: `🔋 Batteria scarica — ${childName}`,
    body: `Batteria al ${level}% sullo smartwatch di ${childName}.`,
  };
}

// Stesso pattern di sendPushSafe() in parent-command.js: un errore FCM
// non deve mai diventare un errore fatale per la richiesta chiamante
// (ingest-location.js/trigger-event.js), e un token non piu' valido
// viene ripulito subito invece di restare a marcire su Firestore.
async function sendPushSafe(deviceRef, watchToken, payload) {
  if (!watchToken) return;
  try {
    await getMessaging().send({ token: watchToken, android: { priority: "high" }, ...payload });
  } catch (e) {
    console.error(`batteryAlerts/sendPushSafe: invio fallito (childId=${deviceRef.id})`, e);
    if (e.code === "messaging/registration-token-not-registered") {
      await deviceRef.set({ fcmToken: FieldValue.delete() }, { merge: true });
    }
  }
}

/**
 * @param {FirebaseFirestore.Firestore} db
 * @param {FirebaseFirestore.DocumentReference} deviceRef devices/{childId}
 * @param {string} childId
 * @param {number|null|undefined} battery percentuale 0-100
 * @param {boolean|null|undefined} charging
 * @param {FirebaseFirestore.DocumentData|null} [prefetchedData] snapshot
 *   di deviceRef gia' letto dal chiamante (es. trigger-event.js, che lo
 *   legge comunque prima della propria scrittura) — evita una seconda
 *   lettura Firestore quando disponibile.
 */
async function checkBatteryAlerts(db, deviceRef, childId, battery, charging, prefetchedData) {
  try {
    if (typeof battery !== "number") return;

    const data = prefetchedData ?? (await deviceRef.get()).data() ?? {};
    const childName = data.childName || "Bambino";
    const previousLevel = typeof data.batteryAlertLevel === "number" ? data.batteryAlertLevel : null;

    if (charging === true || battery > RESET_ABOVE_PERCENT) {
      if (previousLevel !== null) {
        await deviceRef.set({ batteryAlertLevel: null }, { merge: true });
      }
      return;
    }

    const crossedLevel = THRESHOLDS.find((t) => battery <= t);
    if (crossedLevel === undefined) return; // ancora sopra la prima soglia (10%)

    // Soglia gia' notificata (o una piu' severa gia' raggiunta in questo
    // stesso episodio) -> non rimandare la stessa notifica ad ogni sample.
    if (previousLevel !== null && previousLevel <= crossedLevel) return;

    await deviceRef.set({ batteryAlertLevel: crossedLevel }, { merge: true });

    const { title, body } = buildBatteryNotification(crossedLevel, childName);
    await getMessaging().send({
      topic: PARENTS_TOPIC,
      notification: { title, body },
      data: { type: "battery_low", level: String(crossedLevel), childId },
      android: { priority: "high" },
    });

    if (crossedLevel === 2) {
      const watchToken = data.fcmToken;

      // Richiesta forzata della posizione: stesso data-only gia' usato
      // dal pulsante "Aggiorna posizione" della phone-app (vedi
      // parent-command.js/handleRequestLocation).
      await sendPushSafe(deviceRef, watchToken, { data: { type: "location_request" } });

      // Messaggio automatico in chat verso il watch: stessa scrittura
      // Firestore + stessa push "data-only" di handleMessage() in
      // parent-command.js, cosi' compare nella chat del watch come un
      // messaggio normale, non solo come notifica di sistema.
      const ts = Timestamp.now();
      const expiresAt = Timestamp.fromMillis(ts.toMillis() + MESSAGE_RETENTION_HOURS * 3_600_000);
      await deviceRef.collection("messages").add({
        sender: "parent",
        senderId: "system",
        senderName: LOW_BATTERY_SENDER_NAME,
        text: LOW_BATTERY_MESSAGE_TEXT,
        timestamp: ts,
        expiresAt,
      });
      await sendPushSafe(deviceRef, watchToken, {
        data: { type: "chat", sender: "parent", senderName: LOW_BATTERY_SENDER_NAME, text: LOW_BATTERY_MESSAGE_TEXT },
      });
    }
  } catch (e) {
    console.error(`checkBatteryAlerts: fallita (childId=${childId})`, e);
  }
}

module.exports = { checkBatteryAlerts };
