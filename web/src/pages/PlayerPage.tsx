import { Alert, Anchor, Badge, Group, Loader, SimpleGrid, Stack, Table, Text, Title, Tooltip, VisuallyHidden } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router";
import type { components } from "../../generated/openapi";
import { api } from "../api/client";
import { useGains } from "../api/gains";
import { PageHeading, TabTitle } from "../components/PageHeading";
import { gainedBySkill, groupItems } from "./tally";

type Skill = components["schemas"]["SkillRow"];
type Item = components["schemas"]["ItemRow"];

/**
 * The "Today" column's heading. When history began after midnight it carries [countedSince] as a
 * tooltip, shown on hover, focus or touch, and the heading is underlined with dots to say there's one;
 * screen readers read the note with the heading.
 */
function TodayHeading({ countedSince }: { countedSince: string | null }) {
  if (!countedSince) {
    return "Today";
  }
  return (
    <Tooltip label={countedSince} events={{ hover: true, focus: true, touch: true }} multiline maw={260}>
      <Text span inherit tabIndex={0} style={{ textDecoration: "underline dotted", cursor: "help" }}>
        Today
        <VisuallyHidden>. {countedSince}</VisuallyHidden>
      </Text>
    </Tooltip>
  );
}

export function PlayerPage() {
  const idText = useParams().id ?? "";
  const id = Number(idText);
  // Until the name arrives, or if it never does, the page is named from the address.
  const placeholderName = `Player ${idText}`;
  // The gains columns stay blank while loading or if history can't be read; the rest of the page doesn't wait on them.
  // A player's own page shows their gains whether or not they're staff.
  const lastHour = gainedBySkill(useGains("hour", { player: id, staff: true }).data?.gains);
  const todayGains = useGains("today", { player: id, staff: true }).data;
  const today = gainedBySkill(todayGains?.gains);

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
    return (
      <Stack>
        <BackToPlayers />
        <TabTitle text={placeholderName} />
        <PageHeading>{placeholderName}</PageHeading>
        <Loader />
      </Stack>
    );
  }
  if (page.isError) {
    return (
      <Stack>
        <BackToPlayers />
        <TabTitle text={placeholderName} />
        <PageHeading>{placeholderName}</PageHeading>
        <Alert color="red" title="Can't load this player">
          {page.error.message}
        </Alert>
      </Stack>
    );
  }

  const { player, questPoints, skills, quests, equipment, inventory, bank } = page.data;
  return (
    <Stack>
      <BackToPlayers />
      <TabTitle text={player.name} />
      <Group align="baseline">
        <PageHeading>{player.name}</PageHeading>
        {player.online && <Badge color="green">Online</Badge>}
      </Group>
      <Text c="dimmed">
        Combat level {player.combatLevel} · Total level {player.totalLevel} · {questPoints} quest points
        {!player.online && player.lastLogin && ` · Last login ${new Date(player.lastLogin).toLocaleString()}`}
      </Text>

      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="xl">
        <Stack gap="xs">
          <Title order={3}>Skills</Title>
          <Table.ScrollContainer minWidth={400} type="native">
            <Table striped>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Skill</Table.Th>
                  <Table.Th ta="right">Level</Table.Th>
                  <Table.Th ta="right">Experience</Table.Th>
                  <Table.Th ta="right">Last hour</Table.Th>
                  <Table.Th ta="right">
                    <TodayHeading countedSince={todayGains?.countedSince ?? null} />
                  </Table.Th>
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
                    <Table.Td ta="right">
                      <Gained amount={lastHour.get(skill.skill)} />
                    </Table.Td>
                    <Table.Td ta="right">
                      <Gained amount={today.get(skill.skill)} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Stack>

        <Stack gap="xs">
          <Title order={3}>Quests</Title>
          {quests.length === 0 ? (
            <Text c="dimmed">No quests started.</Text>
          ) : (
            <Table.ScrollContainer minWidth={260} type="native">
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
            </Table.ScrollContainer>
          )}
        </Stack>
      </SimpleGrid>

      <SimpleGrid cols={{ base: 1, md: 3 }} spacing="xl">
        <ItemList title="Equipment" items={equipment} empty="Wearing nothing." />
        <ItemList title="Inventory" items={inventory} empty="Carrying nothing." markWorn />
        <ItemList title="Bank" items={bank} empty="Nothing banked." />
      </SimpleGrid>
    </Stack>
  );
}

/**
 * A titled list of items with their amounts, one row per kind of item. Noted items are
 * marked, and worn ones too with [markWorn]; a kind held in several slots says how many.
 */
function ItemList({ title, items, empty, markWorn = false }: { title: string; items: Item[]; empty: string; markWorn?: boolean }) {
  return (
    <Stack gap="xs">
      <Title order={3}>{title}</Title>
      {items.length === 0 ? (
        <Text c="dimmed">{empty}</Text>
      ) : (
        <Table.ScrollContainer minWidth={260} type="native">
          <Table striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Item</Table.Th>
                <Table.Th ta="right">Amount</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {groupItems(items).map(({ item, amount, slots }) => (
                <Table.Tr key={item.slot}>
                  <Table.Td>
                    <Group gap="xs" wrap="nowrap">
                      {item.name ?? `Item ${item.itemId}`}
                      {item.noted && (
                        <Badge size="xs" variant="light">
                          noted
                        </Badge>
                      )}
                      {markWorn && item.worn && (
                        <Badge size="xs" variant="light" color="grape">
                          worn
                        </Badge>
                      )}
                      {slots > 1 && (
                        <Text span size="sm" c="dimmed">
                          ({slots} slots)
                        </Text>
                      )}
                    </Group>
                  </Table.Td>
                  <Table.Td ta="right">{amount.toLocaleString()}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Stack>
  );
}

/** Experience gained in a period, as "+1,234", or a dimmed dash when there was none. */
function Gained({ amount }: { amount: number | undefined }) {
  if (!amount) {
    return (
      <Text span c="dimmed">
        –
      </Text>
    );
  }
  return (
    <Text span c="green">
      +{amount.toLocaleString()}
    </Text>
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
