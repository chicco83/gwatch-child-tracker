/**
 * _lib/tripAlerts.js
 * Versione: 0.2.0 (2026-10-12)
 *
 * Avviso "viaggio lungo" al genitore. NON e' un endpoint a se' stante (limite
 * di 12 Serverless Function su Vercel Hobby, vedi header di
 * parent-command.js): helper richiamato da trigger-event.js quando il watch
 * manda uno "status" con reason "long_trip".
 *
 * Perche' (analisi consumi del 12/10/2026): una giornata di viaggio con il GPS
 * a un punto al minuto per 5 ore e' il carico peggiore per la batteria del
 * watch. Il watch si accorge da solo di essere in viaggio (in movimento da
 * almeno 15 minuti e ad almeno 10 km da dove e' partito) e avvisa; il
 * genitore puo' rispondere "viaggio previsto" (parent-command.js, azione
 * set_trip_expected) e il watch torna alla cadenza di quando e' fermo.
 *
 * Regole:
 * - nessun avviso se un viaggio previsto e' gia' attivo (tripExpectedUntil
 *   nel futuro): il genitore ha gia' risposto;
 * - al massimo un avviso ogni COOLDOWN_MS per bambino (lastLongTripAlertAt):
 *   il watch avvisa una volta per viaggio, ma se il suo processo riparte
 *   perde la memoria dell'avviso gia' mandato;
 * - push SOLO "data" sul topic del bambino: con un payload "notification" ad
 *   app in background Android mostrerebbe la notifica da solo, senza passare
 *   dal codice dell'app, e non si potrebbe aggiungere il pulsante di risposta.
 *
 * Non deve mai far fallire la richiesta del chiamante: ogni errore e' loggato
 * e inghiottito (stesso principio di batteryAlerts.js).
 *
 * Storico versioni:
 * - 0.1.0 (2026-10-12): versione iniziale.
 * - 0.2.0 (2026-10-12): clearTripOnZoneEnter — richiesta utente: il viaggio
 *   previsto si annulla quando il watch entra in una zona (qualunque).
 */
const { childTopic } = require("./fcmTopics.js");

const COOLDOWN_MS = 3 * 3_600_000;
const MAX_MINUTES = 24 * 60;
const MAX_KM = 5000;

function millisOf(ts) {
  return ts && typeof ts.toMillis === "function" ? ts.toMillis() : 0;
}

/** true se per questo bambino si puo' mandare l'avviso adesso. */
function shouldAlert(deviceData, nowMs) {
  const data = deviceData || {};
  if (millisOf(data.tripExpectedUntil) > nowMs) return false;
  return nowMs - millisOf(data.lastLongTripAlertAt) >= COOLDOWN_MS;
}

/** Numero finito entro [0, max], altrimenti null. */
function clampNumber(value, max) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) return null;
  return Math.min(value, max);
}

/** Payload "data" della push (tutti i valori devono essere stringhe). */
function buildPayload(childId, childName, minutes, km) {
  return {
    type: "long_trip",
    childId,
    childName: childName || "Bambino",
    minutes: String(Math.round(minutes)),
    km: String(Math.round(km)),
  };
}

/**
 * @param {FirebaseFirestore.DocumentReference} deviceRef devices/{childId}
 * @param {string} childId
 * @param {*} minutes minuti in movimento dichiarati dal watch
 * @param {*} km distanza in linea d'aria da dove e' iniziato il movimento
 * @param {object} [deps] iniezione per i test: { now, send, serverTimestamp }
 * @returns {Promise<boolean>} true se l'avviso e' stato inviato
 */
async function maybeSendLongTripAlert(deviceRef, childId, minutes, km, deps = {}) {
  try {
    const mins = clampNumber(minutes, MAX_MINUTES);
    const dist = clampNumber(km, MAX_KM);
    if (mins === null || dist === null) return false;

    const nowMs = deps.now ? deps.now() : Date.now();
    const data = (await deviceRef.get()).data() ?? {};
    if (!shouldAlert(data, nowMs)) return false;

    const serverTimestamp = deps.serverTimestamp ?? (() => require("firebase-admin/firestore").FieldValue.serverTimestamp());
    await deviceRef.set({ lastLongTripAlertAt: serverTimestamp() }, { merge: true });

    const send = deps.send ?? ((message) => require("firebase-admin/messaging").getMessaging().send(message));
    await send({
      topic: childTopic(childId),
      data: buildPayload(childId, data.childName, mins, dist),
      android: { priority: "high" },
    });
    return true;
  } catch (e) {
    console.error(`maybeSendLongTripAlert: fallito (childId=${childId})`, e);
    return false;
  }
}

// Un ingresso in zona piu' vecchio di cosi' (evento consegnato in ritardo dal
// watch, offline per ore) non deve annullare un viaggio dichiarato dopo.
const STALE_ENTER_MS = 10 * 60_000;

/** true se questo ingresso in zona deve annullare un viaggio previsto ancora attivo. */
function shouldClearOnEnter(deviceData, eventMs, nowMs) {
  return millisOf((deviceData || {}).tripExpectedUntil) > nowMs && nowMs - eventMs <= STALE_ENTER_MS;
}

/**
 * Annulla il "viaggio previsto" quando il watch entra in una zona (richiesta
 * utente 2026-10-12): toglie tripExpectedUntil dal device (e' la fonte che
 * device-config rimanderebbe al watch) e manda al watch la push "trip_mode"
 * con until "0" per tornare subito alla cadenza normale.
 *
 * @param {FirebaseFirestore.DocumentReference} deviceRef devices/{childId}
 * @param {object} deviceData dati del device gia' letti dal chiamante
 * @param {number} eventMs istante dell'ingresso (timestamp dell'evento)
 * @param {object} [deps] iniezione per i test: { now, send, deleteValue }
 * @returns {Promise<boolean>} true se il viaggio previsto e' stato annullato
 */
async function clearTripOnZoneEnter(deviceRef, deviceData, eventMs, deps = {}) {
  try {
    const nowMs = deps.now ? deps.now() : Date.now();
    if (!shouldClearOnEnter(deviceData, eventMs, nowMs)) return false;

    const deleteValue = deps.deleteValue ?? (() => require("firebase-admin/firestore").FieldValue.delete());
    await deviceRef.set({ tripExpectedUntil: deleteValue() }, { merge: true });

    const token = deviceData.fcmToken;
    if (token) {
      const send = deps.send ?? ((message) => require("firebase-admin/messaging").getMessaging().send(message));
      try {
        await send({ token, data: { type: "trip_mode", until: "0" }, android: { priority: "high" } });
      } catch (e) {
        // Il watch si riallinea comunque alla prossima sync di device-config.
        console.error(`clearTripOnZoneEnter: push fallita (childId=${deviceRef.id})`, e);
      }
    }
    return true;
  } catch (e) {
    console.error(`clearTripOnZoneEnter: fallita (childId=${deviceRef.id})`, e);
    return false;
  }
}

module.exports = {
  maybeSendLongTripAlert,
  clearTripOnZoneEnter,
  shouldAlert,
  shouldClearOnEnter,
  buildPayload,
  COOLDOWN_MS,
  STALE_ENTER_MS,
};
