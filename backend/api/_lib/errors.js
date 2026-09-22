// Error handling utilities per il backend.

/**
 * Wraps an async handler to catch errors and return appropriate HTTP responses.
 */
function wrapHandler(handler) {
  return async (req, res) => {
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
