package ledger.admin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.sql.Connection
import java.sql.DriverManager
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** The skill columns of the server's `curstats`, `maxstats` and `experience` tables. */
val SKILL_COLUMNS = listOf(
    "attack", "defense", "strength", "hits", "ranged", "prayer", "magic", "cooking", "woodcut", "fletching",
    "fishing", "firemaking", "crafting", "smithing", "mining", "herblaw", "agility", "thieving", "harvesting", "runecraft",
)

/**
 * Saves to the game database [live] as the game server makes them: everything in one
 * transaction (`PlayerService.savePlayer` saves a player's data and skills together,
 * `atomically`), with `players.skill_total` the sum of the full levels (`Skills.getTotalLevel`),
 * and the file's modification time moving with each save. Like the server, it keeps one
 * connection open between saves; [close] closes it.
 */
class ServerSaves(private val live: Path) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$live").apply { autoCommit = false }

    /** Saves [statements] in one transaction at [at], setting every player's total level from their full levels. */
    fun save(at: Instant, vararg statements: String) {
        connection.createStatement().use { statement ->
            statements.forEach(statement::executeUpdate)
            statement.executeUpdate(
                "UPDATE players SET skill_total = (SELECT ${SKILL_COLUMNS.joinToString(" + ")} FROM maxstats WHERE maxstats.playerID = players.id) " +
                    "WHERE id IN (SELECT playerID FROM maxstats)",
            )
        }
        connection.commit()
        Files.setLastModifiedTime(live, FileTime.from(at.toJavaInstant()))
    }

    override fun close() = connection.close()
}

/** Raises one skill of [player] to [level] with [storedExperience], as a save of their progress would. */
fun raiseSkill(player: Int, skill: String, level: Int, storedExperience: Int) = arrayOf(
    "UPDATE maxstats SET $skill = $level WHERE playerID = $player",
    "UPDATE curstats SET $skill = $level WHERE playerID = $player",
    "UPDATE experience SET $skill = $storedExperience WHERE playerID = $player",
)
