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
    val questPoints = integer("quest_points").nullable()
    /** The player's rank on the server: below [FIRST_PLAYER_GROUP] is staff. */
    val groupId = integer("group_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * The server's group for ordinary players (`Group.USER`). Every group below it is staff:
 * owner, admins, moderators, developers, event and player moderators, and testers.
 */
internal const val FIRST_PLAYER_GROUP = 10

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

/** Each player's current level per skill, after boosts and drains. */
internal object CurStats : SkillTable("curstats")

/** Each player's progress in each quest they've started. */
internal object Quests : Table("quests") {
    val playerId = integer("playerID")
    val questId = integer("id").nullable()
    /** How far through the quest the player is; [QUEST_COMPLETE] once done. */
    val stage = integer("stage").nullable()
}

/** The stage the server gives a finished quest (`Quests.QUEST_STAGE_COMPLETED`). */
const val QUEST_COMPLETE = -1

/**
 * A table of slots holding items, keyed by player: `invitems` (the inventory) or `bank`.
 * Each slot points at an [ItemStatuses] row, which says what the item is.
 */
internal open class ItemSlotTable(name: String) : Table(name) {
    val playerId = integer("playerID")
    /** The [ItemStatuses] row; not the item's type. */
    val itemStatusId = integer("itemID")
    val slot = integer("slot")
}

internal object InvItems : ItemSlotTable("invitems")
internal object BankItems : ItemSlotTable("bank")

/**
 * What each player wears, on a server with its equipment tab on (`want_equipment_tab`);
 * the table exists only there, made by the server's `add_equipment_tab` script. Each row
 * points at an [ItemStatuses] row. There's no slot: the server places each item by what
 * kind of item it is.
 */
internal object Equipped : Table("equipped") {
    val playerId = integer("playerID")
    /** The [ItemStatuses] row; not the item's type. */
    val itemStatusId = integer("itemID")
}

/** One held item: what it is, how many, and whether it's noted or worn. */
internal object ItemStatuses : Table("itemstatuses") {
    val id = integer("itemID")
    /** The item's type, the number the game data names. */
    val catalogId = integer("catalogID")
    val amount = integer("amount")
    val noted = integer("noted")
    val wielded = integer("wielded")

    override val primaryKey = PrimaryKey(id)
}
