// Test per api/_lib/tripAlerts.js (v0.2.0).
// Versione: 0.2.0 (2026-10-12).
// Nessuna dipendenza da firebase-admin: maybeSendLongTripAlert riceve now/send/serverTimestamp iniettati.
const { test } = require("node:test");
const assert = require("node:assert/strict");

const {
  maybeSendLongTripAlert,
  clearTripOnZoneEnter,
  shouldAlert,
  shouldClearOnEnter,
  buildPayload,
  COOLDOWN_MS,
  STALE_ENTER_MS,
} = require("../api/_lib/tripAlerts.js");

// Finto Timestamp Firestore: basta toMillis().
const ts = (ms) => ({ toMillis: () => ms });
const NOW = 1_790_000_000_000;
const HOUR = 3_600_000;

// Finto DocumentReference: get() restituisce i dati, set() li registra.
function fakeRef(data) {
  const sets = [];
  return {
    sets,
    get: async () => ({ data: () => data }),
    set: async (obj, opts) => sets.push({ obj, opts }),
  };
}

test("nessun dato precedente: avviso", () => {
  assert.equal(shouldAlert({}, NOW), true);
  assert.equal(shouldAlert(undefined, NOW), true);
});

test("viaggio previsto ancora attivo: nessun avviso", () => {
  assert.equal(shouldAlert({ tripExpectedUntil: ts(NOW + HOUR) }, NOW), false);
});

test("viaggio previsto scaduto: avviso", () => {
  assert.equal(shouldAlert({ tripExpectedUntil: ts(NOW - HOUR) }, NOW), true);
});

test("avviso gia' mandato da meno del cooldown: niente", () => {
  assert.equal(shouldAlert({ lastLongTripAlertAt: ts(NOW - COOLDOWN_MS + 60_000) }, NOW), false);
});

test("avviso mandato oltre il cooldown: di nuovo possibile", () => {
  assert.equal(shouldAlert({ lastLongTripAlertAt: ts(NOW - COOLDOWN_MS - 60_000) }, NOW), true);
});

test("payload: tutti i valori sono stringhe e il nome ha un ripiego", () => {
  const p = buildPayload("figlio", "", 47.6, 183.2);
  assert.deepEqual(p, { type: "long_trip", childId: "figlio", childName: "Bambino", minutes: "48", km: "183" });
  for (const v of Object.values(p)) assert.equal(typeof v, "string");
});

test("invio: scrive lastLongTripAlertAt e manda la push sul topic del bambino", async () => {
  const ref = fakeRef({ childName: "Marco" });
  const sent = [];
  const ok = await maybeSendLongTripAlert(ref, "figlio", 20, 15, {
    now: () => NOW,
    send: async (m) => sent.push(m),
    serverTimestamp: () => "TS",
  });
  assert.equal(ok, true);
  assert.deepEqual(ref.sets, [{ obj: { lastLongTripAlertAt: "TS" }, opts: { merge: true } }]);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].topic, "child-figlio");
  assert.equal(sent[0].data.type, "long_trip");
  assert.equal(sent[0].data.childName, "Marco");
  assert.equal(sent[0].notification, undefined); // solo "data": serve il codice dell'app per il pulsante
});

test("cooldown: seconda chiamata ravvicinata non manda nulla", async () => {
  const ref = fakeRef({ lastLongTripAlertAt: ts(NOW - HOUR) });
  const sent = [];
  const ok = await maybeSendLongTripAlert(ref, "figlio", 20, 15, { now: () => NOW, send: async (m) => sent.push(m) });
  assert.equal(ok, false);
  assert.equal(sent.length, 0);
  assert.equal(ref.sets.length, 0);
});

