package ledger.core.rank

import ledger.core.fixture.Fixtures
import ledger.core.game.HoldingRecord
import ledger.core.game.Place
import ledger.core.game.SqliteGameData
import java.nio.file.Files
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
    fun `totals don't overflow`() {
        val holdings = List(3) { HoldingRecord(it, Place.BANK, COINS, Int.MAX_VALUE) }
        assertEquals(3L * Int.MAX_VALUE, Economy.itemTotals(holdings).single().total)
    }

    @Test
    fun `reads every held item from the database`() {
        val gameData = SqliteGameData(Fixtures.database(Files.createTempDirectory("economy").resolve("copy.db")))
        val totals = Economy.itemTotals(gameData.allHeldItems()).associateBy { it.itemId }
        assertEquals(ItemTotal(COINS, inInventories = 1250, inBanks = 50000, holders = 1), totals[COINS])
        // The 120 logs are banked as a note; they count as logs.
        assertEquals(ItemTotal(14, inInventories = 0, inBanks = 120, holders = 1), totals[14])
        // Seven kinds of item: coins are both carried and banked.
        assertEquals(7, totals.size)
    }
}
