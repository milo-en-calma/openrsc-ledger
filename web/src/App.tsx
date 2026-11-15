import { AppShell, Container, Group, Stack, Title } from "@mantine/core";
import { NavLink, Route, Routes } from "react-router";
import { DataFreshness } from "./components/DataFreshness";
import { EconomyPage } from "./pages/EconomyPage";
import { ItemPage } from "./pages/ItemPage";
import { LeaderboardsPage } from "./pages/LeaderboardsPage";
import { PlayerPage } from "./pages/PlayerPage";
import { PlayersPage } from "./pages/PlayersPage";

/** The header's links; the current page's is bold. */
const navLinkStyle = ({ isActive }: { isActive: boolean }) => ({
  color: "inherit",
  textDecoration: "none",
  fontWeight: isActive ? 700 : 400,
});

/** The pages the header links to. */
function NavLinks() {
  return (
    <>
      <NavLink to="/" end style={navLinkStyle}>
        Players
      </NavLink>
      <NavLink to="/leaderboards" style={navLinkStyle}>
        Leaderboards
      </NavLink>
      <NavLink to="/economy" style={navLinkStyle}>
        Economy
      </NavLink>
    </>
  );
}

export function App() {
  return (
    // Phones get a two-row header (title and freshness, then the links), since one row doesn't fit.
    <AppShell header={{ height: { base: 84, sm: 56 } }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap" visibleFrom="sm">
          <Group gap="lg" wrap="nowrap">
            <Title order={3}>openrsc-ledger</Title>
            <NavLinks />
          </Group>
          <DataFreshness />
        </Group>
        <Stack h="100%" px="md" py={8} gap={6} justify="center" hiddenFrom="sm">
          <Group justify="space-between" wrap="nowrap">
            <Title order={4}>openrsc-ledger</Title>
            <DataFreshness size="xs" />
          </Group>
          <Group gap="lg" wrap="nowrap">
            <NavLinks />
          </Group>
        </Stack>
      </AppShell.Header>
      <AppShell.Main>
        {/* Capped, so on wide screens numbers stay close to the names they belong to. */}
        <Container size={1000} p={0}>
          <Routes>
          <Route path="/" element={<PlayersPage />} />
          <Route path="/players/:id" element={<PlayerPage />} />
          <Route path="/leaderboards" element={<LeaderboardsPage />} />
          <Route path="/leaderboards/:board" element={<LeaderboardsPage />} />
          <Route path="/economy" element={<EconomyPage />} />
          <Route path="/items/:itemId" element={<ItemPage />} />
          </Routes>
        </Container>
      </AppShell.Main>
    </AppShell>
  );
}
