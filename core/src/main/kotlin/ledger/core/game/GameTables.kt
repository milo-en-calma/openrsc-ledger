package ledger.core.game

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
