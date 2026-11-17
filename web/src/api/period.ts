/** How far back a gain is counted from: the last hour, or since midnight in the viewer's time zone. */
export type GainsPeriod = "hour" | "today";

/**
 * The start of [period] as of [now]. "today" starts at midnight on the viewer's clock, or, on a
 * day the clocks skip midnight (Santiago, early September), at the first time the day has.
 */
export function periodStart(period: GainsPeriod, now: Date = new Date()): Date {
  if (period === "hour") {
    return new Date(now.getTime() - 60 * 60 * 1000);
  }
  const midnight = new Date(now);
  midnight.setHours(0, 0, 0, 0);
  return midnight;
}
