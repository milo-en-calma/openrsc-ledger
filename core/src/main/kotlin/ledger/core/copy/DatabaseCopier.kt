package ledger.core.copy

import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteErrorCode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.sql.Connection
import java.sql.DriverManager

/**
 * Copies the server's live database to [copy], which is what every page reads.
 *
 * The server's database uses SQLite's `delete` journal mode, in which a reader blocks the
 * server from saving until the read ends. So the ledger never queries the live file: it
 * copies it with SQLite's backup function, which reads a few pages at a time and lets go
 * of the file between steps, so a save waits a few milliseconds at most. The server waits
 * up to three seconds for a lock before failing, its driver's default.
 *
 * The copy is written to a temporary file, checked, then renamed over [copy] in one step,
 * so nothing ever reads a half-written copy.
 */
class DatabaseCopier(private val live: Path, val copy: Path) {
    fun copy() {
        Files.createDirectories(copy.toAbsolutePath().parent)
        val temp = copy.resolveSibling("${copy.fileName}.tmp")
        Files.deleteIfExists(temp)

        openReadOnly(live).use { connection ->
            val result = connection.unwrap(SQLiteConnection::class.java).database.backup(
                "main",
                temp.toString(),
                null,
                BUSY_WAIT_MILLIS,
                BUSY_RETRIES,
                PAGES_PER_STEP,
            )
            check(result == SQLiteErrorCode.SQLITE_OK.code) { "Copying $live failed with SQLite result code $result" }
        }

        val check = openReadOnly(temp).use { connection ->
            connection.createStatement().use { it.executeQuery("PRAGMA quick_check").use { rows -> rows.next(); rows.getString(1) } }
        }
        check(check == "ok") { "The copy of $live is damaged: $check" }

        Files.move(temp, copy, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private companion object {
        /** About 128 KB a step with the server's 4 KB pages. */
        const val PAGES_PER_STEP = 32
        /** While the server is saving, wait this long and try again... */
        const val BUSY_WAIT_MILLIS = 100
        /** ...this many times (three seconds) before giving up until the next check. */
        const val BUSY_RETRIES = 30

        /** Opens [file] so that nothing done through the connection can change it. */
        fun openReadOnly(file: Path): Connection {
            val config = SQLiteConfig().apply { setReadOnly(true) }
            return DriverManager.getConnection("jdbc:sqlite:$file", config.toProperties())
        }
    }
}
