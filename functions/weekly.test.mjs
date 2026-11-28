import test from "node:test";
import assert from "node:assert/strict";
import {daysAfter, drawFor, localMidnight, newSecret, preview, settlement, settleOnce, weekAt} from "./weekly.mjs";

const solo = {wallet: 60, bank: 359940, team: null};
const captain = {...solo, team: "team"};
const other = {...captain, bank: 12};

test("Budapest deadline is Sunday 23:59:59 inclusive and respects both DST changes", () => {
  for (const [monday, utc] of [
    ["2026-11-30", "2026-11-29T23:00:00.000Z"],
    ["2026-07-13", "2026-07-12T22:00:00.000Z"],
    ["2026-03-30", "2026-03-29T22:00:00.000Z"],
    ["2026-10-26", "2026-10-25T23:00:00.000Z"],
  ]) {
    const deadline = Date.parse(utc);
    assert.equal(localMidnight(monday), deadline);
    assert.deepEqual(weekAt(deadline - 1), {start: daysAfter(monday, -7), deadline});
    assert.equal(weekAt(deadline).start, monday);
  }
});

test("every first-place tie earns 30%, all other people earn 20%", () => {
  const tied = {wallet: 359900, bank: 100, team: "team"};
  const group = [captain, tied, other];
  assert.equal(preview(captain, group).percent, 30);
  assert.equal(preview(tied, group).percent, 30);
  assert.equal(preview(other, group).rank, 3);
  assert.equal(preview(other, group).percent, 20);
  assert.equal(preview(solo, []).percent, 20);
});

test("risk wins exactly 30 or 20 draws out of 100 and matches sure retention exactly", () => {
  for (const [entry, group, percent] of [
    [solo, [], 20],
    [captain, [captain, other], 30],
    [other, [captain, other], 20],
    [{wallet: 3, bank: 4, team: null}, [], 20],
    [{wallet: 2_000_000_000, bank: 2_000_000_000, team: "team"}, [], 30],
  ]) {
    const sure = settlement(entry, group, () => assert.fail("default does not draw"));
    assert.equal(sure.mode, "safe");
    assert.equal(sure.percent, percent);
    const rolls = Array.from({length: 100}, (_, i) => settlement({...entry, mode: "risk"}, group, () => i));
    assert.equal(rolls.filter(result => result.won).length, percent);
    assert.equal(rolls.reduce((n, result) => n + result.retainedTenths, 0), 100 * sure.retainedTenths);
  }
});

test("a seed and week produce one stable draw per user", () => {
  const secret = newSecret();
  assert.match(secret, /^[\da-f]{64}$/);
  const values = Array.from({length: 100}, (_, i) => drawFor(secret, "2026-11-30", "uid-" + i));
  assert.ok(values.every(value => Number.isInteger(value) && value >= 0 && value < 100));
  assert.equal(new Set(values).size > 30, true);
  assert.equal(values[0], drawFor(secret, "2026-11-30", "uid-0"));
});

test("retries and concurrent workers return the sealed receipt once", async () => {
  const docs = new Map([
    ["weeklyPeriods/2026-11-23", {secret: newSecret(), deadline: 1}],
    ["weeklyPeriods/2026-11-23/entries/a", {...solo, device: "one", mode: "risk"}],
  ]);
  function ref(path) {
    return {
      path, id: path.split("/").at(-1),
      collection: child => ref(path + "/" + child), doc: child => ref(path + "/" + child),
    };
  }
  let created = 0;
  let preceding = Promise.resolve();
  const db = {
    collection: ref,
    runTransaction(fn) {
      const result = preceding.then(() => fn({
        async get(ref) { return {exists: docs.has(ref.path), data: () => docs.get(ref.path)}; },
        create(ref, row) {
          assert.equal(docs.has(ref.path), false);
          docs.set(ref.path, row);
          created++;
        },
      }));
      preceding = result;
      return result;
    },
  };
  const period = ref("weeklyPeriods/2026-11-23");
  const results = await Promise.all(Array.from({length: 10}, () => settleOnce(db, period, "a")));
  assert.equal(created, 1);
  results.forEach(result => assert.deepEqual(result, results[0]));
});
