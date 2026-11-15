import { Alert, Anchor, Badge, Group, Loader, Select, Stack, Switch, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { api } from "../api/client";

export function LeaderboardsPage() {
  const board = useParams().board ?? "overall";
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  // Staff are left off unless asked for; the choice lives in the address so a link keeps it.
  const showStaff = searchParams.get("staff") === "1";
  const staffSuffix = showStaff ? "?staff=1" : "";

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
    queryKey: ["leaderboard", board, showStaff],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/leaderboards/{board}", {
        params: { path: { board }, query: showStaff ? { staff: true } : {} },
      });
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
      <Group align="end">
        <Select
          label="Leaderboard"
          data={(boards.data ?? []).map((it) => ({ value: it.key, label: it.name }))}
          value={board}
          onChange={(value) => value && navigate(`/leaderboards/${value}${staffSuffix}`)}
          allowDeselect={false}
          searchable
          maw={240}
        />
        <Switch
          label="Show staff"
          checked={showStaff}
          onChange={(event) => setSearchParams(event.currentTarget.checked ? { staff: "1" } : {})}
          mb={8}
        />
      </Group>
      {leaderboard.isPending ? (
        <Loader />
      ) : leaderboard.isError ? (
        <Alert color="red" title="Can't load this leaderboard">
          {leaderboard.error.message}
        </Alert>
      ) : leaderboard.data.entries.length === 0 ? (
        <Text c="dimmed">No players yet.</Text>
      ) : (
        <Table.ScrollContainer minWidth={340} type="native">
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
                    {entry.staff && (
                      <Badge size="xs" variant="light" color="gray" ml="xs">
                        Staff
                      </Badge>
                    )}
                  </Table.Td>
                  <Table.Td ta="right">{entry.level}</Table.Td>
                  <Table.Td ta="right">{entry.experience.toLocaleString()}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Stack>
  );
}
