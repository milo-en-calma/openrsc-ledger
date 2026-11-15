import { Alert, Anchor, Loader, Select, Stack, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router";
import { api } from "../api/client";

export function LeaderboardsPage() {
  const board = useParams().board ?? "overall";
  const navigate = useNavigate();

  const boards = useQuery({
    queryKey: ["leaderboards"],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/leaderboards");
      if (error || !data) {
        throw new Error("The ledger didn't answer.");
      }
      return data.boards;
    },
    staleTime: Infinity,
  });

  const leaderboard = useQuery({
    queryKey: ["leaderboard", board],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/leaderboards/{board}", { params: { path: { board } } });
      if (error || !data) {
        throw new Error(error?.message ?? "The ledger didn't answer.");
      }
      return data;
    },
    placeholderData: keepPreviousData,
    refetchInterval: 15_000,
  });

  return (
    <Stack>
      <Select
        label="Leaderboard"
        data={(boards.data ?? []).map((it) => ({ value: it.key, label: it.name }))}
        value={board}
        onChange={(value) => value && navigate(`/leaderboards/${value}`)}
        allowDeselect={false}
        searchable
        maw={240}
      />
      {leaderboard.isPending ? (
        <Loader />
      ) : leaderboard.isError ? (
        <Alert color="red" title="Can't load this leaderboard">
          {leaderboard.error.message}
        </Alert>
      ) : leaderboard.data.entries.length === 0 ? (
        <Text c="dimmed">No players yet.</Text>
      ) : (
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th ta="right">Rank</Table.Th>
              <Table.Th>Name</Table.Th>
              <Table.Th ta="right">{board === "overall" ? "Total level" : "Level"}</Table.Th>
              <Table.Th ta="right">Experience</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {leaderboard.data.entries.map((entry) => (
              <Table.Tr key={entry.playerId}>
                <Table.Td ta="right">{entry.rank}</Table.Td>
                <Table.Td>
                  <Anchor component={Link} to={`/players/${entry.playerId}`}>
                    {entry.name}
                  </Anchor>
                </Table.Td>
                <Table.Td ta="right">{entry.level}</Table.Td>
                <Table.Td ta="right">{entry.experience.toLocaleString()}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}
