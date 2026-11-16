package ledger.core.history

import ledger.core.sqlite.openReadOnly
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.time.Instant

/**
 * How much history a history file holds: [values] recorded skill values, for [players]
 * players, saved from [oldest] to [newest] (both null when nothing is recorded yet).
 */
data class HistorySummary(val values: Int, val players: Int, val oldest: Instant?, val newest: Instant?) {
    companion object {
        /** A file with no history in it yet. */
        val EMPTY = HistorySummary(values = 0, players = 0, oldest = null, newest = null)

        /**
         * Reads how much history [file] holds without changing it: it's opened read-only, and a
         * missing file is null, never created. A file [HistoryStore] would refuse, or whose table
         * isn't the one this ledger reads, is an [IllegalArgumentException] naming it.
         */
        fun read(file: Path): HistorySummary? {
            if (!Files.exists(file)) { return null }
            require(Files.isRegularFile(file)) { "The history file $file isn't a file" }
            require(Files.size(file) == 0L || HistoryStore.hasSqliteHeader(file)) { "The history file $file isn't a SQLite database" }

            return try {
                openReadOnly(file).use { connection ->
                    val hasTable = connection.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?").use { query ->
                        query.setString(1, SkillHistory.tableName)
                        query.executeQuery().use { rows -> rows.next(); rows.getInt(1) > 0 }
                    }
                    if (!hasTable) { return EMPTY }

                    connection.createStatement().use { statement ->
                        // Names every column the ledger reads, so a table it can't read fails here.
                        val columns = SkillHistory.columns.joinToString { it.name }
                        statement.executeQuery("SELECT $columns FROM ${SkillHistory.tableName} LIMIT 0").close()

                        val playerId = SkillHistory.playerId.name
                        val recordedAt = SkillHistory.recordedAt.name
                        val sql = "SELECT count(*), count(DISTINCT $playerId), min($recordedAt), max($recordedAt) FROM ${SkillHistory.tableName}"
                        statement.executeQuery(sql).use { rows ->
                            rows.next()
                            HistorySummary(
                                values = rows.getInt(1),
                                players = rows.getInt(2),
                                oldest = rows.getLong(3).takeUnless { rows.wasNull() }?.let(Instant::fromEpochMilliseconds),
                                newest = rows.getLong(4).takeUnless { rows.wasNull() }?.let(Instant::fromEpochMilliseconds),
                            )
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalArgumentException("Can't read the history file $file: ${e.message}", e)
            }
        }
    }
}
