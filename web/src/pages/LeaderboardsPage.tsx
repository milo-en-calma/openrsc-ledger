import { Alert, Anchor, Badge, Group, Loader, Select, Stack, Switch, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { api } from "../api/client";
import { useGains } from "../api/gains";
import { PageHeading, TabTitle } from "../components/PageHeading";
import { totalPerPlayer } from "./tally";

/** The board of experience gained today. It's built here from the history's gains, not served as a board by the API. */
const GAINED_TODAY = "gained-today";

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
    enabled: board !== GAINED_TODAY,
  });

  return (
    <Stack>
      <TabTitle text="Leaderboards" />
      <PageHeading>Leaderboards</PageHeading>
      <Group align="end">
        <Select
          label="Leaderboard"
          data={[
            ...(boards.data ?? []).map((it) => ({ value: it.key, label: it.name })),
            { value: GAINED_TODAY, label: "Experience gained today" },
          ]}
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
      {board === GAINED_TODAY ? (
        <GainedTodayBoard showStaff={showStaff} />
      ) : leaderboard.isPending ? (
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

/** Players ranked by experience gained since midnight, in the viewer's time zone. Staff are left off unless [showStaff]. */
function GainedTodayBoard({ showStaff }: { showStaff: boolean }) {
  const gains = useGains("today", { staff: showStaff });

  if (gains.isPending) {
    return <Loader />;
  }
  if (gains.isError) {
    return (
      <Alert color="red" title="Can't load today's gains">
        {gains.error.message}
      </Alert>
    );
  }

  const rows = totalPerPlayer(gains.data.gains);
  const countedSince = gains.data.countedSince && (
    <Text size="sm" c="dimmed">
      {gains.data.countedSince}
    </Text>
  );
  if (rows.length === 0) {
    return (
      <Stack gap="xs">
        <Text c="dimmed">No experience gained yet today.</Text>
        {countedSince}
      </Stack>
    );
  }
  return (
    <Stack gap="xs">
      <GainedTodayTable rows={rows} />
      {countedSince}
    </Stack>
  );
}

/** The board of experience gained today, one row per player, as [totalPerPlayer] ranks them. */
function GainedTodayTable({ rows }: { rows: ReturnType<typeof totalPerPlayer> }) {
  return (
    <Table.ScrollContainer minWidth={420} type="native">
      <Table striped highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th ta="right">Rank</Table.Th>
            <Table.Th>Name</Table.Th>
            <Table.Th ta="right">Experience gained</Table.Th>
            <Table.Th ta="right">Levels gained</Table.Th>
            <Table.Th>Most in</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((row, index) => (
            <Table.Tr key={row.playerId}>
              <Table.Td ta="right">{index + 1}</Table.Td>
              <Table.Td>
                <Anchor component={Link} to={`/players/${row.playerId}`}>
                  {row.name}
                </Anchor>
                {row.staff && (
                  <Badge size="xs" variant="light" color="gray" ml="xs">
                    Staff
                  </Badge>
                )}
              </Table.Td>
              <Table.Td ta="right">+{row.experienceGained.toLocaleString()}</Table.Td>
              <Table.Td ta="right">{row.levelsGained > 0 ? `+${row.levelsGained}` : "–"}</Table.Td>
              <Table.Td>{row.topSkill}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}
