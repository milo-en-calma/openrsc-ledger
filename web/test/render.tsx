// Built by `npm test` with `vite build --ssr` into build/render/render.js, so the tests can draw
// each page to HTML in Node without a browser.
import { MantineProvider } from "@mantine/core";
import { hashKey, QueryClient, QueryClientProvider, type QueryKey } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { App } from "../src/App";

/** What a page's queries hold when it's drawn: answers by query key, and keys whose fetch failed. */
export interface Seed {
  data?: [QueryKey, unknown][];
  failed?: [QueryKey, string][];
}

/**
 * A drawn page, and every query key it asked for while drawing (queries it turned off left out),
 * each written as [keyText] writes it.
 */
export interface Drawn {
  html: string;
  asked: string[];
}

/** A query key as one string, written the way the query client itself compares keys. */
export function keyText(queryKey: QueryKey): string {
  return hashKey(queryKey);
}

/**
 * The whole app, header included, drawn as HTML at [path]. Each query holds its answer from
 * [seed]; a key it doesn't name stays loading, since nothing is fetched while drawing.
 */
export async function renderPage(path: string, seed: Seed = {}): Promise<string> {
  return (await drawPage(path, seed)).html;
}

/** [renderPage], also saying which query keys the page asked for while it was drawn. */
export async function drawPage(path: string, { data = [], failed = [] }: Seed = {}): Promise<Drawn> {
  const queryClient = new QueryClient({
    // A failed query is shown failed, rather than as loading again before a retry.
    defaultOptions: { queries: { retry: false, retryOnMount: false, staleTime: Infinity } },
  });
  for (const [queryKey, answer] of data) {
    queryClient.setQueryData(queryKey, answer);
  }
  for (const [queryKey, message] of failed) {
    await queryClient.prefetchQuery({
      queryKey,
      queryFn: () => Promise.reject(new Error(message)),
    });
  }
  // Every query a page uses is looked up in the cache through build, so from here on (after the
  // seeding above, which builds too) each call is a key the page asked for, unless the page
  // turned that query off (`enabled: false`), in which case it never fetches.
  const cache = queryClient.getQueryCache();
  const build = cache.build.bind(cache);
  const asked = new Set<string>();
  cache.build = (client, options, state) => {
    const query = build(client, options, state);
    const enabled = "enabled" in options ? options.enabled : undefined;
    if (typeof enabled === "function" ? enabled(query) : enabled !== false) {
      asked.add(query.queryHash);
    }
    return query;
  };
  const html = renderToStaticMarkup(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[path]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
  return { html, asked: [...asked] };
}
