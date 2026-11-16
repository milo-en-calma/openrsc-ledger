package ledger.core.history

import ledger.core.game.PlayerSkills
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Reading how much history a file holds, for `--check`, without changing the file. */
class HistorySummaryTest {
    private val dir = Files.createTempDirectory("history-summary")
    private val file = dir.resolve("history.db")
    private val at = Instant.parse("2026-01-01T09:20:00Z")

    private fun skills(playerId: Int, woodcut: Int) =
        PlayerSkills(playerId, Skill.entries.associateWith { SkillValue(0, 1) } + (Skill.WOODCUT to SkillValue(woodcut, 1)))

    @Test
    fun `counts the values and players recorded, and the oldest and newest times`() {
        val history = HistoryStore(file)
        history.record(at, listOf(skills(1, 0), skills(2, 0)))
        history.record(at + 5.minutes, listOf(skills(1, 100), skills(2, 0)))

        assertEquals(
            HistorySummary(values = 2 * Skill.entries.size + 1, players = 2, oldest = at, newest = at + 5.minutes),
            HistorySummary.read(file),
        )
    }

    @Test
    fun `reading changes nothing in the file`() {
        HistoryStore(file).record(at, listOf(skills(1, 0)))
        val before = file.readBytes()
        HistorySummary.read(file)
        assertContentEquals(before, file.readBytes())
        assertFalse(Files.exists(dir.resolve("history.db-journal")))
    }

    @Test
    fun `a missing file is null and isn't created`() {
        assertNull(HistorySummary.read(file))
        assertFalse(Files.exists(file))
    }

    @Test
    fun `a file with no recordings yet, or no table yet, reads as empty`() {
        HistoryStore(file)
        assertEquals(HistorySummary.EMPTY, HistorySummary.read(file))

        val bare = dir.resolve("bare.db")
        Files.createFile(bare)
        assertEquals(HistorySummary.EMPTY, HistorySummary.read(bare))
    }

    @Test
    fun `a file that isn't a SQLite database is refused, naming it`() {
        file.writeText("not a database")
        assertEquals("The history file $file isn't a SQLite database", assertFailsWith<IllegalArgumentException> { HistorySummary.read(file) }.message)
    }

    @Test
    fun `a folder where the file should be is refused, naming it`() {
        Files.createDirectory(file)
        assertEquals("The history file $file isn't a file", assertFailsWith<IllegalArgumentException> { HistorySummary.read(file) }.message)
    }

    @Test
    fun `a history table missing a column the ledger reads is refused, naming the file`() {
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { it.executeUpdate("CREATE TABLE skill_history (player_id INT, skill TEXT, recorded_at BIGINT)") }
        }
        val message = assertFailsWith<IllegalArgumentException> { HistorySummary.read(file) }.message!!
        assertContains(message, "Can't read the history file $file")
        assertContains(message, "experience")
    }
}
