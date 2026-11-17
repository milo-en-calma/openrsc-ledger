import { Title } from "@mantine/core";
import type { ReactNode } from "react";

/**
 * The page's browser tab title, "[text] · openrsc-ledger". React moves it into the document's head,
 * ahead of index.html's plain "openrsc-ledger", which shows only until the app has drawn.
 */
export function TabTitle({ text }: { text: string }) {
  // One string, since a title can't hold several pieces of text.
  return <title>{`${text} · openrsc-ledger`}</title>;
}

/** The page's one main heading (h1), drawn at the size the player and item names have always had. */
export function PageHeading({ children }: { children: ReactNode }) {
  return (
    <Title order={1} size="h2">
      {children}
    </Title>
  );
}
