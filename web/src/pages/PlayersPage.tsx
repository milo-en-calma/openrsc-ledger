import { Alert, Anchor, Badge, Loader, Stack, Table, Text, TextInput, UnstyledButton } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  createColumnHelper,
  createSortedRowModel,
  rowSortingFeature,
  sortFn_alphanumeric,
  sortFn_basic,
  tableFeatures,
  useTable,
} from "@tanstack/react-table";
import { useState } from "react";
import { Link } from "react-router";
import type { components } from "../../generated/openapi";
import { api } from "../api/client";
import { PageHeading, TabTitle } from "../components/PageHeading";
import { lastSeenOrder } from "./tally";

type Player = components["schemas"]["PlayerListItem"];

const features = tableFeatures({
  rowSortingFeature,
  sortedRowModel: createSortedRowModel(),
  sortFns: { alphanumeric: sortFn_alphanumeric, basic: sortFn_basic },
});

const column = createColumnHelper<typeof features, Player>();

const columns = column.columns([
  column.accessor("name", {
    header: "Name",
    sortFn: "alphanumeric",
    cell: ({ row }) => (
      <Anchor component={Link} to={`/players/${row.original.id}`}>
        {row.original.name}
      </Anchor>
    ),
  }),
  column.accessor("combatLevel", { header: "Combat", sortFn: "basic", sortDescFirst: true }),
  column.accessor("totalLevel", { header: "Total level", sortFn: "basic", sortDescFirst: true }),
  column.accessor(lastSeenOrder, {
    id: "lastSeen",
    header: "Last login",
    sortFn: "basic",
    sortDescFirst: true,
    cell: ({ row }) =>
      row.original.online ? (
        <Badge color="green">Online</Badge>
      ) : row.original.lastLogin ? (
        new Date(row.original.lastLogin).toLocaleString()
      ) : (
        <Text c="dimmed" span>
          Never
        </Text>
      ),
  }),
]);

function sortMark(sorted: false | "asc" | "desc"): string {
  return sorted === "asc" ? " ▲" : sorted === "desc" ? " ▼" : "";
}

export function PlayersPage() {
  const [search, setSearch] = useState("");
  const [debouncedSearch] = useDebouncedValue(search.trim(), 200);

  const players = useQuery({
    queryKey: ["players", debouncedSearch],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/players", {
        params: { query: debouncedSearch ? { search: debouncedSearch } : {} },
      });
      if (error || !data) {
        throw new Error(error?.message ?? "The ledger didn't answer.");
      }
      return data.players;
    },
    // Keeps the last results on screen while the next search loads.
    placeholderData: keepPreviousData,
    refetchInterval: 15_000,
  });

  const table = useTable({
    features,
    columns,
    data: players.data ?? [],
    // Online players first, then the most recent logins; a header click still re-sorts.
    initialState: { sorting: [{ id: "lastSeen", desc: true }] },
  });

  return (
    <Stack>
      <TabTitle text="Players" />
      <PageHeading>Players</PageHeading>
      <TextInput
        placeholder="Search by name"
        value={search}
        onChange={(event) => setSearch(event.currentTarget.value)}
        maw={320}
        aria-label="Search players by name"
      />
      {players.isPending ? (
        <Loader />
      ) : players.isError ? (
        <Alert color="red" title="Can't load the players">
          {players.error.message}
        </Alert>
      ) : (
        <>
          <Text c="dimmed" size="sm">
            {players.data.length === 1 ? "1 player" : `${players.data.length} players`}
            {" · "}
            {players.data.filter((player) => player.online).length} online now
          </Text>
          <Table.ScrollContainer minWidth={340} type="native">
            <Table striped highlightOnHover>
              <Table.Thead>
                {table.getHeaderGroups().map((group) => (
                  <Table.Tr key={group.id}>
                    {group.headers.map((header) => (
                      <Table.Th key={header.id}>
                        <UnstyledButton onClick={header.column.getToggleSortingHandler()} fw={700}>
                          <table.FlexRender header={header} />
                          {sortMark(header.column.getIsSorted())}
                        </UnstyledButton>
                      </Table.Th>
                    ))}
                  </Table.Tr>
                ))}
              </Table.Thead>
              <Table.Tbody>
                {table.getRowModel().rows.map((row) => (
                  <Table.Tr key={row.id}>
                    {row.getAllCells().map((cell) => (
                      <Table.Td key={cell.id}>
                        <table.FlexRender cell={cell} />
                      </Table.Td>
                    ))}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </>
      )}
    </Stack>
  );
}
