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

    /** One player's summary, skills and quests, or null if there's no such player. */
    fun player(id: Int): PlayerDetails?
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
)

/** Everything the player page shows about one player. */
data class PlayerDetails(
    val summary: PlayerSummary,
    val questPoints: Int,
    val skills: Map<Skill, SkillStanding>,
    /** Every quest the player has started, by quest number. */
    val quests: List<QuestProgress>,
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
