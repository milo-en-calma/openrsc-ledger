package ledger.core.history

import ledger.core.game.PlayerSkills
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.measureTime

/**
 * The history store reads through its index rather than through every row. These check that
 * its answers are what reading every row gives, that SQLite's plan for each read jumps through
 * the index, and that a million rows stay quick.
 */
class HistoryReadsTest {
    private val dir = Files.createTempDirectory("history-reads")

    private data class Row(val playerId: Int, val skill: Skill, val value: SkillValue, val at: Long)

    /** [HistoryReader.changes] as its description says, worked out over every row. */
    private fun reference(rows: List<Row>, from: Long, to: Long, playerId: Int?, skill: Skill?): List<SkillChange> =
        rows.groupBy { it.playerId to it.skill }
            .filterKeys { (player, rowSkill) -> (playerId == null || player == playerId) && (skill == null || rowSkill == skill) }
            .toSortedMap(compareBy<Pair<Int, Skill>> { it.first }.thenBy { it.second.column })
            .mapNotNull { (key, values) ->
                val end = values.filter { it.at <= to }.maxByOrNull { it.at }?.value ?: return@mapNotNull null
                val start = values.filter { it.at <= from }.maxByOrNull { it.at }?.value
                    ?: values.filter { it.at > from && it.at <= to }.minByOrNull { it.at }?.value
                    ?: return@mapNotNull null
                if (start == end) null else SkillChange(key.first, key.second, start, end)
            }

