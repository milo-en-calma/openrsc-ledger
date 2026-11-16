package ledger.core.history

import ledger.core.game.PlayerSkills
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * One row per change: a player's skill took a new value at a time. Each row holds the
 * full new value, never the difference, so a latest value is one lookup and a gain over
 * a period is the latest value minus the last one before it began.
 */
internal object SkillHistory : Table("skill_history") {
    val playerId = integer("player_id")
    /** The skill's column name in the server's tables, such as `woodcut`. */
    val skill = varchar("skill", 16)
    /** Experience as the server stores it. */
    val experience = integer("experience")
    /** The full level, before boosts and drains. */
    val level = integer("level")
    /** When the server saved the value: milliseconds since 1970. */
    val recordedAt = long("recorded_at")

    init {
        index(isUnique = true, playerId, skill, recordedAt)
    }
}

/**
 * The ledger's own record of how every player's skills changed over time, kept in [file],
 * a SQLite database of its own. The server keeps only the present, so this is the only
 * place the past survives. Nothing here ever writes to the server's database.
 *
 * A [file] that can't be used (its folder is a file or can't be written, or it isn't a
 * SQLite database) is refused with an [IllegalArgumentException] naming it.
 */
class HistoryStore(file: Path) : HistoryReader {
    private val database: Database

