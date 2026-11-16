package ledger.core.gamedata

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Extraction from a small invented OpenRSC checkout, laid out as the real one is. */
class ExtractGameDataTest {
    private val checkout: Path = Files.createTempDirectory("openrsc")
    private val defs = checkout.resolve("server/conf/server/defs").createDirectories()
    private val plugins = checkout.resolve("server/plugins/quests").createDirectories()
    private val out: Path = Files.createTempDirectory("gamedata")

    init {
        items("[]", custom = "[]")
        checkout.resolve("server/src/com/openrsc/server/constants").createDirectories().resolve("Quests.java").writeText(
            "public final class Quests {\n\tpublic static final int SHEEP_COUNT = 0;\n\tpublic static final int LOST_LANTERN = 7;\n}\n",
        )
    }

    @Test
    fun `extracts items in the server's file order and quests from their plugins`() {
        items(
            """[{"id": 0, "name": "Bronze Pickaxe", "isStackable": 0}, {"id": 1, "name": "Feather", "isStackable": 1}]""",
            custom = """[{"id": 2, "name": "Lantern", "isStackable": 0}]""",
        )
        plugin("LostLantern.java", quest("LOST_LANTERN", "The lost lantern"))
        plugin("SheepCount.java", quest("SHEEP_COUNT", "Counting sheep"))
        plugin("NotAQuest.java", "class NotAQuest {}")

        extract()

        assertEquals(listOf("0\tBronze Pickaxe\t0", "1\tFeather\t1", "2\tLantern\t0"), rows(out.resolve(ITEMS_FILE)))
        assertEquals(listOf("0\tCounting sheep", "7\tThe lost lantern"), rows(out.resolve(QUESTS_FILE)))
    }

    @Test
    fun `refuses a quest plugin whose number isn't a known constant`() {
        plugin("Odd.java", quest("UNKNOWN", "Odd"))
        val refusal = assertFailsWith<IllegalArgumentException> { extract() }
        assertContains(refusal.message.orEmpty(), "Quests.UNKNOWN")
    }

    @Test
    fun `refuses an item name with a tab or a line break, writing no items`() {
        // A JSON escape, so the name itself holds the tab or line break the output can't.
        for (escape in listOf("\\t", "\\n")) {
            items("""[{"id": 5, "name": "Bronze${escape}Pickaxe", "isStackable": 0}]""")
            val refusal = assertFailsWith<IllegalArgumentException> { extract() }
            assertContains(refusal.message.orEmpty(), "tab or line break")
            assertFalse(out.resolve(ITEMS_FILE).exists(), "items written despite $escape in a name")
        }
    }

    @Test
    fun `refuses a quest plugin that names its quest other than as a plain string`() {
        val odd = listOf(
            quest("LOST_LANTERN", """The \"lost\" lantern"""),
            quest("LOST_LANTERN", "x").replace("\"x\"", "LANTERN_NAME"),
        )
        for (source in odd) {
            plugin("LostLantern.java", source)
            val refusal = assertFailsWith<IllegalArgumentException> { extract() }
            assertContains(refusal.message.orEmpty(), "no plain quest name")
        }
    }

    @Test
    fun `refuses one quest number given two names, and takes it once when they agree`() {
        plugin("LostLantern.java", quest("LOST_LANTERN", "The lost lantern"))
        plugin("LostLanternAgain.java", quest("LOST_LANTERN", "Lost lantern"))
        val refusal = assertFailsWith<IllegalArgumentException> { extract() }
        assertContains(refusal.message.orEmpty(), "Quest 7 is named both")

        plugins.resolve("LostLanternAgain.java").deleteExisting()
        plugin("LostLanternAgain.java", quest("LOST_LANTERN", "The lost lantern"))
        extract()
        assertEquals(listOf("7\tThe lost lantern"), rows(out.resolve(QUESTS_FILE)))
    }

    @Test
    fun `running again replaces what an earlier run wrote`() {
        items("""[{"id": 1, "name": "Feather", "isStackable": 1}]""")
        plugin("SheepCount.java", quest("SHEEP_COUNT", "Counting sheep"))
        extract()

        items("""[{"id": 2, "name": "Lantern", "isStackable": 0}]""")
        plugins.resolve("SheepCount.java").deleteExisting()
        plugin("LostLantern.java", quest("LOST_LANTERN", "The lost lantern"))
        extract()

        assertEquals(listOf("2\tLantern\t0"), rows(out.resolve(ITEMS_FILE)))
        assertEquals(listOf("7\tThe lost lantern"), rows(out.resolve(QUESTS_FILE)))
        // Each file keeps its own two comment lines, not one pair per run.
        assertEquals(2, out.resolve(ITEMS_FILE).readText().lines().count { it.startsWith("#") })
        assertEquals(2, out.resolve(QUESTS_FILE).readText().lines().count { it.startsWith("#") })
    }

    @Test
    fun `creates an output folder that isn't there yet`() {
        val into = out.resolve("not/yet")
        extract(into)
        assertTrue(into.resolve(ITEMS_FILE).exists())
        assertTrue(into.resolve(QUESTS_FILE).exists())
    }

    private fun extract(into: Path = out) = main(arrayOf(checkout.toString(), into.toString()))

    /** Writes the two item definition files, each one object holding its array as the server's are. */
    private fun items(main: String, custom: String = "[]") {
        defs.resolve("ItemDefs.json").writeText("""{"item": $main}""")
        defs.resolve("ItemDefsCustom.json").writeText("""{"items": $custom}""")
    }

    private fun plugin(file: String, source: String) = plugins.resolve(file).writeText(source)

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
