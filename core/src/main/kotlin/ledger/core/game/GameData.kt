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
