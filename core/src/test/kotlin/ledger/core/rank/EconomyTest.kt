package ledger.core.rank

import ledger.core.game.HoldingRecord
import ledger.core.game.Place
import ledger.core.game.PlayerSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class EconomyTest {
    @Test
    fun `adds up each item across inventories and banks, most-held first`() {
        val holdings = listOf(
            HoldingRecord(1, Place.INVENTORY, COINS, 1250),
            HoldingRecord(1, Place.BANK, COINS, 50000),
            HoldingRecord(2, Place.INVENTORY, COINS, 3),
            HoldingRecord(1, Place.BANK, 14, 120),
            HoldingRecord(2, Place.INVENTORY, 20, 1),
            HoldingRecord(3, Place.INVENTORY, 20, 1),
        )
        assertEquals(
            listOf(
                ItemTotal(COINS, inInventories = 1253, inBanks = 50000, holders = 2),
                ItemTotal(14, inInventories = 0, inBanks = 120, holders = 1),
                ItemTotal(20, inInventories = 2, inBanks = 0, holders = 2),
            ),
            Economy.itemTotals(holdings),
        )
    }

    @Test
    fun `equal totals go to the item more players hold`() {
        val holdings = listOf(
            HoldingRecord(1, Place.INVENTORY, 5, 2),
            HoldingRecord(1, Place.INVENTORY, 9, 1),
            HoldingRecord(2, Place.BANK, 9, 1),
        )
        assertEquals(listOf(9, 5), Economy.itemTotals(holdings).map { it.itemId })
    }

    @Test
    fun `lists an item's holders most first, then by name, leaving out unknown players`() {
        fun player(id: Int, name: String) = PlayerSummary(id, name, combatLevel = 3, totalLevel = 27, lastLogin = null, online = false)
        val players = listOf(player(1, "Wren"), player(2, "alder"), player(3, "Birch"))
        val holdings = listOf(
            HoldingRecord(1, Place.INVENTORY, 14, 5),
            HoldingRecord(1, Place.BANK, 14, 20),
            HoldingRecord(2, Place.BANK, 14, 1),
            HoldingRecord(2, Place.INVENTORY, 14, 4),
            HoldingRecord(3, Place.BANK, 14, 5),
            HoldingRecord(3, Place.BANK, COINS, 900),
            HoldingRecord(4, Place.BANK, 14, 70),
        )
        assertEquals(
            listOf(Triple("Wren", 5L, 20L), Triple("alder", 4L, 1L), Triple("Birch", 0L, 5L)),
            Economy.holders(14, holdings, players).map { Triple(it.player.name, it.inInventory, it.inBank) },
        )
    }

    @Test
    fun `totals don't overflow`() {
        val holdings = List(3) { HoldingRecord(it, Place.BANK, COINS, Int.MAX_VALUE) }
        assertEquals(3L * Int.MAX_VALUE, Economy.itemTotals(holdings).single().total)
    }
}
