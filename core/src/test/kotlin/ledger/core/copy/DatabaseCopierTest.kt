package ledger.core.copy

import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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

    @Test
    fun `creates every missing folder above the copy`() {
        val deep = DatabaseCopier(live, dir.resolve("a/b/c/server-copy.db"))
        deep.copy()
        assertEquals(3, open(deep.copy).use { it.playerNames() }.size)
    }

    @Test
    fun `deletes a temporary file left by an earlier copy that never finished`() {
        Files.createDirectories(copier.copy.parent)
        Files.writeString(copier.copy.resolveSibling("server-copy.db.tmp"), "half a copy")
        copier.copy()
        assertEquals(listOf(copier.copy), copier.copy.parent.listDirectoryEntries())
        assertEquals(3, open(copier.copy).use { it.playerNames() }.size)
    }

    @Test
    fun `a missing live database fails without creating one`() {
        val missing = dir.resolve("missing.db")
        val failure = assertFailsWith<SQLException> { DatabaseCopier(missing, copier.copy).copy() }
        assertContains(failure.message.orEmpty(), "unable to open")
        assertFalse(Files.exists(missing))
        assertFalse(Files.exists(copier.copy))
    }

    @Test
    fun `a live database gone missing keeps the last good copy`() {
        copier.copy()
        val before = sha256(copier.copy)
        Files.delete(live)
        assertFailsWith<SQLException> { copier.copy() }
        assertContentEquals(before, sha256(copier.copy))
        assertFalse(Files.exists(live))
    }

    @Test
    fun `a live file that isn't a database fails and keeps the last good copy`() {
        copier.copy()
        val before = sha256(copier.copy)
        Files.writeString(live, "not a database, only text ".repeat(200))
        val failure = assertFailsWith<IllegalStateException> { copier.copy() }
        assertContains(failure.message.orEmpty(), "failed with SQLite result code 26")
        assertContentEquals(before, sha256(copier.copy))
        assertEquals(listOf(copier.copy), copier.copy.parent.listDirectoryEntries())
    }

    @Test
    fun `an empty live file is refused rather than copied as an empty database`() {
        copier.copy()
        val before = sha256(copier.copy)
        Files.write(live, ByteArray(0))
        val failure = assertFailsWith<IllegalStateException> { copier.copy() }
        assertEquals("The copy of $live has no tables; is the live file empty?", failure.message)
        assertContentEquals(before, sha256(copier.copy))
        assertEquals(listOf(copier.copy), copier.copy.parent.listDirectoryEntries())
    }

    @Test
    fun `a damaged page in the live database is refused and the last good copy kept`() {
        copier.copy()
        val before = sha256(copier.copy)
        // Overwrite the second page (the first table's) past its header, leaving the file's own header intact.
        val bytes = Files.readAllBytes(live)
        val pageSize = ((bytes[16].toInt() and 0xff) shl 8) or (bytes[17].toInt() and 0xff)
        for (i in pageSize + 8 until pageSize * 2) { bytes[i] = 0x5a }
        Files.write(live, bytes)

        val failure = assertFailsWith<IllegalStateException> { copier.copy() }
        assertContains(failure.message.orEmpty(), "is damaged")
        assertContentEquals(before, sha256(copier.copy))
        assertEquals(listOf(copier.copy), copier.copy.parent.listDirectoryEntries())
    }

    @Test
    fun `a save the server has begun but not finished isn't in the copy`() {
        open(live).use { server ->
            // A reserved lock: the server is writing, but nothing is saved to the file yet.
            server.createStatement().execute("BEGIN IMMEDIATE")
            server.createStatement().executeUpdate("INSERT INTO players (username, pass) VALUES ('Fernhallow', 'x')")
            copier.copy()
            server.createStatement().execute("COMMIT")
        }
        assertEquals(listOf("Bramblewick", "Tansy Moss", "Oakhollow"), open(copier.copy).use { it.playerNames() })
    }

    @Test
    fun `waits for a save in progress, then copies it`() {
        val server = open(live)
        // An exclusive lock: the server is saving, and no one may read the file until it's done.
        server.createStatement().execute("BEGIN EXCLUSIVE")
        server.createStatement().executeUpdate("INSERT INTO players (username, pass) VALUES ('Fernhallow', 'x')")
        val finishSave = thread {
            Thread.sleep(500)
            server.createStatement().execute("COMMIT")
            server.close()
        }
        try {
            copier.copy()
        } finally {
            finishSave.join()
        }
        assertEquals(listOf("Bramblewick", "Tansy Moss", "Oakhollow", "Fernhallow"), open(copier.copy).use { it.playerNames() })
    }
}
