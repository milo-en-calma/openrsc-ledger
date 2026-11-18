import { QueryClient, type DefaultOptions } from "@tanstack/react-query";

/** How often every query asks again for what it shows, so an open page never goes stale. */
export const REFRESH_EVERY_MS = 15_000;

/**
 * The app's query client: every query refreshes every [REFRESH_EVERY_MS] unless it sets its own
 * `refetchInterval`. [queries] adds further defaults on top, as the drawn-page tests do; the
 * browser and the tests share this one setting, so the tests check what the browser uses.
 */
export function makeQueryClient(queries: DefaultOptions["queries"] = {}): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { refetchInterval: REFRESH_EVERY_MS, ...queries } },
  });
}
