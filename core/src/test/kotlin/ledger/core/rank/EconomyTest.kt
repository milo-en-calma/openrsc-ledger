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

    private fun player(id: Int, name: String) = PlayerSummary(id, name, combatLevel = 3, totalLevel = 27, lastLogin = null, online = false)

    @Test
    fun `no holdings give no totals and no holders`() {
        assertEquals(emptyList(), Economy.itemTotals(emptyList()))
        assertEquals(emptyList(), Economy.holders(COINS, emptyList(), listOf(player(1, "Wren"))))
    }

    @Test
    fun `an item nobody holds has no holders`() {
        val holdings = listOf(HoldingRecord(1, Place.BANK, COINS, 5))
        assertEquals(emptyList(), Economy.holders(14, holdings, listOf(player(1, "Wren"))))
    }

    @Test
    fun `several stacks of one item held by one player count once as a holder`() {
        // Items that don't stack take a slot each, so one player has a row per item.
        val holdings = List(4) { HoldingRecord(1, Place.INVENTORY, 14, 1) } + HoldingRecord(1, Place.BANK, 14, 30)
        assertEquals(listOf(ItemTotal(14, inInventories = 4, inBanks = 30, holders = 1)), Economy.itemTotals(holdings))
        assertEquals(
            listOf(Triple("Wren", 4L, 30L)),
            Economy.holders(14, holdings, listOf(player(1, "Wren"))).map { Triple(it.player.name, it.inInventory, it.inBank) },
        )
    }

    @Test
    fun `items with equal totals and holders go by item number`() {
        val holdings = listOf(
            HoldingRecord(1, Place.INVENTORY, 30, 2),
            HoldingRecord(1, Place.BANK, 7, 2),
            HoldingRecord(1, Place.INVENTORY, 19, 1),
            HoldingRecord(1, Place.BANK, 19, 1),
        )
        assertEquals(listOf(7, 19, 30), Economy.itemTotals(holdings).map { it.itemId })
    }

    @Test
    fun `a holder's place in the list comes from inventory and bank together`() {
        // Wren's 10 are all banked, alder's 9 all carried: banked items count the same.
        val holdings = listOf(HoldingRecord(1, Place.BANK, 14, 10), HoldingRecord(2, Place.INVENTORY, 14, 9))
        val players = listOf(player(1, "Wren"), player(2, "alder"))
        assertEquals(listOf("Wren" to 10L, "alder" to 9L), Economy.holders(14, holdings, players).map { it.player.name to it.total })
    }

    @Test
    fun `holders with equal totals are listed by name ignoring case`() {
        val names = listOf("delta", "Bravo", "alpha", "Charlie")
        val players = names.mapIndexed { index, name -> player(index + 1, name) }
        val holdings = players.map { HoldingRecord(it.id, Place.INVENTORY, 14, 3) }
        assertEquals(listOf("alpha", "Bravo", "Charlie", "delta"), Economy.holders(14, holdings, players).map { it.player.name })
    }

    @Test
    fun `the order holdings and players come in doesn't change the results`() {
        val players = listOf(player(1, "Wren"), player(2, "alder"), player(3, "Birch"))
        val holdings = listOf(
            HoldingRecord(1, Place.INVENTORY, COINS, 40),
            HoldingRecord(2, Place.BANK, COINS, 40),
            HoldingRecord(3, Place.BANK, 14, 40),
            HoldingRecord(2, Place.INVENTORY, 14, 2),
            HoldingRecord(1, Place.BANK, 20, 80),
        )
        assertEquals(Economy.itemTotals(holdings), Economy.itemTotals(holdings.reversed()))
        for (itemId in listOf(COINS, 14, 20)) {
            assertEquals(Economy.holders(itemId, holdings, players), Economy.holders(itemId, holdings.reversed(), players.reversed()), "$itemId")
        }
    }

    @Test
    fun `one holder's amounts past the largest Int don't overflow`() {
        val holdings = List(2) { HoldingRecord(1, Place.BANK, COINS, Int.MAX_VALUE) } + HoldingRecord(1, Place.INVENTORY, COINS, Int.MAX_VALUE)
        val holder = Economy.holders(COINS, holdings, listOf(player(1, "Wren"))).single()
        assertEquals(Int.MAX_VALUE.toLong(), holder.inInventory)
        assertEquals(2L * Int.MAX_VALUE, holder.inBank)
        assertEquals(3L * Int.MAX_VALUE, holder.total)
    }

    @Test
    fun `an item's holder count includes item rows whose player isn't listed, which its holders leave out`() {
        // The server never deletes a player's record, so only a hand edit of the database
        // could leave item rows without a player; the two counts would then differ.
        val holdings = listOf(HoldingRecord(1, Place.BANK, 14, 5), HoldingRecord(9, Place.BANK, 14, 70))
        assertEquals(2, Economy.itemTotals(holdings).single().holders)
        assertEquals(listOf("Wren"), Economy.holders(14, holdings, listOf(player(1, "Wren"))).map { it.player.name })
    }
}
