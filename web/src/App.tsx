import { AppShell, Group, Title } from "@mantine/core";
import { NavLink, Route, Routes } from "react-router";
import { DataFreshness } from "./components/DataFreshness";
import { LeaderboardsPage } from "./pages/LeaderboardsPage";
import { PlayerPage } from "./pages/PlayerPage";
import { PlayersPage } from "./pages/PlayersPage";

/** The header's links; the current page's is bold. */
const navLinkStyle = ({ isActive }: { isActive: boolean }) => ({
  color: "inherit",
  textDecoration: "none",
  fontWeight: isActive ? 700 : 400,
});

export function App() {
  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="lg" wrap="nowrap">
            <Title order={3}>openrsc-ledger</Title>
            <NavLink to="/" end style={navLinkStyle}>
              Players
            </NavLink>
            <NavLink to="/leaderboards" style={navLinkStyle}>
              Leaderboards
            </NavLink>
          </Group>
          <DataFreshness />
        </Group>
      </AppShell.Header>
      <AppShell.Main>
        <Routes>
          <Route path="/" element={<PlayersPage />} />
          <Route path="/players/:id" element={<PlayerPage />} />
          <Route path="/leaderboards" element={<LeaderboardsPage />} />
          <Route path="/leaderboards/:board" element={<LeaderboardsPage />} />
        </Routes>
      </AppShell.Main>
    </AppShell>
  );
}
