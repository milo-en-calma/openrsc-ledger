import assert from "node:assert/strict";
import { test } from "node:test";
import { gainedBySkill, groupItems, lastSeenOrder, totalPerPlayer } from "../src/pages/tally.ts";

/** One skill's gain for one player, as the gains route gives it. */
function gain(playerId, name, skill, experienceGained, { levelsGained = 0, staff = false } = {}) {
  const skillName = skill[0].toUpperCase() + skill.slice(1);
  return { playerId, name, staff, skill, skillName, experienceGained, levelsGained };
}

test("each player's gains are added up across skills, with the skill they gained most in", () => {
  const rows = totalPerPlayer([
    gain(1, "Aldren", "fishing", 300, { levelsGained: 1 }),
    gain(1, "Aldren", "cooking", 500, { levelsGained: 2 }),
    gain(1, "Aldren", "firemaking", 100),
  ]);
  assert.deepEqual(rows, [
    { playerId: 1, name: "Aldren", staff: false, experienceGained: 900, levelsGained: 3, topSkill: "Cooking", topGain: 500 },
  ]);
});

test("players are ranked by experience gained, and ties by name whatever its case", () => {
  const rows = totalPerPlayer([
    gain(1, "corwin", "mining", 200),
    gain(2, "Bryn", "mining", 200),
    gain(3, "Aldren", "mining", 50),
    gain(4, "Dessa", "mining", 900),
  ]);
  assert.deepEqual(rows.map((row) => row.name), ["Dessa", "Bryn", "corwin", "Aldren"]);
});

test("between two skills with the same gain, the first one listed stays the top skill", () => {
  const [row] = totalPerPlayer([gain(1, "Aldren", "fishing", 400), gain(1, "Aldren", "cooking", 400)]);
  assert.equal(row.topSkill, "Fishing");
});

test("players who gained no experience are left off", () => {
  const rows = totalPerPlayer([gain(1, "Aldren", "fishing", 0, { levelsGained: 1 }), gain(2, "Bryn", "fishing", 10)]);
  assert.deepEqual(rows.map((row) => row.name), ["Bryn"]);
});

test("a player with no name is shown by number, and staff stay marked", () => {
  const [row] = totalPerPlayer([gain(7, null, "magic", 25, { staff: true })]);
  assert.equal(row.name, "Player 7");
  assert.equal(row.staff, true);
});

test("no gains make an empty board", () => {
  assert.deepEqual(totalPerPlayer([]), []);
});

test("a player's gains are keyed by skill", () => {
  const bySkill = gainedBySkill([gain(1, "Aldren", "fishing", 300), gain(1, "Aldren", "cooking", 5)]);
  assert.deepEqual([...bySkill], [["fishing", 300], ["cooking", 5]]);
  assert.deepEqual([...gainedBySkill(undefined)], []);
});

/** One slot's item. */
function item(itemId, amount, { noted = false, worn = false } = {}) {
  return { itemId, name: `Item ${itemId}`, amount, noted, worn };
}

test("the same item across slots is one row, with its amounts and slots added up", () => {
  const groups = groupItems([item(14, 1), item(14, 1), item(14, 1)]);
  assert.deepEqual(groups.map(({ item, amount, slots }) => [item.itemId, amount, slots]), [[14, 3, 3]]);
});

test("noted and worn items are rows of their own, in the order each kind first appears", () => {
  const groups = groupItems([
    item(20, 1),
    item(14, 50, { noted: true }),
    item(14, 1),
    item(20, 1, { worn: true }),
    item(14, 1),
  ]);
  assert.deepEqual(
    groups.map(({ item, amount, slots }) => [item.itemId, item.noted, item.worn, amount, slots]),
    [
      [20, false, false, 1, 1],
      [14, true, false, 50, 1],
      [14, false, false, 2, 2],
      [20, false, true, 1, 1],
    ],
  );
});

test("players online now sort above everyone, then by when they last logged in", () => {
  const online = lastSeenOrder({ online: true, lastLogin: "2020-01-01T00:00:00Z" });
  const recent = lastSeenOrder({ online: false, lastLogin: "2026-11-16T12:00:00Z" });
  const older = lastSeenOrder({ online: false, lastLogin: "2026-11-01T12:00:00Z" });
  const never = lastSeenOrder({ online: false, lastLogin: null });
  assert.ok(online > recent && recent > older && older > never);
  assert.equal(never, 0);
});
