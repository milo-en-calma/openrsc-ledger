package ledger.core.rank

import ledger.core.game.HoldingRecord
import ledger.core.game.Place

/** The item number of coins. */
const val COINS = 10

/** How much of one item the world holds. Amounts are Long: a world's coins can pass Int's limit. */
data class ItemTotal(
    val itemId: Int,
    val inInventories: Long,
    val inBanks: Long,
    /** How many players hold any, in either place. */
    val holders: Int,
) {
    val total: Long get() = inInventories + inBanks
}

/** The world's economy: how much of every item is held, and where. */
object Economy {
    /**
     * Every item anyone holds, most-held first (by total amount, then by how many hold it,
     * then by item number). A noted item counts as the item it stands for.
     */
    fun itemTotals(holdings: List<HoldingRecord>): List<ItemTotal> =
        holdings.groupBy { it.itemId }
            .map { (itemId, held) ->
                ItemTotal(
                    itemId = itemId,
                    inInventories = held.filter { it.place == Place.INVENTORY }.sumOf { it.amount.toLong() },
                    inBanks = held.filter { it.place == Place.BANK }.sumOf { it.amount.toLong() },
                    holders = held.map { it.playerId }.distinct().size,
                )
            }
            .sortedWith(compareByDescending<ItemTotal> { it.total }.thenByDescending { it.holders }.thenBy { it.itemId })
}
