// The pages' sums and orderings. Only types are imported, so `node --test` can load this file as it is.
import type { components } from "../../generated/openapi";

type SkillGain = components["schemas"]["SkillGainRow"];
type Item = components["schemas"]["ItemRow"];
type Player = components["schemas"]["PlayerListItem"];

/** One player's row on the board of experience gained today. */
export type DayGains = {
  playerId: number;
  name: string;
  staff: boolean;
  experienceGained: number;
  levelsGained: number;
  topSkill: string;
};

/**
 * Adds up each player's gains across every skill, with the skill they gained most in,
 * ranked by experience gained, ties by name. Players who gained no experience are left
 * off: a level can change with none gained only when the database is edited by hand.
 */
export function totalPerPlayer(gains: SkillGain[]): DayGains[] {
  const players = new Map<number, DayGains & { topGain: number }>();
  for (const gain of gains) {
    const player = players.get(gain.playerId);
    if (!player) {
      players.set(gain.playerId, {
        playerId: gain.playerId,
        name: gain.name ?? `Player ${gain.playerId}`,
        staff: gain.staff,
        experienceGained: gain.experienceGained,
        levelsGained: gain.levelsGained,
        topSkill: gain.skillName,
        topGain: gain.experienceGained,
      });
      continue;
    }
    player.experienceGained += gain.experienceGained;
    player.levelsGained += gain.levelsGained;
    if (gain.experienceGained > player.topGain) {
      player.topSkill = gain.skillName;
      player.topGain = gain.experienceGained;
    }
  }
  return [...players.values()].filter((player) => player.experienceGained > 0).sort(
    (a, b) => b.experienceGained - a.experienceGained || a.name.localeCompare(b.name, undefined, { sensitivity: "base" }),
  );
}

/** Experience gained per skill key, from one period's gains. */
export function gainedBySkill(gains: SkillGain[] | undefined): Map<string, number> {
  return new Map((gains ?? []).map((gain) => [gain.skill, gain.experienceGained]));
}

/** Items of one kind in one list: the same item, noted or not, worn or not, across however many slots. */
export type ItemGroup = { item: Item; amount: number; slots: number };

/**
 * Gathers [items] into one row per kind, in the order each kind first appears, adding up
 * the amounts. The game keeps each unstackable item in a slot of its own, so 22 logs would
 * otherwise be 22 rows.
 */
export function groupItems(items: Item[]): ItemGroup[] {
  const groups = new Map<string, ItemGroup>();
  for (const item of items) {
    const key = `${item.itemId}/${item.noted}/${item.worn}`;
    const group = groups.get(key);
    if (group) {
      group.amount += item.amount;
      group.slots += 1;
    } else {
      groups.set(key, { item, amount: item.amount, slots: 1 });
    }
  }
  return [...groups.values()];
}

/** Sorts players online now above everyone, then by when they last logged in. */
export function lastSeenOrder(player: Player): number {
  if (player.online) {
    return Number.MAX_SAFE_INTEGER;
  }
  return player.lastLogin ? Date.parse(player.lastLogin) : 0;
}
