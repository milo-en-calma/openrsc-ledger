package ledger.core.game

import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillTest {
    /** The server's `experience`, `maxstats` and `curstats` columns after `playerID`, in its schema's order. */
    private val serverColumns = listOf(
        "attack", "defense", "strength", "hits", "ranged", "prayer", "magic", "cooking", "woodcut", "fletching",
        "fishing", "firemaking", "crafting", "smithing", "mining", "herblaw", "agility", "thieving", "harvesting", "runecraft",
    )

    @Test
    fun `one skill per server column, in the server's order`() {
        assertEquals(serverColumns, Skill.entries.map { it.column })
    }

    @Test
    fun `each skill has the long name the game shows`() {
        assertEquals(
            listOf(
                "Attack", "Defense", "Strength", "Hits", "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting", "Fletching",
                "Fishing", "Firemaking", "Crafting", "Smithing", "Mining", "Herblaw", "Agility", "Thieving", "Harvesting", "Runecraft",
            ),
            Skill.entries.map { it.displayName },
        )
    }

    @Test
    fun `the test database's skill tables have exactly the skills' columns`() {
        val file = Fixtures.database(Files.createTempDirectory("skills").resolve("copy.db"))
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            for (table in listOf("experience", "maxstats", "curstats")) {
                val columns = connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                        buildList { while (rows.next()) { add(rows.getString("name")) } }
                    }
                }
                assertEquals(listOf("playerID") + serverColumns, columns, table)
            }
        }
    }

    @Test
    fun `experience shown rounds down at every remainder`() {
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 2), (0..8).map(::shownExperience))
        // A new player's Hits: 4616 stored, 1154 in game, exactly level 10.
        assertEquals(1154, shownExperience(4616))
    }

    @Test
    fun `experience shown at the ends of the stored range`() {
        // The largest positive stored value...
        assertEquals(0x1fffffff, shownExperience(Int.MAX_VALUE))
        // ...and the negative ones, which the client reads as unsigned, so they come out above it.
        assertEquals(0x20000000, shownExperience(Int.MIN_VALUE))
        assertEquals(0x3fffffff, shownExperience(-4))
        assertEquals(0x3fffffff, shownExperience(-1))
    }
}
