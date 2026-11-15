package ledger.core.game

import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun sql(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().executeUpdate(statement) }
    }
}
