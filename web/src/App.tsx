import { AppShell, Group, Title } from "@mantine/core";
import { Route, Routes } from "react-router";
import { DataFreshness } from "./components/DataFreshness";
import { PlayerPage } from "./pages/PlayerPage";
import { PlayersPage } from "./pages/PlayersPage";

export function App() {
  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Title order={3}>openrsc-ledger</Title>
          <DataFreshness />
        </Group>
      </AppShell.Header>
      <AppShell.Main>
        <Routes>
          <Route path="/" element={<PlayersPage />} />
          <Route path="/players/:id" element={<PlayerPage />} />
        </Routes>
      </AppShell.Main>
    </AppShell>
  );
}
