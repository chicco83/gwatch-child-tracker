/**
 * Verifica dei token condivisi (watch <-> backend, Home Assistant <->
 * backend). Token statici in variabili d'ambiente Vercel, confrontati
 * con l'header della richiesta. Stesso schema gia' documentato in
 * CONTEXT.md quando il backend era su Firebase Functions.
 */
function checkDeviceToken(req) {
  const token = req.headers["x-device-token"];
  return Boolean(token) && token === process.env.DEVICE_TOKEN;
}

function checkHaToken(req) {
  const header = req.headers["authorization"] || "";
  const token = header.replace(/^Bearer\s+/i, "");
  return Boolean(token) && token === process.env.HA_STATUS_TOKEN;
}

module.exports = { checkDeviceToken, checkHaToken };
