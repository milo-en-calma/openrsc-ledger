package ledger.core.game

import kotlin.time.Instant

/**
 * Everything the ledger reads from the game server's database. All reading goes through
 * this interface, so a move to MySQL, or a cache in front of the database, changes only
 * the class behind it.
 */
interface GameData {
    /**
     * Every player whose name contains [search], ignoring case, or every player when
     * [search] is null or blank, sorted by name.
     */
    fun players(search: String? = null): List<PlayerSummary>

    /** Every player's experience and full level in every skill. */
    fun allSkills(): List<PlayerSkills>

    /** One player's summary, skills, quests and belongings, or null if there's no such player. */
    fun player(id: Int): PlayerDetails?

    /** Every item in every inventory and bank. */
    fun allHeldItems(): List<HoldingRecord>
}

/** One player, as the player list shows them. */
data class PlayerSummary(
    val id: Int,
    val name: String,
    val combatLevel: Int,
    val totalLevel: Int,
    /** When the player last logged in, or null if they never have. The server records no logout time. */
    val lastLogin: Instant?,
    /** Whether the server had them marked as logged in at its last save. */
    val online: Boolean,
    /** Whether they're one of the server's staff, from owner down to tester, rather than an ordinary player. */
    val staff: Boolean = false,
)

/** Everything the player page shows about one player. */
data class PlayerDetails(
    val summary: PlayerSummary,
    val questPoints: Int,
    val skills: Map<Skill, SkillStanding>,
    /** Every quest the player has started, by quest number. */
    val quests: List<QuestProgress>,
    /** What the player carries, by slot. Worn items are here too, marked [HeldItem.worn]. */
    val inventory: List<HeldItem>,
    /** What the player has banked, by slot. */
    val bank: List<HeldItem>,
)

/** Where a player stands in one skill. */
data class SkillStanding(
    /** The level now, after boosts and drains. */
    val level: Int,
    /** The level before boosts and drains. */
    val fullLevel: Int,
    /** Experience as the server stores it; see [shownExperience]. */
    val storedExperience: Int,
)

/** How far a player is through one quest. */
data class QuestProgress(val questId: Int, val stage: Int) {
    val complete: Boolean get() = stage == QUEST_COMPLETE
}

/** An item in an inventory or bank slot. */
data class HeldItem(
    val slot: Int,
    /** The item's type, the number the game data names. */
    val itemId: Int,
    val amount: Int,
    /** Whether it's a bank note standing for [amount] of the item. */
    val noted: Boolean,
    /** Whether the player is wearing or wielding it. */
    val worn: Boolean,
)

/** Where an item is held. */
enum class Place { INVENTORY, BANK }

/** One slot's item, for adding up across the whole world. */
data class HoldingRecord(val playerId: Int, val place: Place, val itemId: Int, val amount: Int)
