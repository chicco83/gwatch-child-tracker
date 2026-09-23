// Test per _lib/quota.js.
// Richiede node_modules (npm install): quota.js importa firebase-admin a top-level.
const { test } = require("node:test");
const assert = require("node:assert/strict");

const { checkAndConsumeQuota, MAX_BACKEND_CALLS_PER_DAY } = require("../api/_lib/quota.js");

function fakeDb(existingCount) {
  const sets = [];
  return {
    sets,
    collection: () => ({ doc: () => ({ collection: () => ({ doc: () => ({}) }) }) }),
    runTransaction: async (fn) =>
      fn({
        get: async () => ({ exists: existingCount !== undefined, data: () => ({ count: existingCount }) }),
        set: (ref, data, opts) => sets.push(data),
      }),
  };
}

test("prima chiamata del giorno (doc assente): concessa e incrementata", async () => {
  const db = fakeDb(undefined);
  assert.equal(await checkAndConsumeQuota(db, "figlio"), true);
  assert.equal(db.sets.length, 1);
});

test("ultima chiamata disponibile (count == MAX-1): concessa", async () => {
  const db = fakeDb(MAX_BACKEND_CALLS_PER_DAY - 1);
  assert.equal(await checkAndConsumeQuota(db, "figlio"), true);
  assert.equal(db.sets.length, 1);
});

test("limite raggiunto (count == MAX): rifiutata SENZA scrivere", async () => {
  const db = fakeDb(MAX_BACKEND_CALLS_PER_DAY);
  assert.equal(await checkAndConsumeQuota(db, "figlio"), false);
  assert.equal(db.sets.length, 0);
});

test("limite superato (count > MAX): rifiutata", async () => {
  const db = fakeDb(MAX_BACKEND_CALLS_PER_DAY + 100);
  assert.equal(await checkAndConsumeQuota(db, "figlio"), false);
});

// v0.2.0 (Fase 3 di qwen_plan.md — individuato da qwen3.8-27B-UD-IQ4_XS,
// implementato da Sonnet 5): weight, vedi Storico versioni in _lib/quota.js.
test("weight: batch che rientra esattamente nel margine residuo: concesso", async () => {
  const db = fakeDb(MAX_BACKEND_CALLS_PER_DAY - 100);
  assert.equal(await checkAndConsumeQuota(db, "figlio", 100), true);
  assert.equal(db.sets.length, 1);
});

test("weight: batch che supererebbe il margine residuo: rifiutato SENZA scrivere, anche se count < MAX", async () => {
  const db = fakeDb(MAX_BACKEND_CALLS_PER_DAY - 50);
  assert.equal(await checkAndConsumeQuota(db, "figlio", 100), false);
  assert.equal(db.sets.length, 0);
});
