#!/usr/bin/env node
// Test per _lib/auth.js.
// Bug (2026-09-18): sintassi import ESM in un progetto senza
// "type": "module" in package.json (tutto il resto del backend e'
// CommonJS, vedi _lib/auth.js stesso) — "node --test test/" (lo
// script "test" di package.json) falliva a caricare questo file con
// "Cannot use import statement outside a module". Riportato a
// require(), come quota.test.js.
const { test, describe } = require("node:test");
const assert = require("node:assert/strict");
const { timingSafeEquals, checkHaToken } = require("../api/_lib/auth.js");
describe("timingSafeEquals", () => {
  test("stesso valore -> true", () => {
    assert.equal(timingSafeEquals("abc", "abc"), true);
  });
  test("valori diversi -> false", () => {
    assert.equal(timingSafeEquals("abc", "abd"), false);
    assert.equal(timingSafeEquals("", "x"), false);
  });
  test("undefined/null diventano stringa vuota", () => {
    assert.equal(timingSafeEquals(undefined, undefined), true);
    assert.equal(timingSafeEquals(null, ""), true);
  });
});
describe("checkHaToken", () => {
  test("token corretto -> true", () => {
    process.env.HA_STATUS_TOKEN = "ha-123";
    assert.equal(checkHaToken({ headers: { authorization: "Bearer ha-123" } }), true);
  });
  test("token errato -> false", () => {
    process.env.HA_STATUS_TOKEN = "ha-123";
    assert.equal(checkHaToken({ headers: { authorization: "Bearer wrong" } }), false);
  });
  test("env non impostata -> fail-closed", () => {
    delete process.env.HA_STATUS_TOKEN;
    assert.equal(checkHaToken({ headers: { authorization: "Bearer undefined" } }), false);
  });
});
console.log("auth.test.js completato");
