package ledger.core.gamedata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameNamesTest {
    @Test
    fun `the bundled names know items and quests`() {
        val names = GameNames.bundled()
        assertEquals(ItemName("Iron Mace", stackable = false), names.item(0))
        assertEquals(ItemName("Coins", stackable = true), names.item(10))
        assertEquals("Cook's assistant", names.quest(1))
        assertNull(names.item(-5))
        assertNull(names.quest(9999))
    }
}
