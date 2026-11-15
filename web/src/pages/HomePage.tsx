import { Alert, Badge, Group, Loader, Stack, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";

export function HomePage() {
  const health = useQuery({
    queryKey: ["health"],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/health");
      if (error || !data) {
        throw new Error("The ledger didn't answer.");
      }
      return data;
    },
    refetchInterval: 15_000,
  });

  if (health.isPending) {
    return <Loader />;
  }
  if (health.isError) {
    return (
      <Alert color="red" title="Can't reach the ledger">
        {health.error.message}
      </Alert>
    );
  }
  const copiedAt = health.data.dataCopiedAt;
  return (
    <Stack>
      <Group>
        <Text>Ledger status:</Text>
        <Badge color="green">{health.data.status}</Badge>
      </Group>
      <Text c="dimmed">
        {copiedAt
          ? `Data as of ${new Date(copiedAt).toLocaleString()}`
          : "Waiting for the first copy of the server's database."}
      </Text>
    </Stack>
  );
}
