package ledger.core.game

import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class SqliteGameDataTest {
    private val dir: Path = Files.createTempDirectory("game-data")
    private val file: Path = Fixtures.database(dir.resolve("copy.db"))
    private val gameData = SqliteGameData(file)

    @Test
    fun `lists every player by name, ignoring case`() {
        assertEquals(listOf("Bramblewick", "Oakhollow", "Tansy Moss"), gameData.players().map { it.name })
    }

    @Test
    fun `reads each player's summary`() {
        val bramblewick = gameData.players().first { it.name == "Bramblewick" }
        assertEquals(
            PlayerSummary(
                id = 1,
                name = "Bramblewick",
                combatLevel = 32,
                totalLevel = 210,
                lastLogin = Instant.fromEpochSeconds(1730000000),
                online = false,
            ),
            bramblewick,
        )
        assertEquals(true, gameData.players().first { it.name == "Oakhollow" }.online)
    }

    @Test
    fun `a player who never logged in has no last login`() {
        sql("UPDATE players SET login_date = 0 WHERE id = 2")
        assertNull(gameData.players().first { it.name == "Tansy Moss" }.lastLogin)
    }

    @Test
    fun `search matches part of a name, ignoring case and surrounding spaces`() {
        assertEquals(listOf("Oakhollow"), gameData.players("HOLL").map { it.name })
        assertEquals(listOf("Tansy Moss"), gameData.players("  y m ").map { it.name })
        assertEquals(3, gameData.players("  ").size)
        assertEquals(emptyList(), gameData.players("nobody"))
    }

    @Test
    fun `search takes wildcard characters literally`() {
        assertEquals(emptyList(), gameData.players("%"))
        assertEquals(emptyList(), gameData.players("_"))
        sql("UPDATE players SET username = 'Fern_100%' WHERE id = 2")
        assertEquals(listOf("Fern_100%"), gameData.players("n_100%").map { it.name })
    }

    @Test
    fun `reads the newest copy after it is renamed over the old one`() {
        assertEquals(3, gameData.players().size)
        val next = Fixtures.database(dir.resolve("next.db"))
        DriverManager.getConnection("jdbc:sqlite:$next").use { it.createStatement().executeUpdate("DELETE FROM players WHERE id = 3") }
        Files.move(next, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(2, gameData.players().size)
    }

    @Test
    fun `reads one player's skills and quests`() {
        sql("UPDATE experience SET woodcut = 49800 WHERE playerID = 1")
        sql("UPDATE maxstats SET woodcut = 30 WHERE playerID = 1")
        sql("UPDATE curstats SET woodcut = 33 WHERE playerID = 1")

        val player = assertNotNull(gameData.player(1))
        assertEquals("Bramblewick", player.summary.name)
        assertEquals(7, player.questPoints)
        assertEquals(Skill.entries.toSet(), player.skills.keys)
        assertEquals(SkillStanding(level = 33, fullLevel = 30, storedExperience = 49800), player.skills[Skill.WOODCUT])
        assertEquals(SkillStanding(level = 10, fullLevel = 10, storedExperience = 4616), player.skills[Skill.HITS])
        assertEquals(listOf(QuestProgress(0, -1), QuestProgress(3, 2)), player.quests)
        assertEquals(listOf(true, false), player.quests.map { it.complete })
    }

    @Test
    fun `a player with no quests has none`() {
        assertEquals(emptyList(), assertNotNull(gameData.player(2)).quests)
    }

    @Test
    fun `there is no player with an unknown id`() {
        assertNull(gameData.player(99))
    }

    @Test
    fun `experience shown is a quarter of what's stored, rounded down`() {
        // A new player's Hits: 4616 stored, 1154 in game, exactly level 10.
        assertEquals(1154, shownExperience(4616))
        assertEquals(83, shownExperience(335))
        assertEquals(0, shownExperience(3))
        // The client reads the stored number as unsigned.
        assertEquals(0x3fffffff, shownExperience(-1))
    }

    @Test
    fun `reads a player's inventory and bank by slot, with what each item is`() {
        val player = assertNotNull(gameData.player(1))
        assertEquals(
            listOf(
                HeldItem(slot = 0, itemId = 0, amount = 1, noted = false, worn = true),
                HeldItem(slot = 1, itemId = 4, amount = 1, noted = false, worn = true),
                HeldItem(slot = 2, itemId = 10, amount = 1250, noted = false, worn = false),
                HeldItem(slot = 3, itemId = 156, amount = 1, noted = false, worn = false),
            ),
            player.inventory,
        )
        assertEquals(
            listOf(
                HeldItem(slot = 0, itemId = 10, amount = 50000, noted = false, worn = false),
                HeldItem(slot = 1, itemId = 14, amount = 120, noted = true, worn = false),
                HeldItem(slot = 2, itemId = 373, amount = 1, noted = false, worn = false),
            ),
            player.bank,
        )
    }

    @Test
    fun `a player with nothing has an empty inventory and bank`() {
        val player = assertNotNull(gameData.player(2))
        assertEquals(emptyList(), player.inventory)
        assertEquals(emptyList(), player.bank)
    }

    private fun sql(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().executeUpdate(statement) }
    }
}
