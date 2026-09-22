#!/usr/bin/env node
/**
 * Migrazione one-time: assegna un familyId a tutti i documenti
 * parents/devices/geofences che ne sono ancora privi (creati PRIMA del
 * modello di isolamento famiglie, vedi firestore.rules v0.7.0 e
 * parent-command.js v0.4.0). Individuato da qwen3.8-27B-UD-IQ4_XS,
 * implementato da Sonnet 5.
 *
 * Oggi il progetto ospita una sola famiglia reale: questo script
 * genera UN familyId e lo scrive su ogni documento delle tre
 * collezioni che non ce l'ha gia' (idempotente: rieseguirlo non tocca
 * i documenti gia' migrati, comodo se si aggiungono altri documenti
 * legacy prima di aver pubblicato le nuove regole).
 *
 * Va eseguito UNA VOLTA, a mano, con le stesse credenziali del
 * backend, PRIMA di pubblicare firestore.rules v0.7.0 — altrimenti la
 * famiglia gia' in uso perde l'accesso (lettura/scrittura richiedono
 * familyId non appena le nuove regole sono attive) nella finestra fra
 * le due operazioni.
 *
 * Uso:
 *   export FIREBASE_SERVICE_ACCOUNT_B64=<lo stesso valore di Vercel/.env>
 *   node scripts/migrate-family-ids.js
 */
const crypto = require("crypto");
const { getAdminApp } = require("../api/_lib/firebase-admin");
const { getFirestore } = require("firebase-admin/firestore");

async function migrateCollection(db, collectionName, familyId) {
  const snap = await db.collection(collectionName).get();
  const batch = db.batch();
  let migrated = 0;
  snap.docs.forEach((doc) => {
    if (!doc.data().familyId) {
      batch.set(doc.ref, { familyId }, { merge: true });
      migrated++;
    }
  });
  if (migrated > 0) await batch.commit();
  return { total: snap.size, migrated };
}

async function main() {
  getAdminApp();
  const db = getFirestore();
  const familyId = crypto.randomUUID();

  console.log(`familyId generato per la famiglia esistente: ${familyId}`);

  const parents = await migrateCollection(db, "parents", familyId);
  const devices = await migrateCollection(db, "devices", familyId);
  const geofences = await migrateCollection(db, "geofences", familyId);

  console.log(`parents:   ${parents.migrated}/${parents.total} migrati`);
  console.log(`devices:   ${devices.migrated}/${devices.total} migrati`);
  console.log(`geofences: ${geofences.migrated}/${geofences.total} migrate`);
  console.log("Fatto. Ora puoi pubblicare firestore.rules v0.7.0.");
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error("Migrazione fallita:", err);
    process.exit(1);
  });
