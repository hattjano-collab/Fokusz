import assert from "node:assert/strict";
import {initializeApp} from "firebase-admin/app";
import {getFirestore} from "firebase-admin/firestore";
import {daysAfter, localMidnight, weekAt} from "./weekly.mjs";

const project = "demo-fokusz";
assert.match(process.env.FIRESTORE_EMULATOR_HOST ?? "", /^(localhost|127\.0\.0\.1):\d+$/);
assert.match(process.env.FIREBASE_AUTH_EMULATOR_HOST ?? "", /^(localhost|127\.0\.0\.1):\d+$/);
const auth = process.env.FIREBASE_AUTH_EMULATOR_HOST;
const signup = async () => {
  const response = await fetch("http://" + auth + "/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo", {
    method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({returnSecureToken:true}),
  });
  assert.equal(response.status, 200);
  return response.json();
};

initializeApp({projectId: project});
const db = getFirestore();
const one = await signup(), two = await signup();
const week = weekAt(Date.now());
const prior = daysAfter(week.start, -7);
const pastCutoff = localMidnight(week.start);
const device = "73ab3502-3762-4125-823b-f6d38cbb3ad6";
const group = "FokuszTeamTesting01";
const period = db.collection("weeklyPeriods").doc(prior);
await period.set({deadline:pastCutoff, complete:false});
for (const player of [one, two]) {
  await period.collection("entries").doc(player.localId).set({
    wallet:60, bank:359940, team:group, device, mode:player === one ? "risk" : "safe",
  });
  await db.collection("weeklyWallets").doc(player.localId).set({device, week:prior});
}
await db.collection("teams").doc(group).set({members:{[one.localId]:{},[two.localId]:{}}});

const endpoint = "http://127.0.0.1:5001/demo-fokusz/europe-west1/syncWeeklyWallet";
const request = async (token, data) => fetch(endpoint, {
  method:"POST", headers:{"Content-Type":"application/json", ...(token ? {Authorization:"Bearer " + token}: {})},
  body:JSON.stringify({data}),
});
const payload = {wallet:1800, bank:0, device, team:group};
const privatePeriod = "http://" + process.env.FIRESTORE_EMULATOR_HOST +
  "/v1/projects/demo-fokusz/databases/(default)/documents/weeklyPeriods/" + prior;
const direct = await fetch(privatePeriod, {headers:{Authorization:"Bearer " + one.idToken}});
assert.equal(direct.status,403);

assert.equal((await request(null,payload)).status,401);
assert.notEqual((await request(one.idToken,{...payload, mode:"risk", expectedWeek:prior})).status,200);
const first = await request(one.idToken,payload);
assert.equal(first.status,200);
const firstReceipt = (await first.json()).result.history[0];
assert.equal(firstReceipt.week,prior);
assert.equal(firstReceipt.rank,1);
assert.equal(firstReceipt.percent,30);
assert.ok([0,3600000].includes(firstReceipt.retainedTenths));

const results = await Promise.all(Array.from({length:3}, () => request(one.idToken,payload)));
for (const response of results) {
  assert.equal(response.status,200);
  const data = (await response.json()).result;
  assert.equal(data.mode,"safe");
  assert.deepEqual(data.history[0],firstReceipt);
}
assert.equal((await db.collection("weeklyWallets").doc(one.localId).collection("results").get()).size,1);
const second = (await (await request(two.idToken,payload)).json()).result.history[0];
assert.equal(second.percent,30);
assert.equal(second.retainedTenths,1080000);
assert.equal(second.openingTenths,1206000);
const change = await request(two.idToken,{...payload, mode:"risk", expectedWeek:week.start});
assert.equal((await change.json()).result.mode,"risk");
// The in-flight request can change the next choice, but the closed week is sealed.
const stored = (await db.collection("weeklyWallets").doc(two.localId).collection("results").doc(prior).get()).data();
assert.deepEqual(stored,second);
console.log("PASS authenticated callable, stale cutoff rejection, tied team leads, default safe, mode update and stable receipts");
