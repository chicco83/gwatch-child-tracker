/**
 * GET /api/cleanup
 * Versione: 0.1.0
 *
 * Pulizia programmata dello storico scaduto. Sostituisce la TTL
 * policy nativa di Firestore: quella richiede il piano Blaze anche se
 * l'uso reale resterebbe gratuito (vedi CONTEXT.md), quindi puliamo i
 * documenti scaduti da soli con un Cron Job di Vercel (gratuito anche
 * su piano Hobby, nessuna carta).
 *
 * Invocato automaticamente da Vercel Cron una volta al giorno (vedi
 * vercel.json). Autenticato con l'header che Vercel inietta da solo
 * quando e' impostata la variabile d'ambiente CRON_SECRET.
 */
const { getFirestore, Timestamp } = require("firebase-admin/firestore");
const { getAdminApp } = require("./_lib/firebase-admin");

const BATCH_SIZE = 500;
const MAX_BATCHES_PER_RUN = 10; // tetto di sicurezza: max 5.000 delete/esecuzione

async function purgeExpired(db, collectionGroupName) {
  let totalDeleted = 0;
  for (let i = 0; i < MAX_BATCHES_PER_RUN; i++) {
    const snap = await db
      .collectionGroup(collectionGroupName)
      .where("expiresAt", "<=", Timestamp.now())
      .limit(BATCH_SIZE)
      .get();

    if (snap.empty) break;

    const batch = db.batch();
    snap.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
    totalDeleted += snap.size;

    if (snap.size < BATCH_SIZE) break;
  }
  return totalDeleted;
}

module.exports = async (req, res) => {
  const authHeader = req.headers["authorization"] || "";
  const expected = `Bearer ${process.env.CRON_SECRET}`;
  if (!process.env.CRON_SECRET || authHeader !== expected) {
    res.status(401).send("Unauthorized");
    return;
  }

  getAdminApp();
  const db = getFirestore();

  const [locationsDeleted, quotaDeleted] = await Promise.all([
    purgeExpired(db, "locations"),
    purgeExpired(db, "quota"),
  ]);

  res.status(200).json({ ok: true, locationsDeleted, quotaDeleted });
};
