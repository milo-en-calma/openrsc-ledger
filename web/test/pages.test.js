import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
// Built from test/render.tsx by `npm test` just before the tests run.
import { drawPage, keyText, renderPage } from "../build/render/render.js";

// Made-up answers, keyed as the pages ask for them.
const aldren = { id: 1, name: "Aldren", combatLevel: 12, totalLevel: 40, lastLogin: null, online: true };
const bryn = { id: 2, name: "Bryn", combatLevel: 5, totalLevel: 22, lastLogin: "2026-01-02T10:00:00Z", online: false };
const holdings = (total) => ({ total, inInventories: total - 1, inBanks: 1 });
const noGains = { gains: [], countedSince: null };

const health = [["health"], { status: "ok", dataCopiedAt: "2026-01-02T10:00:00Z" }];
const players = [["players", ""], [aldren, bryn]];
const boards = [["leaderboards"], [{ key: "overall", name: "Overall" }, { key: "fishing", name: "Fishing" }]];
const overall = [
  ["leaderboard", "overall", false],
  {
    board: { key: "overall", name: "Overall" },
    entries: [{ rank: 1, playerId: 1, name: "Aldren", level: 40, experience: 5000, staff: false }],
  },
];
const gainedToday = [
  ["gains", "today", undefined, false],
  {
    gains: [
      { playerId: 2, name: "Bryn", skill: "fishing", skillName: "Fishing", experienceGained: 300, levelsGained: 1, experience: 900, level: 9, staff: false },
    ],
    countedSince: null,
  },
];
const playerPage = [
  ["player", 1],
  {
    player: aldren,
    questPoints: 3,
    skills: [{ skill: "fishing", name: "Fishing", level: 10, fullLevel: 10, experience: 1200 }],
    quests: [{ id: 0, name: "Cook's Assistant", stage: 1, complete: false }],
    equipment: [{ slot: 0, itemId: 70, name: "Bronze sword", amount: 1, noted: false, worn: true }],
    inventory: [{ slot: 0, itemId: 10, name: "Coins", amount: 25, noted: false, worn: false }],
    bank: [],
  },
];
const playerGains = [
  [["gains", "hour", 1, true], noGains],
  [["gains", "today", 1, true], noGains],
];
const economy = [
  ["economy"],
  { coins: holdings(500), items: [{ itemId: 10, name: "Coins", held: holdings(500), holders: 2 }] },
];
const itemHolders = [
  ["item-holders", 10],
  { itemId: 10, name: "Coins", held: holdings(500), holders: [{ playerId: 1, name: "Aldren", staff: false, held: holdings(500) }] },
];

/** The text inside a piece of HTML, tags removed and spaces evened out. */
function text(html) {
  return html.replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim();
}

/** The value of attribute [name] in a tag, or undefined. */
function attribute(tag, name) {
  return tag.match(new RegExp(`\\s${name}="([^"]*)"`))?.[1];
}

/**
 * Everything wrong with [html] for someone using a screen reader: not exactly one tab title or one
 * main heading, a table without a header row, an input nobody labelled, or a link with no text.
 */
function problems(html) {
  const found = [];
  const titles = [...html.matchAll(/<title>([\s\S]*?)<\/title>/g)];
  if (titles.length !== 1) {
    found.push(`${titles.length} tab titles`);
  }
  const headings = [...html.matchAll(/<h1[\s>][\s\S]*?<\/h1>/g)];
  if (headings.length !== 1) {
    found.push(`${headings.length} main headings`);
  }
  for (const [table] of html.matchAll(/<table[\s>][\s\S]*?<\/table>/g)) {
    if (!/<thead[\s>][\s\S]*?<th[\s>][\s\S]*?<\/thead>/.test(table)) {
      found.push(`a table with no header row: ${text(table).slice(0, 60)}`);
    }
  }
  for (const [input] of html.matchAll(/<input\b[^>]*>/g)) {
    if (attribute(input, "type") === "hidden" || attribute(input, "aria-label")?.trim()) {
      continue;
    }
    const id = attribute(input, "id");
    const labels = id ? [...html.matchAll(new RegExp(`<label[^>]*\\sfor="${id}"[^>]*>([\\s\\S]*?)</label>`, "g"))] : [];
    if (!labels.some(([, inside]) => text(inside))) {
      found.push(`an input with no label: ${input}`);
    }
  }
  for (const [link, attributes, inside] of html.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/g)) {
    if (!text(inside) && !attribute(attributes, "aria-label")?.trim()) {
      found.push(`a link with no text: ${link}`);
    }
  }
  return found;
}

