import { Anchor, Group, Stack, Text } from "@mantine/core";
import { Link, useLocation } from "react-router";
import { PageHeading, TabTitle } from "../components/PageHeading";
import { nearMiss } from "./nearMiss";

/**
 * Any address no page claims: says so, rather than drawing nothing under the header, and offers
 * the likely page for a few easy mistakes. It never goes anywhere on its own.
 */
export function NotFoundPage() {
  const { pathname } = useLocation();
  const suggestion = nearMiss(pathname);
  return (
    <Stack>
      <TabTitle text="Page not found" />
      <PageHeading>Page not found</PageHeading>
      <Text>There is no page at {pathname}.</Text>
      {suggestion && (
        <Text>
          Did you mean{" "}
          <Anchor component={Link} to={suggestion.to}>
            {suggestion.label}
          </Anchor>
          ?
        </Text>
      )}
      <Group gap="lg">
        <Anchor component={Link} to="/">
          Players
        </Anchor>
        <Anchor component={Link} to="/leaderboards">
          Leaderboards
        </Anchor>
        <Anchor component={Link} to="/economy">
          Economy
        </Anchor>
      </Group>
    </Stack>
  );
}
