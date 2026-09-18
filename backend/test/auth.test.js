#!/usr/bin/env node
// Test per _lib/auth.js — corretto da qwen3.8-Flash-Next il 16-9-26.
import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { timingSafeEquals, checkHaToken } from "../api/_lib/auth.js";
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
