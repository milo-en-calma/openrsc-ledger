import { Alert, Anchor, Badge, Group, Loader, SimpleGrid, Stack, Table, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router";
import type { components } from "../../generated/openapi";
import { api } from "../api/client";

type Skill = components["schemas"]["SkillRow"];

export function PlayerPage() {
  const id = Number(useParams().id);

  const page = useQuery({
    queryKey: ["player", id],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/players/{id}", { params: { path: { id } } });
      if (error || !data) {
        throw new Error(error?.message ?? "The ledger didn't answer.");
      }
      return data;
    },
    refetchInterval: 15_000,
  });

  if (page.isPending) {
    return <Loader />;
  }
  if (page.isError) {
    return (
      <Stack>
        <BackToPlayers />
        <Alert color="red" title="Can't load this player">
          {page.error.message}
        </Alert>
      </Stack>
    );
  }

  const { player, questPoints, skills, quests } = page.data;
  return (
    <Stack>
      <BackToPlayers />
      <Group align="baseline">
        <Title order={2}>{player.name}</Title>
        {player.online && <Badge color="green">Online now</Badge>}
      </Group>
      <Text c="dimmed">
        Combat level {player.combatLevel} · Total level {player.totalLevel} · {questPoints} quest points
        {!player.online && player.lastLogin && ` · Last login ${new Date(player.lastLogin).toLocaleString()}`}
      </Text>

      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="xl">
        <Stack gap="xs">
          <Title order={3}>Skills</Title>
          <Table striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Skill</Table.Th>
                <Table.Th ta="right">Level</Table.Th>
                <Table.Th ta="right">Experience</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {skills.map((skill) => (
                <Table.Tr key={skill.skill}>
                  <Table.Td>{skill.name}</Table.Td>
                  <Table.Td ta="right">
                    <Level skill={skill} />
                  </Table.Td>
                  <Table.Td ta="right">{skill.experience.toLocaleString()}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Stack>

        <Stack gap="xs">
          <Title order={3}>Quests</Title>
          {quests.length === 0 ? (
            <Text c="dimmed">No quests started.</Text>
          ) : (
            <Table striped>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Quest</Table.Th>
                  <Table.Th>Progress</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {quests.map((quest) => (
                  <Table.Tr key={quest.id}>
                    <Table.Td>{quest.name ?? `Quest ${quest.id}`}</Table.Td>
                    <Table.Td>
                      {quest.complete ? <Badge color="green">Complete</Badge> : `Stage ${quest.stage}`}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </Stack>
      </SimpleGrid>
    </Stack>
  );
}

/** The full level, with the current one beside it when a boost or drain has moved it. */
function Level({ skill }: { skill: Skill }) {
  if (skill.level === skill.fullLevel) {
    return <>{skill.fullLevel}</>;
  }
  return (
    <>
      <Text span c={skill.level > skill.fullLevel ? "green" : "red"} fw={700}>
        {skill.level}
      </Text>
      {` / ${skill.fullLevel}`}
    </>
  );
}

function BackToPlayers() {
  return (
    <Anchor component={Link} to="/" size="sm">
      ← All players
    </Anchor>
  );
}
