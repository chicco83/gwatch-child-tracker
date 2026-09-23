#!/usr/bin/env node
/**
 * Diagnostica di SOLA LETTURA dello storico dei dispositivi.
 * Versione: 0.4.0 (2026-09-23)
 *
 * Serve a datare un problema ("da quando non arrivano piu' posizioni /
 * eventi zona?") senza aprire la Firebase Console. Autorizzata
 * dall'utente in modo permanente il 2026-09-23.
 *
 * Privacy: stampa solo date, conteggi, tipi di evento e precisione in
 * metri — MAI coordinate, nomi o testi dei messaggi.
 *
 * Storico versioni:
 * - 0.1.0 (2026-09-23): prima versione.
 * - 0.2.0 (2026-09-23): aggiunta la precisione (accuracy, metri) delle
 *   ultime posizioni: distingue un fix GPS (pochi metri) da una
 *   posizione da rete Wi-Fi/celle (decine-centinaia di metri).
 * - 0.3.0 (2026-09-23): orari in ora italiana (Europe/Rome) invece che
 *   UTC — l'utente leggeva 20:50 (UTC) per un invio delle 22:50 locali.
 *   Precedente: iso() con toISOString() + "Z" (UTC).
 * - 0.4.0 (2026-09-23): precisione mediana per giorno — capire se nei
 *   giorni in cui il tracking funzionava le posizioni venivano dal GPS
 *   (pochi metri) o da Wi-Fi/celle (decine di metri).
 * Sicurezza: nessuna scrittura (solo get()).
 *
 * Uso (stesse credenziali del backend, gia' nell'ambiente):
 *   node backend/scripts/diag-device-history.js [giorni=8]
 */
const path = require("path");
module.paths.unshift(path.join(__dirname, "..", "node_modules"));
const { getAdminApp } = require("../api/_lib/firebase-admin");
const { getFirestore } = require("firebase-admin/firestore");

const days = Number(process.argv[2]) || 8;

// Timestamp Firestore, Date o millisecondi -> Date (null se assente).
function toDate(v) {
  if (!v) return null;
  if (typeof v.toDate === "function") return v.toDate();
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? null : d;
}

// v0.3.0: ora italiana. Precedente (UTC):
// const iso = (d) => (d ? d.toISOString().replace("T", " ").slice(0, 19) + "Z" : "-");
const iso = (d) => (d ? d.toLocaleString("sv-SE", { timeZone: "Europe/Rome" }) : "-");

async function main() {
  getAdminApp();
  const db = getFirestore();
  const since = new Date(Date.now() - days * 864e5);
  console.log(`Storico dal ${iso(since)} (ultimi ${days} giorni)\n`);

  const devices = await db.collection("devices").get();
  for (const dev of devices.docs) {
    const d = dev.data();
    console.log(`DEVICE ${dev.id}`);
    console.log(`  lastSeen: ${iso(toDate(d.lastSeen))}   sosActive: ${!!d.sosActive}`);

    // Posizioni: conteggio per giorno + ultime 8 (orario e precisione).
    const locs = await dev.ref.collection("locations").where("timestamp", ">=", since).get();
    const perDay = {};
    const accPerDay = {};
    const times = [];
    locs.docs.forEach((l) => {
      const t = toDate(l.data().timestamp);
      if (!t) return;
      times.push({ t, acc: l.data().accuracy });
      const k = t.toISOString().slice(0, 10);
      perDay[k] = (perDay[k] || 0) + 1;
      const acc = l.data().accuracy;
      if (acc != null) (accPerDay[k] = accPerDay[k] || []).push(acc);
    });
    times.sort((a, b) => b.t - a.t);
    console.log(`  posizioni per giorno: ${JSON.stringify(perDay)}`);
    // v0.4.0: precisione mediana (e minima) per giorno.
    Object.keys(accPerDay).sort().forEach((k) => {
      const a = accPerDay[k].slice().sort((x, y) => x - y);
      console.log(`    ${k}: precisione mediana ${Math.round(a[Math.floor(a.length / 2)])} m, migliore ${Math.round(a[0])} m`);
    });
    console.log("  ultime posizioni (ora italiana, precisione):");
    times.slice(0, 8).forEach((p) => console.log(`    ${iso(p.t)}  ${p.acc != null ? Math.round(p.acc) + " m" : "precisione ?"}`));

    // Eventi: orario, tipo, origine (niente coordinate/nomi).
    const evs = await dev.ref.collection("events").where("timestamp", ">=", since).get();
    const rows = evs.docs
      .map((e) => {
        const v = e.data();
        return { t: toDate(v.timestamp), type: v.type, source: v.source || "", acc: v.accuracy };
      })
      .filter((r) => r.t)
      .sort((a, b) => b.t - a.t);
    console.log(`  eventi (${rows.length}, piu' recenti per primi):`);
    rows.slice(0, 40).forEach((r) => console.log(`    ${iso(r.t)}  ${r.type}${r.source ? " (" + r.source + ")" : ""}${r.acc != null ? "  " + Math.round(r.acc) + " m" : ""}`));
    console.log("");
  }
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error("Diagnostica fallita:", err);
    process.exit(1);
  });
