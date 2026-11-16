package ledger.core.history

import ledger.core.game.PlayerSkills
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The history file outlives every build of the ledger: a newer build opens the history.db an
 * older one wrote, and must read it as it is. These pin the file's table and index exactly,
 * so changing them is a deliberate step that also says how an older file is brought along.
 */
class HistoryLayoutTest {
    private val dir = Files.createTempDirectory("history-layout")

    /** The table and index every build so far has written, as SQLite keeps them. */
    private val layout = listOf(
        "table" to "CREATE TABLE skill_history (player_id INT NOT NULL, skill VARCHAR(16) NOT NULL, experience INT NOT NULL, " +
            "\"level\" INT NOT NULL, recorded_at BIGINT NOT NULL, " +
            "CONSTRAINT chk_skill_history_signed_integer_player_id CHECK (player_id BETWEEN -2147483648 AND 2147483647), " +
            "CONSTRAINT chk_skill_history_signed_integer_experience CHECK (experience BETWEEN -2147483648 AND 2147483647), " +
            "CONSTRAINT chk_skill_history_signed_integer_level CHECK (\"level\" BETWEEN -2147483648 AND 2147483647))",
        "index" to "CREATE UNIQUE INDEX skill_history_player_id_skill_recorded_at ON skill_history (player_id, skill, recorded_at)",
    )

    private fun layoutOf(file: Path): List<Pair<String, String>> =
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT type, sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type DESC, name").use { rows ->
                    buildList { while (rows.next()) { add(rows.getString(1) to rows.getString(2)) } }
                }
            }
        }

    @Test
    fun `a new history file has exactly the table and index every build has written`() {
        val file = dir.resolve("history.db")
        HistoryStore(file)
        assertEquals(layout, layoutOf(file))
    }

    @Test
    fun `a history file written by hand in that layout is read as it is, recorded onto, and left in that layout`() {
        val file = dir.resolve("history.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                layout.forEach { (_, sql) -> statement.executeUpdate(sql) }
                statement.executeUpdate("INSERT INTO skill_history VALUES (7, 'woodcut', 1000, 9, 1700000000000)")
                statement.executeUpdate("INSERT INTO skill_history VALUES (7, 'woodcut', 2000, 12, 1700000600000)")
            }
        }

        val history = HistoryStore(file)
        assertEquals(SkillValue(experience = 2000, level = 12), history.latest()[7 to Skill.WOODCUT])
        assertEquals(Instant.fromEpochMilliseconds(1700000000000), history.recordedSince())

        val later = Instant.fromEpochMilliseconds(1700001200000)
        val skills = Skill.entries.associateWith { SkillValue(experience = 0, level = 1) } + (Skill.WOODCUT to SkillValue(3000, 14))
        history.record(later, listOf(PlayerSkills(7, skills)))
        assertEquals(listOf(1700000000000, 1700000600000, 1700001200000).map(Instant::fromEpochMilliseconds), history.values(7, Skill.WOODCUT).map { it.first })
        assertEquals(layout, layoutOf(file))
    }
}
