import { Alert, Badge, Group, Loader, Text } from "@mantine/core";
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
  return (
    <Group>
      <Text>Ledger status:</Text>
      <Badge color="green">{health.data.status}</Badge>
    </Group>
  );
}
