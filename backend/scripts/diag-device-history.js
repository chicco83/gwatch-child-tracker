#!/usr/bin/env node
/**
 * Diagnostica di SOLA LETTURA dello storico dei dispositivi.
 * Versione: 0.8.1 (2026-09-23)
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
 * - 0.5.0 (2026-09-23): opzione --all, elenca tutte le posizioni del
 *   periodo (orario, precisione, activity), per ricostruire un tragitto
 *   senza mostrare coordinate.
 * - 0.6.0 (2026-09-23): opzione --track (implica --all): per ogni
 *   posizione la distanza dalla precedente, dalla prima del periodo e la
 *   velocita' media dal punto precedente — per capire se i punti
 *   descrivono un tragitto vero o la stessa posizione ripetuta. Le
 *   coordinate servono solo al calcolo e non vengono stampate.
 * - 0.7.0 (2026-09-23): opzione --hourly, riepilogo per ora: numero di
 *   punti, quanti "still"/"moving", distanza min-max dal primo punto del
 *   periodo. E' il confronto che ha mostrato il tracking continuo fino al
 *   20/9 sera (~10 punti/ora anche di notte) e quasi nullo dopo.
 * - 0.8.0 (2026-09-23): stampa lo stato corrente del device (batteria,
 *   temperatura, carica, lastStatusAt, gnss, batteryAlertLevel) —
 *   niente coordinate.
 * - 0.8.1 (2026-09-23): gnss.active=false stampato come "GPS non usato"
 *   invece di "null visti, null agganciati".
 * Sicurezza: nessuna scrittura (solo get()).
 *
 * Uso (stesse credenziali del backend, gia' nell'ambiente):
 *   node backend/scripts/diag-device-history.js [giorni=8] [--all] [--track] [--hourly]
 */
const path = require("path");
module.paths.unshift(path.join(__dirname, "..", "node_modules"));
const { getAdminApp } = require("../api/_lib/firebase-admin");
const { getFirestore } = require("firebase-admin/firestore");

const days = Number(process.argv.slice(2).find((a) => !a.startsWith("--"))) || 8;
// v0.5.0
// v0.7.0: --hourly usa le stesse distanze di --track.
const hourly = process.argv.includes("--hourly");
const track = hourly || process.argv.includes("--track");
const listAll = track || process.argv.includes("--all");

// v0.6.0: distanza in metri tra due punti (formula dell'emisenoverso).
function distanceM(a, b) {
  const R = 6371000;
  const toRad = (x) => (x * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLon = toRad(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

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
    // v0.8.0: stato corrente, niente coordinate.
    console.log(
      `  batteria: ${d.battery ?? "-"}%  temp: ${d.batteryTemp ?? "-"}  carica: ${d.charging ?? "-"}  ` +
        `alertLevel: ${d.batteryAlertLevel ?? "-"}  lastStatusAt: ${iso(toDate(d.lastStatusAt))}`,
    );
    // v0.8.1. Precedente: sempre "${d.gnss.visible} visti, ${d.gnss.used} agganciati".
    const gnssText = !d.gnss
      ? "-"
      : d.gnss.active === false
        ? `GPS non usato (posizione da Wi-Fi/rete), ${iso(toDate(d.gnss.at))}`
        : `${d.gnss.visible} visti, ${d.gnss.used} agganciati, ${iso(toDate(d.gnss.at))}`;
    console.log(`  gnss: ${gnssText}`);

    // Posizioni: conteggio per giorno + ultime 8 (orario e precisione).
    const locs = await dev.ref.collection("locations").where("timestamp", ">=", since).get();
    const perDay = {};
    const accPerDay = {};
    const times = [];
    locs.docs.forEach((l) => {
      const t = toDate(l.data().timestamp);
      if (!t) return;
      times.push({ t, acc: l.data().accuracy, activity: l.data().activity, lat: l.data().lat, lon: l.data().lon });
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
    // v0.6.0: con --track, distanze calcolate in ordine cronologico.
    if (track) {
      const chrono = times.slice().reverse();
      chrono.forEach((p, i) => {
        if (i === 0 || p.lat == null) return;
        const prev = chrono[i - 1];
        p.fromPrev = distanceM(prev, p);
        p.fromFirst = distanceM(chrono[0], p);
        const dtS = (p.t - prev.t) / 1000;
        p.kmh = dtS > 0 ? (p.fromPrev / dtS) * 3.6 : null;
      });
    }
    const trackInfo = (p) =>
      track && p.fromPrev != null
        ? `  | dal prec. ${Math.round(p.fromPrev)} m, dalla prima ${Math.round(p.fromFirst)} m${p.kmh != null ? ", " + p.kmh.toFixed(1) + " km/h" : ""}`
        : "";
    // v0.7.0: riepilogo orario (ora italiana).
    if (hourly) {
      const hours = new Map();
      times.slice().reverse().forEach((p) => {
        const key = iso(p.t).slice(0, 13) + ":00";
        const h = hours.get(key) || { n: 0, still: 0, moving: 0, min: null, max: null };
        h.n++;
        if (p.activity === "still") h.still++;
        if (p.activity === "moving") h.moving++;
        const d = p.fromFirst ?? 0;
        h.min = h.min == null ? d : Math.min(h.min, d);
        h.max = h.max == null ? d : Math.max(h.max, d);
        hours.set(key, h);
      });
      console.log("  riepilogo orario (punti, fermo/movimento, distanza dal primo punto):");
      hours.forEach((h, k) =>
        console.log(`    ${k}  ${String(h.n).padStart(3)} punti  fermo ${h.still} / mov. ${h.moving}  ${Math.round(h.min)}-${Math.round(h.max)} m`),
      );
      console.log("");
      return;
    }
    (listAll ? times : times.slice(0, 8)).forEach((p) => console.log(`    ${iso(p.t)}  ${p.acc != null ? Math.round(p.acc) + " m" : "precisione ?"}${p.activity ? "  " + p.activity : ""}${trackInfo(p)}`));

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
    (listAll ? rows : rows.slice(0, 40)).forEach((r) => console.log(`    ${iso(r.t)}  ${r.type}${r.source ? " (" + r.source + ")" : ""}${r.acc != null ? "  " + Math.round(r.acc) + " m" : ""}`));
    console.log("");
  }
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error("Diagnostica fallita:", err);
    process.exit(1);
  });
