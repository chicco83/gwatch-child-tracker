// Configurazione centralizzata per il backend.
// Scritto da qwen3.8turbo-coder il 17-9-26.

const requiredVars = ['FIREBASE_SERVICE_ACCOUNT_B64'];

function validateConfig() {
    const missing = requiredVars.filter(varName => !process.env[varName]);
    if (missing.length > 0) {
        throw new Error(`Missing required env vars: ${missing.join(', ')}`);
    }
}

const isProduction = process.env.NODE_ENV === 'production';

module.exports = {
    validateConfig,
    isProduction,
    LOG_LEVEL: isProduction ? 'info' : 'debug',
    MAX_POINTS_PER_BATCH: 10,
    MAX_BACKEND_CALLS_PER_DAY: 5000,
    RETENTION_HOURS: 24 * 365, // 1 year
};
