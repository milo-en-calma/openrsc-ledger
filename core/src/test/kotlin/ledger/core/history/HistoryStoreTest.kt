package ledger.core.history

import ledger.core.fixture.Fixtures
import ledger.core.game.PlayerSkills
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import ledger.core.game.SqliteGameData
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class HistoryStoreTest {
    private val dir = Files.createTempDirectory("history")
    private val game = Fixtures.database(dir.resolve("copy.db"))
    private val gameData = SqliteGameData(game)
    private val history = HistoryStore(dir.resolve("data/history.db"))
    private val at = Instant.parse("2026-01-01T09:20:00Z")

    @Test
    fun `the first recording writes every skill of every player`() {
        assertEquals(3 * Skill.entries.size, history.record(at, gameData.allSkills()))
        assertEquals(SkillValue(experience = 4616, level = 10), history.latest()[1 to Skill.HITS])
    }

    @Test
    fun `nothing changed writes nothing`() {
        history.record(at, gameData.allSkills())
        assertEquals(0, history.record(at + 1.minutes, gameData.allSkills()))
    }

    @Test
    fun `a change writes one row with the full new value`() {
        history.record(at, gameData.allSkills())
        sql("UPDATE experience SET woodcut = 12450 WHERE playerID = 2")
        sql("UPDATE maxstats SET woodcut = 22 WHERE playerID = 2")

        assertEquals(1, history.record(at + 1.minutes, gameData.allSkills()))
        assertEquals(
            listOf(at to SkillValue(0, 1), at + 1.minutes to SkillValue(12450, 22)),
            history.values(2, Skill.WOODCUT),
        )
        assertEquals(SkillValue(12450, 22), history.latest()[2 to Skill.WOODCUT])
    }

    @Test
    fun `compares with what was recorded, not with the last reading`() {
        history.record(at, gameData.allSkills())
        // A reading that was never recorded (say the ledger stopped) doesn't hide a change.
        val unchanged = gameData.allSkills()
        sql("UPDATE experience SET mining = 900 WHERE playerID = 3")
        assertEquals(0, history.record(at + 1.minutes, unchanged))
        assertEquals(1, history.record(at + 2.minutes, gameData.allSkills()))
    }

    @Test
    fun `a new player has every skill recorded`() {
        history.record(at, gameData.allSkills())
        val newcomer = PlayerSkills(playerId = 9, skills = Skill.entries.associateWith { SkillValue(0, 1) })
        assertEquals(Skill.entries.size, history.record(at + 1.minutes, gameData.allSkills() + newcomer))
    }

    @Test
    fun `history survives reopening the file`() {
        history.record(at, gameData.allSkills())
        val reopened = HistoryStore(dir.resolve("data/history.db"))
        assertEquals(0, reopened.record(at + 1.minutes, gameData.allSkills()))
    }

    private fun sql(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:$game").use { it.createStatement().executeUpdate(statement) }
    }
}
