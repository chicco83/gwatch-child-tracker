/**
 * GET /api/watch-silence
 * Versione: 0.1.0 (2026-09-24)
 *
 * Avviso "watch muto": push al telefono del genitore quando un watch non
 * manda nulla (posizioni, stato batteria, avvisi di stato) da piu' di
 * SILENCE_AFTER_MS. Richiesto dall'utente il 24/9 dopo una mattinata
 * senza posizioni: l'app sul watch era rimasta in "arresto forzato"
 * dalle 01:23 (fine sessione Android Studio) e nessuno se n'era accorto
 * fino a meta' mattina — la phone-app mostrava l'icona 📵 solo a chi la
 * apriva.
 *
 * Invocato ogni 30 minuti da GitHub Actions (.github/workflows/
 * watch-silence-cron.yml), stesso schema e stesso CRON_SECRET di
 * cleanup.js (Vercel Hobby non permette i "crons" in vercel.json).
 *
 * Regole (vedi silenceDecision, testata in test/watch-silence.test.js):
 * - ultimo contatto = il piu' recente fra lastSeen (posizione),
 *   lastStatusAt (stato senza posizione) e watchState.at (avvisi);
 * - niente avviso se l'ultimo stato noto e' "modalita' aereo" o "spento":
 *   il genitore ha gia' ricevuto quella push, e il silenzio e' atteso;
 * - un solo avviso per periodo di silenzio: devices/{id}.silenceAlertAt
 *   salva quando e' stato mandato; il prossimo e' possibile solo dopo che
 *   il watch si e' fatto risentire (ultimo contatto > silenceAlertAt).
 * Nessuna push di "tornato raggiungibile": la prima posizione nuova basta.
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-24): prima versione.
 */
const { getFirestore, Timestamp } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { wrapHandler } = require("./_lib/errors.js");
const { getAdminApp } = require("./_lib/firebase-admin");
const { timingSafeEquals } = require("./_lib/auth");
const { childTopic } = require("./_lib/fcmTopics.js");

// Il tracking da fermo manda un punto almeno ogni 10', l'upload a gruppi
// ogni 15': un'ora senza nulla non e' piu' un ritardo normale. La soglia
// della phone-app per l'icona 📵 resta 30' (li' costa niente sbagliare).
const SILENCE_AFTER_MS = 60 * 60 * 1000;

function toMillis(ts) {
  return ts && typeof ts.toMillis === "function" ? ts.toMillis() : null;
}

/**
 * Decide se mandare l'avviso per un device. Funzione pura (testabile):
 * ritorna { notify: boolean, lastContactMs: number|null }.
 */
function silenceDecision(data, nowMs) {
  const stateAt = toMillis(data.watchState?.at);
  const contacts = [toMillis(data.lastSeen), toMillis(data.lastStatusAt), stateAt].filter((v) => v !== null);
  if (contacts.length === 0) return { notify: false, lastContactMs: null }; // watch mai visto
  const lastContactMs = Math.max(...contacts);

  if (nowMs - lastContactMs <= SILENCE_AFTER_MS) return { notify: false, lastContactMs };

  // Aereo/spento ancora valido = nessun contatto dopo l'avviso (margine
  // 60 s: lo stesso invio aggiorna lastStatusAt pochi secondi dopo).
  const state = data.watchState?.state;
  if ((state === "airplane" || state === "off") && stateAt !== null && lastContactMs <= stateAt + 60_000) {
    return { notify: false, lastContactMs };
  }

  const alertAt = toMillis(data.silenceAlertAt);
  if (alertAt !== null && alertAt >= lastContactMs) return { notify: false, lastContactMs }; // gia' avvisato

  return { notify: true, lastContactMs };
}

function formatTimeIt(ms) {
  return new Date(ms).toLocaleTimeString("it-IT", { timeZone: "Europe/Rome", hour: "2-digit", minute: "2-digit" });
}

const handler = wrapHandler(async (req, res) => {
  const authHeader = req.headers["authorization"] || "";
  if (!process.env.CRON_SECRET || !timingSafeEquals(authHeader, `Bearer ${process.env.CRON_SECRET}`)) {
    res.status(401).send("Unauthorized");
    return;
  }

  getAdminApp();
  const db = getFirestore();
  const nowMs = Date.now();
  const devices = await db.collection("devices").get();
  let notified = 0;

  for (const dev of devices.docs) {
    const data = dev.data();
    const { notify, lastContactMs } = silenceDecision(data, nowMs);
    if (!notify) continue;

    const childName = data.childName || "Bambino";
    try {
      await getMessaging().send({
        topic: childTopic(dev.id),
        notification: {
          title: `📵 ${childName}: nessuna notizia dal watch`,
          body:
            `Ultimo contatto alle ${formatTimeIt(lastContactMs)}. ` +
            "Il watch potrebbe essere senza rete, scarico o con l'app ferma (va riaperta sul watch).",
        },
        data: { type: "watch_silent", childId: dev.id },
        android: { priority: "high" },
      });
      // Salvato solo a push riuscita: se FCM fallisce si ritenta al giro dopo.
      await dev.ref.set({ silenceAlertAt: Timestamp.fromMillis(nowMs) }, { merge: true });
      notified++;
    } catch (e) {
      console.error(`watch-silence: push fallita (childId=${dev.id})`, e);
    }
  }

  res.status(200).json({ ok: true, devices: devices.size, notified });
});

module.exports = handler;
module.exports.silenceDecision = silenceDecision;
module.exports.SILENCE_AFTER_MS = SILENCE_AFTER_MS;
