// Built by `npm test` with `vite build --ssr` into build/render/render.js, so the tests can draw
// each page to HTML in Node without a browser.
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider, type QueryKey } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { App } from "../src/App";

/** What a page's queries hold when it's drawn: answers by query key, and keys whose fetch failed. */
export interface Seed {
  data?: [QueryKey, unknown][];
  failed?: [QueryKey, string][];
}

/**
 * The whole app, header included, drawn as HTML at [path]. Each query holds its answer from
 * [seed]; a key it doesn't name stays loading, since nothing is fetched while drawing.
 */
export async function renderPage(path: string, { data = [], failed = [] }: Seed = {}): Promise<string> {
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
  return renderToStaticMarkup(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[path]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}
