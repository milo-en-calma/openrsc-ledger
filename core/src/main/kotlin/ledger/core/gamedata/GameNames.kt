package ledger.core.gamedata

/** The bundled files' names, under `gamedata/` on the classpath. */
internal const val ITEMS_FILE = "items.tsv"
internal const val QUESTS_FILE = "quests.tsv"

/** What the game calls an item, and whether any number of it shares one inventory slot. */
data class ItemName(val name: String, val stackable: Boolean)

/**
 * Names for the numbers the server's database stores: items and quests. They come from
 * the game data bundled with the ledger ([bundled]), extracted from OpenRSC by
 * `./gradlew :core:extractGameData`.
 */
class GameNames(private val items: Map<Int, ItemName>, private val quests: Map<Int, String>) {
    fun item(id: Int): ItemName? = items[id]
    fun quest(id: Int): String? = quests[id]

    companion object {
        /** The names bundled with the ledger. */
        fun bundled(): GameNames = GameNames(
            items = readTable(ITEMS_FILE).associate { (id, name, stackable) -> id.toInt() to ItemName(name, stackable == "1") },
            quests = readTable(QUESTS_FILE).associate { (id, name) -> id.toInt() to name },
        )

        /** The rows of a bundled tab-separated file, skipping `#` comment lines. */
        private fun readTable(file: String): List<List<String>> {
            val text = requireNotNull(GameNames::class.java.getResource("/gamedata/$file")) { "The ledger's bundled gamedata/$file is missing" }.readText()
            return text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.toList()
        }
    }
}
