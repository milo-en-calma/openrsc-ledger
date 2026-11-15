import { Alert, Loader, Paper, SimpleGrid, Stack, Table, Text, TextInput, Title, UnstyledButton } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import {
  createColumnHelper,
  createSortedRowModel,
  rowSortingFeature,
  sortFn_alphanumeric,
  sortFn_basic,
  tableFeatures,
  useTable,
} from "@tanstack/react-table";
import { useMemo, useState } from "react";
import type { components } from "../../generated/openapi";
import { api } from "../api/client";

type ItemHoldings = components["schemas"]["ItemHoldings"];

const features = tableFeatures({
  rowSortingFeature,
  sortedRowModel: createSortedRowModel(),
  sortFns: { alphanumeric: sortFn_alphanumeric, basic: sortFn_basic },
});

const column = createColumnHelper<typeof features, ItemHoldings>();

const itemName = (item: ItemHoldings) => item.name ?? `Item ${item.itemId}`;
const amount = (value: number) => value.toLocaleString();

const columns = column.columns([
  column.accessor(itemName, { id: "name", header: "Item", sortFn: "alphanumeric" }),
  column.accessor((item) => item.held.total, {
    id: "total",
    header: "Total",
    sortFn: "basic",
    sortDescFirst: true,
    cell: (cell) => amount(cell.getValue()),
  }),
  column.accessor((item) => item.held.inInventories, {
    id: "inInventories",
    header: "In inventories",
    sortFn: "basic",
    sortDescFirst: true,
    cell: (cell) => amount(cell.getValue()),
  }),
  column.accessor((item) => item.held.inBanks, {
    id: "inBanks",
    header: "In banks",
    sortFn: "basic",
    sortDescFirst: true,
    cell: (cell) => amount(cell.getValue()),
  }),
  column.accessor("holders", { header: "Players holding", sortFn: "basic", sortDescFirst: true }),
]);

function sortMark(sorted: false | "asc" | "desc"): string {
  return sorted === "asc" ? " ▲" : sorted === "desc" ? " ▼" : "";
}

export function EconomyPage() {
  const [search, setSearch] = useState("");

  const economy = useQuery({
    queryKey: ["economy"],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/economy");
      if (error || !data) {
        throw new Error(error?.message ?? "The ledger didn't answer.");
      }
      return data;
    },
    refetchInterval: 15_000,
  });

  const items = useMemo(() => {
    const wanted = search.trim().toLowerCase();
    const all = economy.data?.items ?? [];
    return wanted ? all.filter((item) => itemName(item).toLowerCase().includes(wanted)) : all;
  }, [economy.data, search]);

  const table = useTable({ features, columns, data: items });

  if (economy.isPending) {
    return <Loader />;
  }
  if (economy.isError) {
    return (
      <Alert color="red" title="Can't load the economy">
        {economy.error.message}
      </Alert>
    );
  }

  const { coins } = economy.data;
  return (
    <Stack>
      <Title order={3}>Coins</Title>
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <CoinTotal label="Altogether" value={coins.total} />
        <CoinTotal label="In inventories" value={coins.inInventories} />
        <CoinTotal label="In banks" value={coins.inBanks} />
      </SimpleGrid>

      <Title order={3}>Items held</Title>
      <Text c="dimmed" size="sm">
        Every item anyone holds, across every inventory and bank. Noted items count as the item.
      </Text>
      <TextInput
        placeholder="Search by name"
        value={search}
        onChange={(event) => setSearch(event.currentTarget.value)}
        maw={320}
        aria-label="Search items by name"
      />
      <Table striped highlightOnHover>
        <Table.Thead>
          {table.getHeaderGroups().map((group) => (
            <Table.Tr key={group.id}>
              {group.headers.map((header) => (
                <Table.Th key={header.id} ta={header.column.id === "name" ? undefined : "right"}>
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
                <Table.Td key={cell.id} ta={cell.column.id === "name" ? undefined : "right"}>
                  <table.FlexRender cell={cell} />
                </Table.Td>
              ))}
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

function CoinTotal({ label, value }: { label: string; value: number }) {
  return (
    <Paper withBorder p="md">
      <Text c="dimmed" size="sm">
        {label}
      </Text>
      <Text fw={700} size="xl">
        {value.toLocaleString()}
      </Text>
    </Paper>
  );
}
