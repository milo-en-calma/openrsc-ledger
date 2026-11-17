// The day replay's real answers drawn as the board of experience gained today. The admin
// server's DayReplayTest saves them to the folder in DAY_REPLAY_PAGES, one file per replayed
// day, only once that day has passed every check; `:web:dayReplayPages` runs it, then this.
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { countedSinceNote } from "../../src/api/countedSince.ts";
import { periodStart } from "../../src/api/period.ts";
// Built from test/render.tsx by `npm run test:dayReplay` just before the tests run.
import { renderPage } from "../../build/render/render.js";

const folder = process.env.DAY_REPLAY_PAGES;
// The days DayReplayTest replays: an ordinary one, and the 25-hour day the clocks go back.
const days = ["2026-10-14", "2026-11-01"];

test("every replayed day left its answers, and nothing else is there", () => {
  assert.ok(folder, "DAY_REPLAY_PAGES names the replay's folder; run this as :web:dayReplayPages");
  assert.deepEqual(readdirSync(folder).sort(), days.map((day) => `${day}.json`));
});

/** The text inside a piece of HTML, tags removed and spaces evened out. */
function text(html) {
  return html.replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim();
}

/** Each row of the first table in [html]'s main part, as the text of its cells. */
function tableRows(html) {
  const main = html.match(/<main[\s>][\s\S]*?<\/main>/)[0];
  const body = main.match(/<tbody[\s>][\s\S]*?<\/tbody>/)?.[0] ?? "";
  return [...body.matchAll(/<tr[\s>][\s\S]*?<\/tr>/g)].map(([row]) => [...row.matchAll(/<td[\s>][\s\S]*?<\/td>/g)].map(([cell]) => text(cell)));
}

for (const day of folder ? days : []) {
  const { zone, moments } = JSON.parse(readFileSync(join(folder, `${day}.json`), "utf8"));

  for (const { now, from, answers, board } of moments) {
    test(`${day}: the board of experience gained today, drawn from the server's answers at ${now}`, async () => {
      // The viewer's clock is in the replay's zone, as the server's answers were asked for.
      process.env.TZ = zone;
      const start = periodStart("today", new Date(now));
      assert.equal(start.toISOString(), from, "the page's today starts at the midnight the replay asked from");

      // Each held as its page's query holds it once the fetch returns.
      const gains = { gains: answers.gains.gains, countedSince: countedSinceNote(answers.gains.recordedSince, start) };
      const html = await renderPage("/leaderboards/gained-today", {
        data: [
          [["health"], answers.health],
          [["leaderboards"], answers.leaderboards.boards],
          [["gains", "today", undefined, false], gains],
        ],
      });
      // The replay's history begins the evening before, so the whole day is counted.
      assert.doesNotMatch(text(html), /Counted since/);

      if (board.length === 0) {
        assert.ok(text(html).includes("No experience gained yet today."));
        return;
      }
      const rows = tableRows(html);
      assert.deepEqual(
        rows.map(([rank, name, experience, levels]) => [rank, name, experience, levels]),
        board.map((row, index) => [
          String(index + 1),
          row.name,
          `+${row.experienceGained.toLocaleString("en-US")}`,
          row.levelsGained > 0 ? `+${row.levelsGained}` : "–",
        ]),
      );
      rows.forEach(([, name, , , mostIn], index) => {
        assert.ok(board[index].mostIn.includes(mostIn), `${name}'s most gained skill: ${mostIn}, one of ${board[index].mostIn.join(", ")}`);
      });
    });
  }
}