    /** Writes [rows] straight into a new history file laid out as the store lays it out. */
    private fun fill(file: Path, rows: List<Row>) {
        HistoryStore(file)
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("INSERT INTO skill_history (player_id, skill, experience, level, recorded_at) VALUES (?, ?, ?, ?, ?)").use { insert ->
                for (row in rows) {
                    insert.setInt(1, row.playerId)
                    insert.setString(2, row.skill.column)
                    insert.setInt(3, row.value.experience)
                    insert.setInt(4, row.value.level)
                    insert.setLong(5, row.at)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            connection.commit()
        }
    }

    /** A random history: each row one skill of one of [players] players taking a value, at one of a few hundred times, some shared. */
    private fun randomRows(random: Random, players: Int, count: Int): List<Row> {
        val skills = Skill.entries.shuffled(random).take(6)
        return (0 until count).map {
            Row(random.nextInt(1, players + 1), skills.random(random), SkillValue(random.nextInt(0, 50), random.nextInt(1, 4)), random.nextLong(0, 300) * 1000)
        }.distinctBy { Triple(it.playerId, it.skill, it.at) }
    }

    @Test
    fun `changes match a reading of every row, over random histories, periods, players and skills`() {
        var compared = 0
        repeat(40) { round ->
            val random = Random(round)
            val rows = randomRows(random, players = random.nextInt(1, 8), count = random.nextInt(0, 200))
            val file = dir.resolve("history-$round.db")
            fill(file, rows)
            val history = HistoryStore(file)
            repeat(25) {
                val from = random.nextLong(-10, 310) * 1000
                val to = if (random.nextInt(5) == 0) random.nextLong(-10, 310) * 1000 else from + random.nextLong(0, 200) * 1000
                val playerId = if (random.nextBoolean()) random.nextInt(0, 9) else null
                val skill = if (random.nextInt(3) == 0) Skill.entries.random(random) else null
                assertEquals(
                    reference(rows, from, to, playerId, skill),
                    history.changes(Instant.fromEpochMilliseconds(from), Instant.fromEpochMilliseconds(to), playerId, skill),
                    "round $round, from $from, to $to, player $playerId, skill $skill",
                )
                if (playerId == null && skill == null) {
                    assertEquals(reference(rows, from, to, null, null), history.changes(Instant.fromEpochMilliseconds(from), Instant.fromEpochMilliseconds(to)))
                }
                compared++
            }
            val newest = rows.groupBy { it.playerId to it.skill }.mapValues { (_, values) -> values.maxBy { it.at }.value }
            assertEquals(newest, history.latest(), "round $round")
        }
        assertEquals(1000, compared)
    }

    @Test
    fun `the newest values kept in memory follow every recording, and a refused one changes nothing`() {
        val history = HistoryStore(dir.resolve("history.db"))
        val at = Instant.parse("2026-01-01T09:20:00Z")
        fun skills(woodcut: Int) = listOf(PlayerSkills(1, Skill.entries.associateWith { SkillValue(0, 1) } + (Skill.WOODCUT to SkillValue(woodcut, 1))))

        assertEquals(Skill.entries.size, history.record(at, skills(100)))
        assertEquals(1, history.record(at + 1.minutes, skills(200)))
        // Stamped before the newest recording: refused, so the newest stays 200, and the next save records 300.
        assertEquals(0, history.record(at, skills(300)))
        assertEquals(SkillValue(200, 1), history.latest()[1 to Skill.WOODCUT])
        assertEquals(1, history.record(at + 2.minutes, skills(300)))

        // A store opened afresh on the same file starts from what was written.
        assertEquals(history.latest(), HistoryStore(dir.resolve("history.db")).latest())
    }

    @Test
    fun `every read of the history jumps through the index, never through the whole table`() {
        val file = dir.resolve("history.db")
        fill(file, randomRows(Random(1), players = 50, count = 5000))
        val reads = mapOf(
            "everyone's changes" to HistoryStore.changesSql(onePlayer = false, oneSkill = false),
            "one player's changes" to HistoryStore.changesSql(onePlayer = true, oneSkill = false),
            "one skill's changes" to HistoryStore.changesSql(onePlayer = false, oneSkill = true),
            "one player's changes in one skill" to HistoryStore.changesSql(onePlayer = true, oneSkill = true),
            "the newest time" to "SELECT MAX(recorded_at) FROM skill_history",
            "the oldest time" to "SELECT MIN(recorded_at) FROM skill_history",
        )
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            for ((name, sql) in reads) {
                val steps = connection.prepareStatement("EXPLAIN QUERY PLAN ${sql.replace("?", "0")}").use { statement ->
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) { add(rows.getString("detail")) } } }
                }
                assertTrue(steps.none { Regex("""^SCAN (skill_history|h|next|e|s|f)\b""").containsMatchIn(it) }, "$name reads the whole table: $steps")
                assertTrue(steps.any { it.startsWith("SEARCH") }, "$name: $steps")
            }
        }
    }

    @Test
    fun `a million rows of history stay quick to record onto and read`() {
        val players = 3000
        val random = Random(5)
        val file = dir.resolve("history.db")
        val year = 365L * 24 * 3600 * 1000
        val end = Instant.parse("2026-11-01T00:00:00Z").toEpochMilliseconds()
        val experience = HashMap<Pair<Int, Skill>, Int>()
        fill(file, (0 until 1_000_000).map { row ->
            val key = random.nextInt(1, players + 1) to Skill.entries.random(random)
            val value = experience.getOrDefault(key, 0) + random.nextInt(1, 400)
            experience[key] = value
            Row(key.first, key.second, SkillValue(value, 1), end - year + row * (year / 1_000_000))
        })
        val history = HistoryStore(file)
        val latest = history.latest().entries.groupBy({ it.key.first }, { it.key.second to it.value })
        val current = latest.map { (player, skills) ->
            PlayerSkills(player, skills.associate { (skill, value) -> skill to if (player <= 50) value.copy(experience = value.experience + 1) else value })
        }
        val day = Instant.fromEpochMilliseconds(end - 24 * 3600 * 1000) to Instant.fromEpochMilliseconds(end)

        // Limits about five times what these take on an ordinary laptop, and below the times of reading every row.
        val recording = measureTime { assertTrue(history.record(Instant.fromEpochMilliseconds(end + 30_000), current) > 0) }
        assertTrue(recording.inWholeMilliseconds < 400, "Recording a copy took $recording")
        val everyone = measureTime { history.changes(day.first, day.second) }
        assertTrue(everyone.inWholeMilliseconds < 1000, "A day's changes took $everyone")
        val onePlayer = measureTime { history.changes(day.first, day.second, 1234, null) }
        assertTrue(onePlayer.inWholeMilliseconds < 200, "One player's changes took $onePlayer")
    }
}
