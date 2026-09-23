// Test per api/cleanup.js — purgeExpired (v0.8.0).
// Versione: 0.1.0 (2026-09-23) — punto 6 di qwen_plan.md (individuato da
// qwen3.8-27B-UD-IQ4_XS, implementato da Opus 5.5).
// Richiede node_modules (npm install): cleanup.js importa firebase-admin a top-level.
const { test } = require("node:test");
const assert = require("node:assert/strict");

const { purgeExpired } = require("../api/cleanup.js");

// Finto Firestore: "remaining" documenti scaduti, restituiti a blocchi di
// al massimo "limit"; ogni commit li toglie davvero dal conteggio.
function fakeDb(remaining) {
  const state = { remaining, queries: 0, commits: 0 };
  const query = {
    where: () => query,
    limit: (n) => ({
      get: async () => {
        state.queries++;
        const size = Math.min(n, state.remaining);
        return { empty: size === 0, size, docs: Array.from({ length: size }, () => ({ ref: {} })) };
      },
    }),
  };
  return {
    state,
    collectionGroup: () => query,
    batch: () => {
      let n = 0;
      return {
        delete: () => n++,
        commit: async () => {
          state.remaining -= n;
          state.commits++;
        },
      };
    },
  };
}

const FAR_FUTURE = Date.now() + 60_000;

test("niente da cancellare: more=false, una sola query", async () => {
  const db = fakeDb(0);
  assert.deepEqual(await purgeExpired(db, "locations", FAR_FUTURE), { deleted: 0, more: false });
  assert.equal(db.state.queries, 1);
});

test("meno di un blocco: cancella tutto, more=false", async () => {
  const db = fakeDb(120);
  assert.deepEqual(await purgeExpired(db, "locations", FAR_FUTURE), { deleted: 120, more: false });
});

test("oltre il tetto di 10 blocchi: si ferma a 5000, more=true", async () => {
  const db = fakeDb(7000);
  assert.deepEqual(await purgeExpired(db, "locations", FAR_FUTURE), { deleted: 5000, more: true });
  assert.equal(db.state.remaining, 2000);
});

test("budget di tempo gia' scaduto: nessuna query, more=true", async () => {
  const db = fakeDb(1000);
  assert.deepEqual(await purgeExpired(db, "locations", Date.now() - 1), { deleted: 0, more: true });
  assert.equal(db.state.queries, 0);
});
