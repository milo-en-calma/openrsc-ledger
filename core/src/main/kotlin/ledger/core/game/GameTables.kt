package ledger.core.game

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/**
 * The server's `players` table, declaring only the columns the ledger shows.
 *
 * The table also holds each player's password hash, salt, email address and IP
 * addresses (`pass`, `salt`, `email`, `creation_ip`, `login_ip`). They are left out on
 * purpose: a column that isn't declared here can't be selected, so no query can leak it.
 */
internal object Players : Table("players") {
    val id = integer("id")
    val username = varchar("username", 12)
    val combat = integer("combat").nullable()
    val skillTotal = integer("skill_total").nullable()
    /** Seconds since 1970, set when the player logs in; 0 if they never have. */
    val loginDate = long("login_date").nullable()
    val online = integer("online").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** A table with one column per skill, keyed by player: `experience`, `maxstats` or `curstats`. */
internal open class SkillTable(name: String) : Table(name) {
    val playerId = integer("playerID")
    val skill: Map<Skill, Column<Int>> = Skill.entries.associateWith { integer(it.column) }

    override val primaryKey = PrimaryKey(playerId)
}

/** Each player's experience per skill, as the server stores it. */
internal object Experience : SkillTable("experience")

/** Each player's full level per skill, before boosts and drains. */
internal object MaxStats : SkillTable("maxstats")
