import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import { countedSinceNote } from "../src/api/countedSince.ts";

const startingZone = process.env.TZ;
beforeEach(() => {
  process.env.TZ = "America/New_York";
});
afterEach(() => {
  process.env.TZ = startingZone;
});

// Midnight on 2026-11-16 in New York.
const midnight = new Date("2026-11-16T05:00:00Z");

/** The note with every kind of space as a plain one, since the time's "AM" may follow a narrow one. */
function note(recordedSince, periodStart = midnight) {
  return countedSinceNote(recordedSince, periodStart)?.replace(/\s/g, " ") ?? null;
}

test("there's no note without a recorded start", () => {
  assert.equal(note(null), null);
  assert.equal(note(undefined), null);
  assert.equal(note(""), null);
});

test("there's no note when the recorded start can't be read", () => {
  assert.equal(note("not a time"), null);
});

test("there's no note when history reaches back to the start of the period", () => {
  assert.equal(note("2026-11-15T20:00:00Z"), null);
  assert.equal(note("2026-11-16T05:00:00Z"), null);
});

test("there's no note when history starts less than a minute after the period", () => {
  assert.equal(note("2026-11-16T05:00:59.999Z"), null);
});

test("history starting a minute or more after the period gets a note", () => {
  assert.equal(note("2026-11-16T05:01:00Z"), "Counted since 12:01 AM, when the ledger began recording.");
});

test("the note names the time on the viewer's clock, to the minute", () => {
  assert.equal(note("2026-11-16T14:05:42Z"), "Counted since 9:05 AM, when the ledger began recording.");
  process.env.TZ = "America/Santiago";
  assert.equal(
    note("2026-11-16T14:05:42Z", new Date("2026-11-16T03:00:00Z")),
    "Counted since 11:05 AM, when the ledger began recording.",
  );
});
