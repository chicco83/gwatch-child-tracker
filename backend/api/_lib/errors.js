// Error handling utilities per il backend.
//
// Storico versioni:
// - 0.2.0 (2026-09-23): Fase 4 di qwen_plan.md (individuato da
//   qwen3.8-27B-UD-IQ4_XS, implementato da Sonnet 5) — validateConfig()
//   (_lib/config.js) era richiamato a mano solo in 3 endpoint su 9
//   (ingest-location, register-watch-token, sos-heartbeat), con lo
//   stesso identico blocco try/catch copiato in ognuno: negli altri 6
//   una configurazione rotta (es. FIREBASE_SERVICE_ACCOUNT_B64
//   mancante) sarebbe fallita in modo diverso/meno chiaro a seconda
//   del punto in cui l'Admin SDK l'avesse notato. Spostato dentro
//   wrapHandler: ora gira per TUTTI gli endpoint, un solo posto invece
//   di N copie da tenere allineate.
const { validateConfig } = require("./config.js");

/**
 * Wraps an async handler to catch errors and return appropriate HTTP responses.
 */
function wrapHandler(handler) {
  return async (req, res) => {
    try {
      validateConfig();
    } catch (err) {
      console.error("Config validation failed:", err.message);
      res.status(500).json({ ok: false, error: "Internal server error: configuration" });
      return;
    }
    try {
      await handler(req, res);
    } catch (err) {
      console.error("Handler error:", err);
      if (!res.headersSent) {
        res.status(500).json({ ok: false, error: "Internal server error" });
      }
    }
  };
}

/**
 * Sends an error response with optional status code.
 */
function errorResponse(res, statusCode, message) {
  const code = statusCode || 500;
  res.status(code).json({ ok: false, error: message || "Error" });
}

/**
 * Sends a success response with optional data.
 */
function successResponse(res, data) {
  if (data !== undefined) {
    res.status(200).json({ ok: true, ...data });
  } else {
    res.status(200).json({ ok: true });
  }
}

/**
 * Logs an error with context.
 */
function logError(context, err) {
  console.error(`[${context}]`, err);
}

module.exports = { wrapHandler, errorResponse, successResponse, logError };
