import { Alert, Anchor, Badge, Loader, Paper, SimpleGrid, Stack, Table, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { Link, useParams } from "react-router";
import { api } from "../api/client";
import { PageHeading, TabTitle } from "../components/PageHeading";
import { addressId } from "./addressId";

/** One item: how much the world holds, and everyone holding any. */
export function ItemPage() {
  const itemIdText = useParams().itemId ?? "";
  const itemId = addressId(itemIdText);
  // Until the name arrives, or if it never does, the page is named from the address.
  const placeholder = (
    <>
      <BackToEconomy />
      <TabTitle text={`Item ${itemIdText}`} />
      <PageHeading>{`Item ${itemIdText}`}</PageHeading>
    </>
  );

  // An address that isn't an item number is refused at once, without asking the server.
  if (itemId === undefined) {
    return (
      <Stack>
        {placeholder}
        <Alert color="red" title="Can't load this item">
          There's no item with that number.
        </Alert>
      </Stack>
    );
  }
  return <ItemSheet itemId={itemId} placeholder={placeholder} />;
}

/** Item [itemId]'s page, showing [placeholder] in place of its name until the name arrives. */
function ItemSheet({ itemId, placeholder }: { itemId: number; placeholder: ReactNode }) {
  const item = useQuery({
    queryKey: ["item-holders", itemId],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/items/{itemId}/holders", { params: { path: { itemId } } });
      if (error || !data) {
        throw new Error(error?.message ?? "The ledger didn't answer.");
      }
      return data;
    },
  });

  if (item.isPending) {
    return (
      <Stack>
        {placeholder}
        <Loader />
      </Stack>
    );
  }
  if (item.isError) {
    return (
      <Stack>
        {placeholder}
        <Alert color="red" title="Can't load this item">
          {item.error.message}
        </Alert>
      </Stack>
    );
  }

  const { name, held, holders } = item.data;
  const itemName = name ?? `Item ${itemId}`;
  return (
    <Stack>
      <BackToEconomy />
      <TabTitle text={itemName} />
      <PageHeading>{itemName}</PageHeading>
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <Amount label="Altogether" value={held.total} />
        <Amount label="In inventories" value={held.inInventories} />
        <Amount label="In banks" value={held.inBanks} />
      </SimpleGrid>

      <Title order={3}>Who has it</Title>
      {holders.length === 0 ? (
        <Text c="dimmed">Nobody holds any.</Text>
      ) : (
        <Table.ScrollContainer minWidth={340} type="native">
          <Table striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Player</Table.Th>
                <Table.Th ta="right">Total</Table.Th>
                <Table.Th ta="right">In inventory</Table.Th>
                <Table.Th ta="right">In bank</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {holders.map((holder) => (
                <Table.Tr key={holder.playerId}>
                  <Table.Td>
                    <Anchor component={Link} to={`/players/${holder.playerId}`}>
                      {holder.name}
                    </Anchor>
                    {holder.staff && (
                      <Badge size="xs" variant="light" color="gray" ml="xs">
                        Staff
                      </Badge>
                    )}
                  </Table.Td>
                  <Table.Td ta="right">{holder.held.total.toLocaleString()}</Table.Td>
                  <Table.Td ta="right">{holder.held.inInventories.toLocaleString()}</Table.Td>
                  <Table.Td ta="right">{holder.held.inBanks.toLocaleString()}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      <Text c="dimmed" size="sm">
        Noted items count as the item.
      </Text>
    </Stack>
  );
}

function BackToEconomy() {
  return (
    <Anchor component={Link} to="/economy" size="sm">
      ← Economy
    </Anchor>
  );
}

function Amount({ label, value }: { label: string; value: number }) {
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