test("valori non validi dal watch: scartati senza scrivere", async () => {
  for (const [m, k] of [["x", 10], [10, NaN], [-1, 10], [null, null], [10, undefined]]) {
    const ref = fakeRef({});
    const sent = [];
    const ok = await maybeSendLongTripAlert(ref, "figlio", m, k, { now: () => NOW, send: async (x) => sent.push(x) });
    assert.equal(ok, false);
    assert.equal(sent.length, 0);
    assert.equal(ref.sets.length, 0);
  }
});

test("errore FCM: non propaga l'errore al chiamante", async () => {
  const ref = fakeRef({});
  const ok = await maybeSendLongTripAlert(ref, "figlio", 20, 15, {
    now: () => NOW,
    serverTimestamp: () => "TS",
    send: async () => {
      throw new Error("fcm down");
    },
  });
  assert.equal(ok, false);
});

// --- v0.2.0: annullamento del viaggio previsto all'ingresso in una zona ---

test("ingresso in zona con viaggio previsto attivo: va annullato", () => {
  assert.equal(shouldClearOnEnter({ tripExpectedUntil: ts(NOW + HOUR) }, NOW - 1000, NOW), true);
});

test("nessun viaggio previsto o gia' scaduto: niente da annullare", () => {
  assert.equal(shouldClearOnEnter({}, NOW, NOW), false);
  assert.equal(shouldClearOnEnter(undefined, NOW, NOW), false);
  assert.equal(shouldClearOnEnter({ tripExpectedUntil: ts(NOW - HOUR) }, NOW, NOW), false);
});

test("ingresso vecchio (consegnato in ritardo): non annulla un viaggio dichiarato dopo", () => {
  const data = { tripExpectedUntil: ts(NOW + HOUR) };
  assert.equal(shouldClearOnEnter(data, NOW - STALE_ENTER_MS - 1000, NOW), false);
  assert.equal(shouldClearOnEnter(data, NOW - STALE_ENTER_MS + 1000, NOW), true);
});

test("annullamento: toglie tripExpectedUntil e manda trip_mode con until 0 al watch", async () => {
  const ref = fakeRef({});
  const sent = [];
  const ok = await clearTripOnZoneEnter(ref, { tripExpectedUntil: ts(NOW + HOUR), fcmToken: "tok" }, NOW, {
    now: () => NOW,
    deleteValue: () => "DEL",
    send: async (m) => sent.push(m),
  });
  assert.equal(ok, true);
  assert.deepEqual(ref.sets, [{ obj: { tripExpectedUntil: "DEL" }, opts: { merge: true } }]);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].token, "tok");
  assert.deepEqual(sent[0].data, { type: "trip_mode", until: "0" });
});

test("annullamento senza token del watch: toglie il campo, nessuna push", async () => {
  const ref = fakeRef({});
  const sent = [];
  const ok = await clearTripOnZoneEnter(ref, { tripExpectedUntil: ts(NOW + HOUR) }, NOW, {
    now: () => NOW,
    deleteValue: () => "DEL",
    send: async (m) => sent.push(m),
  });
  assert.equal(ok, true);
  assert.equal(ref.sets.length, 1);
  assert.equal(sent.length, 0);
});

test("annullamento con push fallita: il campo e' comunque tolto", async () => {
  const ref = fakeRef({});
  const ok = await clearTripOnZoneEnter(ref, { tripExpectedUntil: ts(NOW + HOUR), fcmToken: "tok" }, NOW, {
    now: () => NOW,
    deleteValue: () => "DEL",
    send: async () => {
      throw new Error("fcm down");
    },
  });
  assert.equal(ok, true);
  assert.equal(ref.sets.length, 1);
});

test("nessun viaggio attivo: nessuna scrittura e nessuna push", async () => {
  const ref = fakeRef({});
  const sent = [];
  const ok = await clearTripOnZoneEnter(ref, {}, NOW, { now: () => NOW, send: async (m) => sent.push(m) });
  assert.equal(ok, false);
  assert.equal(ref.sets.length, 0);
  assert.equal(sent.length, 0);
});
