package ledger.core.history

import ledger.core.game.PlayerSkills
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Instant

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
 */
class HistoryStore(file: Path) : HistoryReader {
    private val database: Database

    init {
        Files.createDirectories(file.toAbsolutePath().parent)
        database = Database.connect("jdbc:sqlite:$file", driver = "org.sqlite.JDBC")
        transaction(database) { SchemaUtils.create(SkillHistory) }
    }

    /**
     * Records, as at [at], each skill in [current] whose value differs from the newest one
     * recorded for that player, or that has none yet. Returns how many rows it wrote.
     *
     * It compares with what's recorded rather than with the previous copy, so changes
     * made while the ledger was stopped, or at a copy whose recording failed, are caught
     * at the next one.
     */
    fun record(at: Instant, current: List<PlayerSkills>): Int = transaction(database) {
        val newest = newestValues()
        val changes = current.flatMap { player ->
            player.skills.filter { (skill, value) -> newest[player.playerId to skill] != value }
                .map { (skill, value) -> Triple(player.playerId, skill, value) }
        }
        SkillHistory.batchInsert(changes, shouldReturnGeneratedValues = false) { (playerId, skill, value) ->
            this[SkillHistory.playerId] = playerId
            this[SkillHistory.skill] = skill.column
            this[SkillHistory.experience] = value.experience
            this[SkillHistory.level] = value.level
            this[SkillHistory.recordedAt] = at.toEpochMilliseconds()
        }
        changes.size
    }

    /** The newest recorded value of every player's every skill. */
    fun latest(): Map<Pair<Int, Skill>, SkillValue> = transaction(database) { newestValues() }

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

    override fun changes(from: Instant, to: Instant): List<SkillChange> = transaction(database) {
        val fromMillis = from.toEpochMilliseconds()
        val toMillis = to.toEpochMilliseconds()
        val atStart = newestValues { SkillHistory.recordedAt lessEq fromMillis }
        val atEnd = newestValues { SkillHistory.recordedAt lessEq toMillis }
        val firstInPeriod = groupedValues(SkillHistory.recordedAt.min()) {
            (SkillHistory.recordedAt greater fromMillis) and (SkillHistory.recordedAt lessEq toMillis)
        }
        atEnd.mapNotNull { (key, end) ->
            val start = atStart[key] ?: firstInPeriod[key] ?: return@mapNotNull null
            if (start == end) { return@mapNotNull null }
            SkillChange(playerId = key.first, skill = key.second, start = start, end = end)
        }
    }

    /** Each player's and skill's newest value among the rows matching [where], or among all rows. */
    private fun newestValues(where: (() -> Op<Boolean>)? = null) = groupedValues(SkillHistory.recordedAt.max(), where)

    /**
     * Each player's and skill's value from the row whose time is picked by [pick], the
     * MAX() or MIN() of `recorded_at`. In SQLite, a query grouping with MAX() or MIN()
     * takes the other columns from the row holding that maximum or minimum.
     */
    private fun groupedValues(pick: Expression<Long?>, where: (() -> Op<Boolean>)? = null): Map<Pair<Int, Skill>, SkillValue> {
        val query = SkillHistory.select(SkillHistory.playerId, SkillHistory.skill, SkillHistory.experience, SkillHistory.level, pick)
        if (where != null) { query.where(where) }
        return query.groupBy(SkillHistory.playerId, SkillHistory.skill)
            .associate { row ->
                (row[SkillHistory.playerId] to skillsByColumn.getValue(row[SkillHistory.skill])) to
                    SkillValue(row[SkillHistory.experience], row[SkillHistory.level])
            }
    }

    private companion object {
        val skillsByColumn = Skill.entries.associateBy { it.column }
    }
}
