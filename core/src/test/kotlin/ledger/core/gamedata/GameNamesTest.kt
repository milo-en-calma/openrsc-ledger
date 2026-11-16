package ledger.core.gamedata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameNamesTest {
    /** A bundled table's rows as written in the file, before GameNames turns them into maps. */
    private fun rows(file: String): List<List<String>> =
        GameNames::class.java.getResource("/gamedata/$file")!!.readText()
            .lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.toList()

    @Test
    fun `the bundled names know items and quests`() {
        val names = GameNames.bundled()
        assertEquals(ItemName("Iron Mace", stackable = false), names.item(0))
        assertEquals(ItemName("Coins", stackable = true), names.item(10))
        assertEquals("Cook's assistant", names.quest(1))
        assertNull(names.item(-5))
        assertNull(names.quest(9999))
    }

    // Expected values from OpenRSC's own definitions: ItemDefs.json holds items 0 to 1289,
    // ItemDefsCustom.json 1290 to 1592, and Quests.java numbers the quests 0 to 51.
    @Test
    fun `the bundled names match OpenRSC's own definitions at the edges`() {
        val names = GameNames.bundled()
        assertEquals(ItemName("Fire-Rune", stackable = true), names.item(31))
        assertEquals(ItemName("Ironman helm", stackable = false), names.item(1290))
        assertEquals(ItemName("Boomstick", stackable = false), names.item(1592))
        assertNull(names.item(1593))
        assertEquals("Black knight's fortress", names.quest(0))
        assertEquals("Peeling the Onion", names.quest(51))
        assertNull(names.quest(52))
        assertNull(names.quest(-1))
    }

    @Test
    fun `the items table has every id from 0 once, in order`() {
        val ids = rows(ITEMS_FILE).map { it[0].toInt() }
        assertEquals((0..1592).toList(), ids)
    }

    @Test
    fun `the quests table has every id from 0 once, in order`() {
        val ids = rows(QUESTS_FILE).map { it[0].toInt() }
        assertEquals((0..51).toList(), ids)
    }

    @Test
    fun `every row has its columns and stackable is only 0 or 1`() {
        rows(ITEMS_FILE).forEach { row ->
            assertEquals(3, row.size, "item row $row")
            assertTrue(row[2] == "0" || row[2] == "1", "item row $row")
        }
        rows(QUESTS_FILE).forEach { row -> assertEquals(2, row.size, "quest row $row") }
    }

    @Test
    fun `no bundled name is blank or padded with spaces`() {
        val bundledNames = rows(ITEMS_FILE).map { it[1] } + rows(QUESTS_FILE).map { it[1] }
        bundledNames.forEach { name ->
            assertTrue(name.isNotBlank() && name == name.trim(), "name '$name'")
        }
    }

    @Test
    fun `names given to the constructor come back as given`() {
        val names = GameNames(
            items = mapOf(7 to ItemName("Pebble", stackable = true)),
            quests = mapOf(3 to "A Short Walk"),
        )
        assertEquals(ItemName("Pebble", stackable = true), names.item(7))
        assertEquals("A Short Walk", names.quest(3))
        assertNull(names.item(3))
        assertNull(names.quest(7))
    }
}