/** The page's tab title and main heading, as text. */
function names(html) {
  return {
    title: text(html.match(/<title>([\s\S]*?)<\/title>/)?.[1] ?? ""),
    heading: text(html.match(/<h1[\s>][\s\S]*?<\/h1>/)?.[0] ?? ""),
  };
}

// Each page as it looks once loaded, and what its tab and main heading should say.
const loaded = [
  { path: "/", data: [health, players], name: "Players" },
  { path: "/leaderboards", data: [health, boards, overall], name: "Leaderboards" },
  { path: "/leaderboards/gained-today", data: [health, boards, gainedToday], name: "Leaderboards" },
  { path: "/players/1", data: [health, playerPage, ...playerGains], name: "Aldren" },
  { path: "/economy", data: [health, economy], name: "Economy" },
  { path: "/items/10", data: [health, itemHolders], name: "Coins" },
];

/**
 * What's wrong between the keys a seed holds and the keys the page asked for: a key it asked
 * for but wasn't given (when [allAsked] is set, as for a loaded page), or one it was given but
 * never asked for. Without this a renamed key leaves a "loaded" page drawing a loader unseen,
 * since every page names itself while loading.
 */
function keyProblems(asked, seededKeys, allAsked) {
  const seeded = seededKeys.map(keyText);
  return [
    ...(allAsked ? asked.filter((key) => !seeded.includes(key)).map((key) => `asked for ${key} but was given no answer`) : []),
    ...seeded.filter((key) => !asked.includes(key)).map((key) => `was given ${key} but never asked for it`),
  ];
}

for (const { path, data, name } of loaded) {
  test(`${path} has one tab title and one main heading, naming the page, and nothing a screen reader would miss`, async () => {
    const html = await renderPage(path, { data });
    assert.deepEqual(problems(html), []);
    assert.deepEqual(names(html), { title: `${name} · openrsc-ledger`, heading: name });
  });

  test(`${path} asks for exactly the answers it is given, so it really draws as loaded`, async () => {
    const { asked } = await drawPage(path, { data });
    assert.deepEqual(keyProblems(asked, data.map(([key]) => key), true), []);
  });
}

