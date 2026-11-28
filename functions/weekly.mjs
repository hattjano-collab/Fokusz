import {createHmac, randomBytes} from "node:crypto";

const zone = "Europe/Budapest";
const dayFormatter = new Intl.DateTimeFormat("sv-SE", {
  timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit",
});
const localClock = new Intl.DateTimeFormat("sv-SE", {
  timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
});
const dayMs = 86_400_000;
export const MAX_SECONDS = 2_000_000_000;
export const NEXT_DAILY_SECONDS = 1800;

function midnightUtcDate(date) {
  return Date.parse(date + "T00:00:00.000Z");
}

export function daysAfter(date, days) {
  return new Date(midnightUtcDate(date) + days * dayMs).toISOString().slice(0, 10);
}

export function localMidnight(date) {
  // Budapest offset at the requested local midnight, including summer time.
  const target = midnightUtcDate(date);
  const formatAt = (instant) => Date.parse(localClock.format(new Date(instant)).replace(" ", "T") + "Z");
  let guess = target;
  for (let pass = 0; pass < 3; pass++) {
    guess += target - formatAt(guess);
  }
  return guess;
}

export function weekAt(serverMs) {
  const day = dayFormatter.format(new Date(serverMs));
  const weekday = new Date(midnightUtcDate(day)).getUTCDay();
  const start = daysAfter(day, -((weekday + 6) % 7));
  return {start, deadline: localMidnight(daysAfter(start, 7))};
}

export function total(entry) {
  return entry.wallet + entry.bank;
}

export function preview(entry, team) {
  const closing = total(entry);
  const rank = entry.team ? 1 + team.filter(other => total(other) > closing).length : null;
  const percent = rank === 1 ? 30 : 20;
  // Amounts in one tenth of a second. There is no floating-point truncation of 20% or 30%.
  return {rank, percent, closing, sureTenths: closing * (percent / 10)};
}

export function settlement(entry, team, draw) {
  const values = preview(entry, team);
  const mode = entry.mode === "risk" ? "risk" : "safe";
  const roll = mode === "risk" ? draw() : null;
  if (roll !== null && (!Number.isInteger(roll) || roll < 0 || roll >= 100)) throw new RangeError("roll");
  const won = roll === null ? null : roll < values.percent;
  const retainedTenths = mode === "safe" ? values.sureTenths : won ? values.closing * 10 : 0;
  const deposit = Math.min(Math.floor(retainedTenths / 10), MAX_SECONDS);
  const mondayInterest = Math.min(Math.floor(deposit / 10), MAX_SECONDS - deposit);
  return {...values, mode, won, retainedTenths,
    openingTenths: retainedTenths + (NEXT_DAILY_SECONDS + mondayInterest) * 10};

}

export function newSecret() {
  return randomBytes(32).toString("hex");
}

export function drawFor(secret, week, uid) {
  // Rejection sampling makes all 100 integers equiprobable.
  const cutoff = 4_294_967_200;
  for (let block = 0; ; block++) {
    const bytes = createHmac("sha256", Buffer.from(secret, "hex"))
      .update(JSON.stringify([week, uid, block])).digest();
    for (let offset = 0; offset < bytes.length; offset += 4) {
      const value = bytes.readUInt32BE(offset);
      if (value < cutoff) return value % 100;
    }
  }
}

export async function settleOnce(db, period, uid) {
  const wallet = db.collection("weeklyWallets").doc(uid);
  const result = wallet.collection("results").doc(period.id);
  const entries = period.collection("entries");
  return db.runTransaction(async transaction => {
    const stored = await transaction.get(result);
    if (stored.exists) return stored.data();
    const marker = await transaction.get(period);
    if (!marker.exists || !marker.data().secret) return null;
    const row = await transaction.get(entries.doc(uid));
    if (!row.exists) return null;
    const entry = row.data();
    const peers = entry.team
      ? (await transaction.get(entries.where("team", "==", entry.team))).docs.map(doc => doc.data())
      : [];
    const settled = {
      week: period.id, device: entry.device, deadline: marker.data().deadline,
      ...settlement(entry, peers, () => drawFor(marker.data().secret, period.id, uid)),
    };
    transaction.create(result, settled);
    return settled;
  });
}

export async function closeWeek(db, id, now) {
  const deadline = localMidnight(daysAfter(id, 7));
  if (now < deadline) return null;
  const period = db.collection("weeklyPeriods").doc(id);
  await db.runTransaction(async transaction => {
    const prior = await transaction.get(period);
    if (prior.exists && !prior.data().secret) {
      transaction.update(period, {secret: newSecret(), closedAt: now});
    }
  });
  return period;
}
