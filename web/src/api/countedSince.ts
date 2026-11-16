/**
 * The note for a period of gains that the ledger's history doesn't reach back to the start of.
 * Gains can only be counted from [recordedSince], the history's first recorded time as the gains
 * route gives it, so when that's a minute or more after [periodStart] the note says so, with the
 * time to the minute in the viewer's own time zone: the same clock [periodStart] was worked out
 * on. Under a minute, the note would name the start itself ("Counted since 12:00 AM"), so there's
 * none. Null too when history reaches back to the start, or when there's none.
 */
export function countedSinceNote(recordedSince: string | null | undefined, periodStart: Date): string | null {
  if (!recordedSince) {
    return null;
  }
  const since = new Date(recordedSince);
  if (Number.isNaN(since.getTime()) || since.getTime() - periodStart.getTime() < 60_000) {
    return null;
  }
  const time = since.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
  return `Counted since ${time}, when the ledger began recording.`;
}
