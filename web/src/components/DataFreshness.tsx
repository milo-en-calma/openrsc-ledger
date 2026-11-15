import { Badge, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";

/** How fresh the ledger's data is: when it last copied the server's database. */
export function DataFreshness() {
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
    return null;
  }
  if (health.isError) {
    return <Badge color="red">Can't reach the ledger</Badge>;
  }
  const copiedAt = health.data.dataCopiedAt;
  return (
    <Text size="sm" c="dimmed">
      {copiedAt
        ? `Data as of ${new Date(copiedAt).toLocaleString()}`
        : "Waiting for the first copy of the server's database"}
    </Text>
  );
}
