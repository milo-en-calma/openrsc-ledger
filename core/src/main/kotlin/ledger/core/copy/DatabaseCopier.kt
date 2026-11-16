package ledger.core.copy

import ledger.core.sqlite.openReadOnly
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteErrorCode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

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
 * so nothing ever reads a half-written copy. A copy that fails, is damaged or holds no
 * tables at all (an empty live file reads as an empty database) is deleted, and the last
 * good copy stays in place.
 */
class DatabaseCopier(private val live: Path, val copy: Path) {
    fun copy() {
        Files.createDirectories(copy.toAbsolutePath().parent)
        val temp = copy.resolveSibling("${copy.fileName}.tmp")
        Files.deleteIfExists(temp)

        try {
            backUp(temp)
            checkCopy(temp)
            Files.move(temp, copy, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun backUp(temp: Path) {
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
    }

    private fun checkCopy(temp: Path) {
        openReadOnly(temp).use { connection ->
            val check = connection.queryOne("PRAGMA quick_check") { it.getString(1) }
            check(check == "ok") { "The copy of $live is damaged: $check" }
            val tables = connection.queryOne("SELECT count(*) FROM sqlite_master WHERE type = 'table'") { it.getInt(1) }
            check(tables > 0) { "The copy of $live has no tables; is the live file empty?" }
        }
    }

    private fun <T> Connection.queryOne(sql: String, read: (ResultSet) -> T): T =
        createStatement().use { it.executeQuery(sql).use { rows -> rows.next(); read(rows) } }

    private companion object {
        /** About 128 KB a step with the server's 4 KB pages. */
        const val PAGES_PER_STEP = 32
        /** While the server is saving, wait this long and try again... */
        const val BUSY_WAIT_MILLIS = 100
        /**
         * ...this many times before giving up until the next check. With the driver's own
         * three-second wait on the first read before these retries start, a copy gives up
         * after about six seconds of the server holding its lock.
         */
        const val BUSY_RETRIES = 30
    }
}
