package ledger.core.gamedata

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `extracts items in the server's file order and quests from their plugins`() {
        val checkout = Files.createTempDirectory("openrsc")
        val out = Files.createTempDirectory("gamedata")
        val defs = checkout.resolve("server/conf/server/defs").createDirectories()
        defs.resolve("ItemDefs.json").writeText(
            """{"item": [{"id": 0, "name": "Bronze Pickaxe", "isStackable": 0}, {"id": 1, "name": "Feather", "isStackable": 1}]}""",
        )
        defs.resolve("ItemDefsCustom.json").writeText("""{"items": [{"id": 2, "name": "Lantern", "isStackable": 0}]}""")
        checkout.resolve("server/src/com/openrsc/server/constants").createDirectories().resolve("Quests.java").writeText(
            "public final class Quests {\n\tpublic static final int SHEEP_COUNT = 0;\n\tpublic static final int LOST_LANTERN = 7;\n}\n",
        )
        val plugins = checkout.resolve("server/plugins/quests").createDirectories()
        plugins.resolve("LostLantern.java").writeText(quest("LOST_LANTERN", "The lost lantern"))
        plugins.resolve("SheepCount.java").writeText(quest("SHEEP_COUNT", "Counting sheep"))
        plugins.resolve("NotAQuest.java").writeText("class NotAQuest {}")

        main(arrayOf(checkout.toString(), out.toString()))

        assertEquals(listOf("0\tBronze Pickaxe\t0", "1\tFeather\t1", "2\tLantern\t0"), rows(out.resolve(ITEMS_FILE)))
        assertEquals(listOf("0\tCounting sheep", "7\tThe lost lantern"), rows(out.resolve(QUESTS_FILE)))
    }

    @Test
    fun `refuses a quest plugin whose number isn't a known constant`() {
        val checkout = Files.createTempDirectory("openrsc")
        val defs = checkout.resolve("server/conf/server/defs").createDirectories()
        defs.resolve("ItemDefs.json").writeText("""{"item": []}""")
        defs.resolve("ItemDefsCustom.json").writeText("""{"items": []}""")
        checkout.resolve("server/src/com/openrsc/server/constants").createDirectories().resolve("Quests.java").writeText("")
        checkout.resolve("server/plugins").createDirectories().resolve("Odd.java").writeText(quest("UNKNOWN", "Odd"))
        assertFailsWith<IllegalArgumentException> { main(arrayOf(checkout.toString(), Files.createTempDirectory("out").toString())) }
    }

    private fun quest(constant: String, name: String) = """
        public class Q implements QuestInterface {
            @Override
            public int getQuestId() {
                return Quests.$constant;
            }

            @Override
            public String getQuestName() {
                return "$name";
            }
        }
    """.trimIndent()

    private fun rows(file: Path) = file.readText().lines().filter { it.isNotEmpty() && !it.startsWith("#") }
}
