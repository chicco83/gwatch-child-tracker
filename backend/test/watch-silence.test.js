// Test per api/watch-silence.js — silenceDecision (v0.1.0).
// Versione: 0.1.0 (2026-09-24).
// Richiede node_modules (npm install): watch-silence.js importa firebase-admin a top-level.
const { test } = require("node:test");
const assert = require("node:assert/strict");

const { silenceDecision, SILENCE_AFTER_MS } = require("../api/watch-silence.js");

// Finto Timestamp Firestore: basta toMillis().
const ts = (ms) => ({ toMillis: () => ms });
const NOW = 1_790_000_000_000;
const MIN = 60_000;

test("watch mai visto: nessun avviso", () => {
  assert.equal(silenceDecision({}, NOW).notify, false);
});

test("contatto recente: nessun avviso", () => {
  assert.equal(silenceDecision({ lastSeen: ts(NOW - 20 * MIN) }, NOW).notify, false);
});

test("silenzio oltre la soglia: avviso", () => {
  const d = silenceDecision({ lastSeen: ts(NOW - SILENCE_AFTER_MS - MIN) }, NOW);
  assert.equal(d.notify, true);
  assert.equal(d.lastContactMs, NOW - SILENCE_AFTER_MS - MIN);
});

test("conta il contatto piu' recente fra posizione, stato e avvisi", () => {
  const data = { lastSeen: ts(NOW - 5 * 60 * MIN), lastStatusAt: ts(NOW - 10 * MIN) };
  assert.equal(silenceDecision(data, NOW).notify, false);
});

test("modalita' aereo gia' segnalata: nessun avviso", () => {
  const at = NOW - 3 * 60 * MIN;
  const data = { lastSeen: ts(at - 5 * MIN), lastStatusAt: ts(at + 5_000), watchState: { state: "airplane", at: ts(at) } };
  assert.equal(silenceDecision(data, NOW).notify, false);
});

test("aereo superato da un contatto successivo: avviso possibile", () => {
  const at = NOW - 5 * 60 * MIN;
  const data = { lastSeen: ts(NOW - 2 * 60 * MIN), watchState: { state: "airplane", at: ts(at) } };
  assert.equal(silenceDecision(data, NOW).notify, true);
});

test("un solo avviso per periodo di silenzio", () => {
  const data = { lastSeen: ts(NOW - 3 * 60 * MIN), silenceAlertAt: ts(NOW - 60 * MIN) };
  assert.equal(silenceDecision(data, NOW).notify, false);
});

test("nuovo silenzio dopo che il watch si e' fatto risentire: nuovo avviso", () => {
  const data = { lastSeen: ts(NOW - 2 * 60 * MIN), silenceAlertAt: ts(NOW - 5 * 60 * MIN) };
  assert.equal(silenceDecision(data, NOW).notify, true);
});
