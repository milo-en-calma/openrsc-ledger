import { AppShell, Group, Title } from "@mantine/core";
import { Route, Routes } from "react-router";
import { HomePage } from "./pages/HomePage";

export function App() {
  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md">
          <Title order={3}>openrsc-ledger</Title>
        </Group>
      </AppShell.Header>
      <AppShell.Main>
        <Routes>
          <Route path="/" element={<HomePage />} />
        </Routes>
      </AppShell.Main>
    </AppShell>
  );
}
