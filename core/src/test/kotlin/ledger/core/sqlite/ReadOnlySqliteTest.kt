package ledger.core.sqlite

import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReadOnlySqliteTest {
    private val dir: Path = createTempDirectory("ledger-test")
    private val database = Fixtures.database(dir.resolve("game.db"))

    private val writes = listOf(
        "INSERT INTO players (username, pass) VALUES ('Fernhallow', 'x')",
        "UPDATE players SET username = 'Fernhallow'",
        "DELETE FROM players",
        "CREATE TABLE notes (text TEXT)",
        "DROP TABLE players",
    )

    private fun sha256(file: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))

    private fun Connection.playerCount(): Int = createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM players").use { rows -> rows.next(); rows.getInt(1) }
    }

    /** Runs [block] and checks that the database file, and the folder it is in, are just as they were. */
    private fun assertUnchanged(block: () -> Unit) {
        val bytes = sha256(database)
        val modified = Files.getLastModifiedTime(database)
        val files = dir.listDirectoryEntries().sorted()

        block()

        assertContentEquals(bytes, sha256(database))
        assertEquals(modified, Files.getLastModifiedTime(database))
        assertEquals(files, dir.listDirectoryEntries().sorted())
    }

    @Test
    fun `reads work`() {
        assertEquals(3, openReadOnly(database).use { it.playerCount() })
    }

    @Test
    fun `every kind of write fails and leaves the file as it was`() = assertUnchanged {
        openReadOnly(database).use { connection ->
            for (sql in writes) {
                assertFailsWith<SQLException>(sql) { connection.createStatement().use { it.executeUpdate(sql) } }
            }
            assertEquals(3, connection.playerCount())
        }
    }

    @Test
    fun `writes still fail after trying to make the connection writable`() = assertUnchanged {
        openReadOnly(database).use { connection ->
            // The driver may refuse the change outright; either way, no write may get through.
            runCatching { connection.isReadOnly = false }
            for (sql in writes) {
                assertFailsWith<SQLException>(sql) { connection.createStatement().use { it.executeUpdate(sql) } }
            }
        }
    }

    @Test
    fun `writes inside a transaction fail too`() = assertUnchanged {
        openReadOnly(database).use { connection ->
            connection.autoCommit = false
            for (sql in writes) {
                assertFailsWith<SQLException>(sql) { connection.createStatement().use { it.executeUpdate(sql) } }
            }
            connection.rollback()
        }
    }
}