// The routes App.tsx declares, as patterns, each needing a loaded page above.
const routes = [...readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8").matchAll(/<Route\s+path="([^"]*)"/g)]
  .map(([, route]) => route)
  .filter((route) => route !== "*");

test("App.tsx declares its routes where this test can read them", () => {
  assert.ok(routes.length >= 6, `found only ${routes.length} routes: ${routes.join(", ")}`);
});

for (const route of routes) {
  test(`route ${route} is drawn loaded at least once`, () => {
    const pattern = new RegExp(`^${route.replace(/:[^/]+/g, "[^/]+")}$`);
    assert.ok(
      loaded.some(({ path }) => pattern.test(path)),
      `no loaded row draws ${route}`,
    );
  });
}

// Pages still loading, failed or refused are named from the address.
const unfinished = [
  { path: "/players/12", seed: {}, name: "Player 12" },
  { path: "/players/12", seed: { failed: [[["player", 12], "No player 12."]] }, name: "Player 12" },
  // Refused at once: nothing is seeded, so a page that asked the server would still be loading.
  { path: "/players/abc", seed: {}, name: "Player abc", refused: "no player with that number." },
  // Numbers written other than as plain digits are refused too, never read as another player.
  { path: "/players/0x10", seed: {}, name: "Player 0x10", refused: "no player with that number." },
  { path: "/players/1e3", seed: {}, name: "Player 1e3", refused: "no player with that number." },
  { path: "/players/2147483648", seed: {}, name: "Player 2147483648", refused: "no player with that number." },
  { path: "/items/12", seed: {}, name: "Item 12" },
  { path: "/items/12", seed: { failed: [[["item-holders", 12], "No item 12."]] }, name: "Item 12" },
  { path: "/items/abc", seed: {}, name: "Item abc", refused: "no item with that number." },
  { path: "/items/%20", seed: {}, name: "Item", refused: "no item with that number." },
  { path: "/items/012", seed: {}, name: "Item 012", refused: "no item with that number." },
  { path: "/economy", seed: {}, name: "Economy" },
  { path: "/economy", seed: { failed: [[["economy"], "No economy yet."]] }, name: "Economy" },
];

for (const { path, seed, name, refused } of unfinished) {
  const state = refused ? "refused at once" : seed.failed ? "that failed to load" : "still loading";
  test(`${path} ${state} is named "${name}" in its tab and main heading`, async () => {
    const { html, asked } = await drawPage(path, seed);
    assert.deepEqual(problems(html), []);
    // What it was given is what it asked for, so a failed state comes from the page's own query.
    const seeded = [...(seed.data ?? []), ...(seed.failed ?? [])].map(([key]) => key);
    assert.deepEqual(keyProblems(asked, seeded, false), []);
    assert.deepEqual(names(html), { title: `${name} · openrsc-ledger`, heading: name });
    // The failure's message is shown, so the page really drew as failed and not as loading.
    if (seed.failed) {
      assert.ok(html.includes(seed.failed[0][1]), `"${seed.failed[0][1]}" is shown`);
    }
    // The refusal is shown in place of a loader. (Drawn HTML escapes the apostrophe in "There's".)
    if (refused) {
      assert.ok(html.includes(refused), `"${refused}" is shown`);
    }
  });
}

/** Each link in [html] as its address and text, in order. */
function links(html) {
  return [...html.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/g)].map(([, attributes, inside]) => [attribute(attributes, "href"), text(inside)]);
}

// The links every unknown address offers under the header, after any likely page.
const pageLinks = [
  ["/", "Players"],
  ["/leaderboards", "Leaderboards"],
  ["/economy", "Economy"],
];

// Addresses no page claims, and the page each is likely meant to be, if any.
const unknown = [
  { path: "/nonsense", meant: undefined },
  { path: "/players/12/extra", meant: undefined },
  { path: "/players", meant: ["/", "Players"] },
  { path: "/player/12", meant: ["/players/12", "/players/12"] },
  { path: "/leaderboard", meant: ["/leaderboards", "/leaderboards"] },
  { path: "/leaderboard/fishing", meant: ["/leaderboards/fishing", "/leaderboards/fishing"] },
  { path: "/item/5", meant: ["/items/5", "/items/5"] },
];

for (const { path, meant } of unknown) {
  const offers = meant ? `offers ${meant[0]}` : "offers no likely page";
  test(`${path} is named "Page not found", says so, and ${offers}`, async () => {
    const html = await renderPage(path, { data: [health] });
    assert.deepEqual(problems(html), []);
    assert.deepEqual(names(html), { title: "Page not found · openrsc-ledger", heading: "Page not found" });
    assert.ok(text(html).includes(`There is no page at ${path}.`), `the page names ${path}`);
    const main = html.match(/<main[\s>][\s\S]*?<\/main>/)[0];
    assert.deepEqual(links(main), meant ? [meant, ...pageLinks] : pageLinks);
  });
}

test("the header's app name is plain text, not a heading", async () => {
  const html = await renderPage("/", { data: [health, players] });
  const header = html.match(/<header[\s>][\s\S]*?<\/header>/)[0];
  assert.match(header, /openrsc-ledger/);
  assert.doesNotMatch(header, /<h[1-6][\s>]/);
});

// The checks themselves must catch what they're for, or a passing page proves nothing.
test("the checks catch a missing heading, a table without a header row, an unlabelled input and an empty link", () => {
  const html =
    "<title>T</title><table><tbody><tr><td>1</td></tr></tbody></table>" +
    '<input id="a" value=""/><label for="b">B</label><input id="b"/><a href="/x"><svg></svg></a>';
  assert.deepEqual(
    problems(html).map((problem) => problem.split(":")[0]),
    ["0 main headings", "a table with no header row", "an input with no label", "a link with no text"],
  );
});