    init {
        val folder = file.toAbsolutePath().parent
        try {
            Files.createDirectories(folder)
        } catch (e: FileAlreadyExistsException) {
            throw IllegalArgumentException("Can't make the folder $folder for the history file: ${e.file} is a file, not a folder", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("Can't make the folder $folder for the history file: $e", e)
        }
        // SQLite's own error for a file that isn't a database is lost on the way ("connection closed").
        require(!isNonEmptyFile(file) || hasSqliteHeader(file)) { "The history file $file isn't a SQLite database" }

        database = Database.connect("jdbc:sqlite:$file", driver = "org.sqlite.JDBC")
        try {
            transaction(database) { SchemaUtils.create(SkillHistory) }
        } catch (e: SQLException) {
            throw IllegalArgumentException("Can't open or create the history file $file: ${e.message}", e)
        }
    }

    /**
     * The newest recorded value of every player's every skill, read from the file once, here,
     * and kept up to date by [record], so recording a copy never rereads the file. Only this
     * object writes the file. Guarded by itself.
     */
    private val newest = mutableMapOf<Pair<Int, Skill>, SkillValue>()

    /** When the newest recorded value was saved, or null before anything is recorded; kept like [newest], and guarded by it. */
    private var newestTime: Instant? = null

    init {
        val started = TimeSource.Monotonic.markNow()
        transaction(database) { readNewest() }
        log.info(
            "Read the newest values of {} player skills from {} ({} MB) in {}",
            newest.size, file, "%.1f".format(Files.size(file) / 1_000_000.0), started.elapsedNow().toString(DurationUnit.SECONDS, 1),
        )
    }

    /**
     * Records, as at [at], each skill in [current] whose value differs from the newest one
     * recorded for that player, or that has none yet. Returns how many rows it wrote.
     *
     * It compares with what's recorded rather than with the previous copy, so changes
     * made while the ledger was stopped, or at a copy whose recording failed, are caught
     * at the next one: the newest values in memory change only once a write has committed.
     *
     * Changes stamped at or before the newest recording aren't recorded, so history is never
     * written out of order; a warning says so. That happens when the server's database file
     * goes back in time, such as one restored from a backup that kept its old time. It heals
     * itself: the server's next save is stamped later, and records whatever still differs.
     */
    fun record(at: Instant, current: List<PlayerSkills>): Int = synchronized(newest) {
        val changes = current.flatMap { player ->
            player.skills.filter { (skill, value) -> newest[player.playerId to skill] != value }
                .map { (skill, value) -> Triple(player.playerId, skill, value) }
        }
        if (changes.isEmpty()) { return 0 }

        val written = transaction(database) {
            val newestTime = newestTime
            if (newestTime != null && at <= newestTime) {
                log.warn(
                    "The server's database was saved at {}, not after the newest recording at {}, so its {} skill changes weren't recorded; " +
                        "the server's next save will record whatever still differs",
                    at, newestTime, changes.size,
                )
                return@transaction false
            }

            SkillHistory.batchInsert(changes, shouldReturnGeneratedValues = false) { (playerId, skill, value) ->
                this[SkillHistory.playerId] = playerId
                this[SkillHistory.skill] = skill.column
                this[SkillHistory.experience] = value.experience
                this[SkillHistory.level] = value.level
                this[SkillHistory.recordedAt] = at.toEpochMilliseconds()
            }
            true
        }
        if (!written) { return 0 }
        changes.forEach { (playerId, skill, value) -> newest[playerId to skill] = value }
        newestTime = at
        changes.size
    }

    /** The newest recorded value of every player's every skill. */
    fun latest(): Map<Pair<Int, Skill>, SkillValue> = synchronized(newest) { newest.toMap() }

    /** Every value recorded for one player's skill, oldest first. */
    fun values(playerId: Int, skill: Skill): List<Pair<Instant, SkillValue>> = transaction(database) {
        SkillHistory.selectAll()
            .where { (SkillHistory.playerId eq playerId) and (SkillHistory.skill eq skill.column) }
            .orderBy(SkillHistory.recordedAt to SortOrder.ASC)
            .map { Instant.fromEpochMilliseconds(it[SkillHistory.recordedAt]) to SkillValue(it[SkillHistory.experience], it[SkillHistory.level]) }
    }

    override fun recordedSince(): Instant? = transaction(database) {
        val oldest = SkillHistory.recordedAt.min()
        SkillHistory.select(oldest).single()[oldest]?.let { Instant.fromEpochMilliseconds(it) }
    }

    override fun changes(from: Instant, to: Instant): List<SkillChange> = changes(from, to, null, null)

    /**
     * Each recorded player's and skill's change between [from] and [to], as [HistoryReader]
     * says, for [playerId] and [skill] where given, in order of player and skill. One query
     * ([changesSql]) that jumps through the table's (player, skill, time) index: from one player
     * and skill to the next, then to the three rows it needs for each, so its time follows the
     * number of players and skills, not the number of rows recorded.
     */
    override fun changes(from: Instant, to: Instant, playerId: Int?, skill: Skill?): List<SkillChange> = transaction(database) {
        val connection = this.connection.connection as Connection
        connection.prepareStatement(changesSql(onePlayer = playerId != null, oneSkill = skill != null)).use { statement ->
            val fromMillis = from.toEpochMilliseconds()
            val toMillis = to.toEpochMilliseconds()
            val parameters = listOfNotNull(playerId?.toLong()) + listOf(toMillis, fromMillis, fromMillis, toMillis)
            parameters.forEachIndexed { index, value -> statement.setLong(index + 1, value) }
            if (skill != null) { statement.setString(parameters.size + 1, skill.column) }
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val rowSkill = skillsByColumn.getValue(rows.getString("skill"))
                        val end = SkillValue(rows.getInt("end_experience"), rows.getInt("end_level"))
                        val start = rows.value("start_experience", "start_level") ?: rows.value("first_experience", "first_level") ?: continue
                        if (start == end) { continue }
                        add(SkillChange(playerId = rows.getInt("player_id"), skill = rowSkill, start = start, end = end))
                    }
                }
            }
        }
    }

    /** The value in columns [experience] and [level] of this row, or null where the row joined to nothing. */
    private fun ResultSet.value(experience: String, level: String): SkillValue? {
        val value = getInt(experience)
        return if (wasNull()) null else SkillValue(value, getInt(level))
    }

    /**
     * Fills [newest] with each player's and skill's newest value, and [newestTime] with the
     * newest of their times, in one query ([NEWEST_SQL]) that jumps through the index pair by
     * pair, so it follows the number of players and skills, not the number of rows. Runs once,
     * when the store opens, inside a transaction.
     */
    private fun JdbcTransaction.readNewest() {
        val connection = this.connection.connection as Connection
        connection.prepareStatement(NEWEST_SQL).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    newest[rows.getInt("player_id") to skillsByColumn.getValue(rows.getString("skill"))] =
                        SkillValue(rows.getInt("experience"), rows.getInt("level"))
                    val at = Instant.fromEpochMilliseconds(rows.getLong("recorded_at"))
                    if (newestTime.let { it == null || at > it }) { newestTime = at }
                }
            }
        }
    }

    internal companion object {
        /**
         * The distinct (player, skill) pairs, walked through the index, each step one jump: to
         * the same player's next skill, or else to the next player's first. For [onePlayer], it
         * starts at the player given as the first parameter and never steps on to another.
         */
        private fun pairsSql(onePlayer: Boolean): String {
            val first = if (onePlayer) "player_id = ?" else "player_id >= (SELECT MIN(player_id) FROM skill_history)"
            val nextPlayer = if (onePlayer) "NULL" else "(SELECT rowid FROM skill_history WHERE player_id > pairs.player_id ORDER BY player_id, skill LIMIT 1)"
            return """
            WITH RECURSIVE pairs(player_id, skill) AS (
                SELECT * FROM (SELECT player_id, skill FROM skill_history
                    WHERE $first ORDER BY player_id, skill LIMIT 1)
                UNION ALL
                SELECT next.player_id, next.skill FROM pairs JOIN skill_history next ON next.rowid = coalesce(
                    (SELECT rowid FROM skill_history WHERE player_id = pairs.player_id AND skill > pairs.skill ORDER BY skill LIMIT 1),
                    $nextPlayer
                )
            )"""
        }

        /** How [readNewest] reads the history: [pairsSql], then each pair's newest row, one jump each. */
        val NEWEST_SQL = pairsSql(onePlayer = false) + """
            SELECT n.player_id, n.skill, n.experience, n.level, n.recorded_at
            FROM pairs JOIN skill_history n ON n.rowid = (
                SELECT rowid FROM skill_history h WHERE h.player_id = pairs.player_id AND h.skill = pairs.skill
                ORDER BY h.recorded_at DESC LIMIT 1
            )
            """

        /**
         * How [changes] reads the history. `pairs` is [pairsSql], for [onePlayer] starting at
         * that player. `rows` then jumps, for each pair (of one skill, for [oneSkill]), to its newest
         * row at or before `to` (the end), its newest at or before `from` (the start), and its
         * first inside the period (the start, for a skill first recorded then). The parameters,
         * in order: the player where one is asked for, then `to`, `from`, `from`, `to`, then the
         * skill where one is asked for.
         */
        fun changesSql(onePlayer: Boolean, oneSkill: Boolean): String {
            val skill = if (oneSkill) "WHERE pairs.skill = ?" else ""
            return pairsSql(onePlayer) + """,
            rows AS (
                SELECT player_id, skill,
                    (SELECT rowid FROM skill_history h WHERE h.player_id = pairs.player_id AND h.skill = pairs.skill AND h.recorded_at <= ?
                        ORDER BY h.recorded_at DESC LIMIT 1) AS end_row,
                    (SELECT rowid FROM skill_history h WHERE h.player_id = pairs.player_id AND h.skill = pairs.skill AND h.recorded_at <= ?
                        ORDER BY h.recorded_at DESC LIMIT 1) AS start_row,
                    (SELECT rowid FROM skill_history h WHERE h.player_id = pairs.player_id AND h.skill = pairs.skill AND h.recorded_at > ? AND h.recorded_at <= ?
                        ORDER BY h.recorded_at LIMIT 1) AS first_row
                FROM pairs $skill
            )
            SELECT rows.player_id, rows.skill,
                e.experience AS end_experience, e.level AS end_level,
                s.experience AS start_experience, s.level AS start_level,
                f.experience AS first_experience, f.level AS first_level
            FROM rows
            JOIN skill_history e ON e.rowid = rows.end_row
            LEFT JOIN skill_history s ON s.rowid = rows.start_row
            LEFT JOIN skill_history f ON f.rowid = rows.first_row
            ORDER BY rows.player_id, rows.skill
            """
        }

        private val skillsByColumn = Skill.entries.associateBy { it.column }
        private val log = LoggerFactory.getLogger(HistoryStore::class.java)

        /** The first 16 bytes of every SQLite database file. */
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        private fun isNonEmptyFile(file: Path): Boolean = Files.isRegularFile(file) && Files.size(file) > 0

        /** Whether [file] starts as every SQLite database does; one that can't be read is an [IllegalArgumentException]. */
        fun hasSqliteHeader(file: Path): Boolean = try {
            Files.newInputStream(file).use { it.readNBytes(SQLITE_HEADER.size) }.contentEquals(SQLITE_HEADER)
        } catch (e: IOException) {
            throw IllegalArgumentException("Can't read the history file $file: $e", e)
        }
    }
}
