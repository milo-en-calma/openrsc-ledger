import { useQuery } from "@tanstack/react-query";
import type { components } from "../../generated/openapi";
import { api } from "./client";
import { countedSinceNote } from "./countedSince";
import { type GainsPeriod, periodStart } from "./period";

export type SkillGain = components["schemas"]["SkillGainRow"];

/**
 * Experience gained over [period], every player's or only [player]'s, refreshed like the rest of
 * the pages, with [countedSinceNote]'s note when history began after the period's start. Staff
 * are left out unless [staff], as on the leaderboards. Nothing is fetched while [enabled] is false.
 */
export function useGains(
  period: GainsPeriod,
  { player, staff = false, enabled = true }: { player?: number; staff?: boolean; enabled?: boolean } = {},
) {
  return useQuery({
    queryKey: ["gains", period, player, staff],
    queryFn: async () => {
      // Worked out afresh on each fetch, so "today" rolls over at midnight.
      const start = periodStart(period);
      const from = start.toISOString();
      const { data, error } = await api.GET("/api/history/gains", {
        params: { query: player === undefined ? { from, staff } : { from, player, staff } },
      });
      if (error || !data) {
        throw new Error(error?.message ?? "The ledger didn't answer.");
      }
      return { gains: data.gains, countedSince: countedSinceNote(data.recordedSince, start) };
    },
    enabled,
    refetchInterval: 15_000,
  });
}
