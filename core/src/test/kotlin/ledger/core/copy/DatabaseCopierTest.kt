package ledger.core.copy

import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DatabaseCopierTest {
    private val dir: Path = createTempDirectory("ledger-test")
    private val live = Fixtures.database(dir.resolve("live.db"))
    private val copier = DatabaseCopier(live, dir.resolve("data/server-copy.db"))

    private fun open(file: Path): Connection = DriverManager.getConnection("jdbc:sqlite:$file")

    private fun Connection.playerNames(): List<String> = createStatement().use { statement ->
        statement.executeQuery("SELECT username FROM players ORDER BY id").use { rows ->
            buildList { while (rows.next()) { add(rows.getString(1)) } }
        }
    }

    private fun sha256(file: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))

    @Test
    fun `copies the database, leaving no temporary file`() {
        copier.copy()
        assertEquals(listOf("Bramblewick", "Tansy Moss", "Oakhollow"), open(copier.copy).use { it.playerNames() })
        assertEquals(listOf(copier.copy), copier.copy.parent.listDirectoryEntries())
    }

    @Test
    fun `never changes the live database`() {
        val before = sha256(live)
        val modified = Files.getLastModifiedTime(live)
        copier.copy()
        assertContentEquals(before, sha256(live))
        assertEquals(modified, Files.getLastModifiedTime(live))
    }

    @Test
    fun `a new copy replaces the old, while a reader of the old one carries on`() {
        copier.copy()
        open(copier.copy).use { reader ->
            reader.autoCommit = false
            assertEquals(3, reader.playerNames().size)

            open(live).use { it.createStatement().executeUpdate("INSERT INTO players (username, pass) VALUES ('Fernhallow', 'x')") }
            copier.copy()

            // The open connection still sees the file it opened...
            assertEquals(3, reader.playerNames().size)
        }
        // ...and anything opened now sees the new copy.
        assertEquals(4, open(copier.copy).use { it.playerNames() }.size)
    }
}
