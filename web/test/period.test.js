import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { periodStart } from "../src/api/period.ts";

const startingZone = process.env.TZ;
afterEach(() => {
  process.env.TZ = startingZone;
});

/** The start of [period] at [at] (an ISO time) on a viewer's clock in [zone], as an ISO time. */
function startIn(zone, period, at) {
  process.env.TZ = zone;
  return periodStart(period, new Date(at)).toISOString();
}

test("the last hour starts exactly an hour earlier", () => {
  assert.equal(startIn("America/New_York", "hour", "2026-11-16T14:05:00Z"), "2026-11-16T13:05:00.000Z");
});

test("the last hour is sixty minutes even across the hour the clocks go back", () => {
  // 01:30 the second time round in New York; an hour earlier is 01:30 the first time round.
  assert.equal(startIn("America/New_York", "hour", "2026-11-01T06:30:00Z"), "2026-11-01T05:30:00.000Z");
});

test("today starts at midnight on the viewer's clock", () => {
  assert.equal(startIn("America/New_York", "today", "2026-11-16T14:05:00Z"), "2026-11-16T05:00:00.000Z");
  // 23:05 in Tokyo, whose midnight was 15:00 UTC the day before.
  assert.equal(startIn("Asia/Tokyo", "today", "2026-11-16T14:05:00Z"), "2026-11-15T15:00:00.000Z");
});

test("just before midnight it's still the day before", () => {
  assert.equal(startIn("America/New_York", "today", "2026-11-16T04:59:59Z"), "2026-11-15T05:00:00.000Z");
  assert.equal(startIn("America/New_York", "today", "2026-11-16T05:00:00Z"), "2026-11-16T05:00:00.000Z");
});

test("the day the clocks go forward in New York starts at midnight before the change", () => {
  // 2026-03-08: 2:00 becomes 3:00, so midnight is still five hours behind UTC.
  assert.equal(startIn("America/New_York", "today", "2026-03-08T15:00:00Z"), "2026-03-08T05:00:00.000Z");
});

test("the day the clocks go back in New York starts at midnight before the change, and lasts 25 hours", () => {
  // 2026-11-01: 2:00 becomes 1:00, so midnight is still four hours behind UTC.
  const start = startIn("America/New_York", "today", "2026-11-01T15:00:00Z");
  assert.equal(start, "2026-11-01T04:00:00.000Z");
  const next = startIn("America/New_York", "today", "2026-11-02T15:00:00Z");
  assert.equal(Date.parse(next) - Date.parse(start), 25 * 60 * 60 * 1000);
});

test("the day Santiago's clocks skip midnight starts at 1:00", () => {
  // 2026-09-06: midnight becomes 1:00, so the day's first time is 1:00, three hours behind UTC.
  assert.equal(startIn("America/Santiago", "today", "2026-09-06T15:00:00Z"), "2026-09-06T04:00:00.000Z");
});

test("working out the start leaves the time it was given unchanged", () => {
  process.env.TZ = "America/New_York";
  const now = new Date("2026-11-16T14:05:00Z");
  periodStart("today", now);
  assert.equal(now.toISOString(), "2026-11-16T14:05:00.000Z");
});
