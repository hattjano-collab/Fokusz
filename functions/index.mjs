import {initializeApp} from "firebase-admin/app";
import {getFirestore} from "firebase-admin/firestore";
import {HttpsError, onCall} from "firebase-functions/v2/https";
import {onSchedule} from "firebase-functions/v2/scheduler";
import {closeWeek, MAX_SECONDS, preview, settleOnce, weekAt} from "./weekly.mjs";

initializeApp();
const db = getFirestore();
const region = "europe-west1";

function validate(input) {
  if (!input || !Number.isInteger(input.wallet) || !Number.isInteger(input.bank) ||
      input.wallet < 0 || input.bank < 0 || input.wallet > MAX_SECONDS || input.bank > MAX_SECONDS ||
      typeof input.device !== "string" || !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(input.device) ||
      (input.team !== "" && (typeof input.team !== "string" || !/^[A-Za-z0-9]{12,40}$/.test(input.team))) ||
      (input.mode != null && (typeof input.expectedWeek !== "string" || !["safe", "risk"].includes(input.mode)))) {
    throw new HttpsError("invalid-argument", "Invalid weekly wallet.");
  }
}

export const syncWeeklyWallet = onCall({region, maxInstances: 12}, async request => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
  const uid = request.auth.uid;
  const input = request.data;
  validate(input);
  const profile = db.collection("weeklyWallets").doc(uid);
  const response = await db.runTransaction(async transaction => {
    const now = Date.now();
    const week = weekAt(now);
    if (input.mode && input.expectedWeek !== week.start) {
      throw new HttpsError("failed-precondition", "The requested week has ended.");
    }
    const period = db.collection("weeklyPeriods").doc(week.start);
    const entryRef = period.collection("entries").doc(uid);
    const [user, periodSnapshot, entry] = await Promise.all([
      transaction.get(profile), transaction.get(period), transaction.get(entryRef),
    ]);
    if (periodSnapshot.data()?.secret) throw new HttpsError("failed-precondition", "Week closed.");
    if (user.exists && user.data().device !== input.device) {
      throw new HttpsError("failed-precondition", "This wallet belongs to a different installation.");
    }
    let team = null;
    if (input.team) {
      const roster = await transaction.get(db.collection("teams").doc(input.team));
      if (Object.hasOwn(roster.data()?.members ?? {}, uid)) team = input.team;
    }
    const next = {
      wallet: input.wallet, bank: input.bank, team, device: input.device,
      mode: input.mode ?? entry.data()?.mode ?? "safe", submittedAt: now,
    };
    if (!periodSnapshot.exists) transaction.create(period, {deadline: week.deadline, complete: false});
    transaction.set(entryRef, next);
    transaction.set(profile, {device: input.device, week: week.start}, {merge: true});
    return {week, next, prior: user.data()?.week};
  });

  if (response.prior && response.prior !== response.week.start) {
    const period = await closeWeek(db, response.prior, Date.now());
    if (period) await settleOnce(db, period, uid);
  }
  const [teamEntries, history] = await Promise.all([
    response.next.team ? db.collection("weeklyPeriods").doc(response.week.start)
      .collection("entries").where("team", "==", response.next.team).get() : null,
    profile.collection("results").orderBy("week", "desc").limit(30).get(),
  ]);
  return {
    now: Date.now(), week: response.week.start, deadline: response.week.deadline,
    mode: response.next.mode, team: response.next.team,
    ...preview(response.next, teamEntries?.docs.map(doc => doc.data()) ?? []),
    history: history.docs.map(doc => doc.data()),
  };
});

export const settleWeeklyWallets = onSchedule({
  region, schedule: "0 0 * * 1", timeZone: "Europe/Budapest", timeoutSeconds: 540,
  retryCount: 5, maxInstances: 1,
}, async () => {
  const now = Date.now();
  const open = await db.collection("weeklyPeriods").where("complete", "==", false).get();
  for (const doc of open.docs) {
    const period = await closeWeek(db, doc.id, now);
    if (!period) continue;
    const participants = await period.collection("entries").listDocuments();
    for (const participant of participants) await settleOnce(db, period, participant.id);
    await period.update({complete: true});
  }
});
