import { useQuery } from "@tanstack/react-query";
import type { components } from "../../generated/openapi";
import { api } from "./client";

export type SkillGain = components["schemas"]["SkillGainRow"];

/** How far back a gain is counted from: the last hour, or since midnight in the viewer's time zone. */
export type GainsPeriod = "hour" | "today";

/** The start of [period], worked out afresh on each fetch so "today" rolls over at midnight. */
function periodStart(period: GainsPeriod): Date {
  if (period === "hour") {
    return new Date(Date.now() - 60 * 60 * 1000);
  }
  const midnight = new Date();
  midnight.setHours(0, 0, 0, 0);
  return midnight;
}

/** Experience gained over [period], every player's or only [player]'s, refreshed like the rest of the pages. */
export function useGains(period: GainsPeriod, player?: number) {
  return useQuery({
    queryKey: ["gains", period, player],
    queryFn: async () => {
      const from = periodStart(period).toISOString();
      const { data, error } = await api.GET("/api/history/gains", {
        params: { query: player === undefined ? { from } : { from, player } },
      });
      if (error || !data) {
        throw new Error(error?.message ?? "The ledger didn't answer.");
      }
      return data.gains;
    },
    refetchInterval: 15_000,
  });
}
