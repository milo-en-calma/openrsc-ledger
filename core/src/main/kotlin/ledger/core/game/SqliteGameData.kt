package ledger.core.game

import ledger.core.sqlite.openReadOnly
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Path
import kotlin.time.Instant

/**
 * Reads the game's data from [file], the copy of the server's SQLite database.
 *
 * Every read opens a fresh read-only connection. Each new copy is renamed over the last,
 * and a connection keeps reading the file it opened, so a connection held open would go
 * on reading an old copy.
 */
class SqliteGameData(file: Path) : GameData {
    // Exposed sets each connection's read-only flag when a transaction starts, and the
    // SQLite driver refuses to change it once open, so the two must agree: read-only.
    private val database = Database.connect(
        getNewConnection = { openReadOnly(file) },
        databaseConfig = DatabaseConfig { defaultReadOnly = true },
    )

    override fun players(search: String?): List<PlayerSummary> = transaction(database) {
        val query = Players.selectAll()
        if (!search.isNullOrBlank()) {
            query.where { Players.username.lowerCase() like containing(search.trim().lowercase()) }
        }
        query.orderBy(Players.username.lowerCase()).map { it.toPlayerSummary() }
    }

    override fun allSkills(): List<PlayerSkills> = transaction(database) {
        Experience.join(MaxStats, JoinType.INNER, Experience.playerId, MaxStats.playerId)
            .selectAll()
            .orderBy(Experience.playerId)
            .map { row ->
                PlayerSkills(
                    playerId = row[Experience.playerId],
                    skills = Skill.entries.associateWith { skill ->
                        SkillValue(experience = row[Experience.skill.getValue(skill)], level = row[MaxStats.skill.getValue(skill)])
                    },
                )
            }
    }

    private fun ResultRow.toPlayerSummary() = PlayerSummary(
        id = this[Players.id],
        name = this[Players.username],
        combatLevel = this[Players.combat] ?: 0,
        totalLevel = this[Players.skillTotal] ?: 0,
        lastLogin = this[Players.loginDate]?.takeIf { it > 0 }?.let { Instant.fromEpochSeconds(it) },
        online = this[Players.online] == 1,
    )

    private companion object {
        const val ESCAPE = '\\'

        /** A LIKE pattern matching names that contain [text], with its own `%` and `_` taken literally. */
        fun containing(text: String): LikePattern {
            val escaped = text
                .replace("$ESCAPE", "$ESCAPE$ESCAPE")
                .replace("%", "$ESCAPE%")
                .replace("_", "${ESCAPE}_")
            return LikePattern("%$escaped%", ESCAPE)
        }
    }
}
