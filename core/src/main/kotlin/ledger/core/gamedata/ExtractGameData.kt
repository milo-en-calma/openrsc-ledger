package ledger.core.gamedata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Extracts item and quest names from an OpenRSC checkout into the ledger's bundled game
 * data: `ExtractGameData <openrsc checkout> <output folder>`, run by
 * `./gradlew :core:extractGameData -PopenrscDir=...`.
 *
 * - **Items** come from the definition files the server loads, in its order
 *   (`server/conf/server/defs/ItemDefs.json`, then `ItemDefsCustom.json`). The server
 *   patches some of them for worlds set to an older era of the game (`based_config_data`
 *   below 85); those older names aren't extracted.
 * - **Quests** come from the server's quest plugins, each of which gives its number
 *   (`getQuestId`, a constant in `Quests.java`) and its name (`getQuestName`).
 */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("Usage: ExtractGameData <openrsc checkout> <output folder>")
        exitProcess(2)
    }
    val (openRsc, out) = args.map { Path.of(it) }
    val server = openRsc.resolve("server")
    Files.createDirectories(out)

    val items = ITEM_FILES.flatMap { readItems(server.resolve("conf/server/defs").resolve(it)) }
    out.resolve(ITEMS_FILE).writeText(
        "# id\tname\tstackable (1 or 0). Extracted from OpenRSC's item definitions by\n" +
            "# ./gradlew :core:extractGameData; don't edit by hand.\n" +
            items.joinToString("") { "${it.id}\t${it.name}\t${if (it.stackable) 1 else 0}\n" },
    )

    val quests = readQuests(server)
    out.resolve(QUESTS_FILE).writeText(
        "# id\tname. Extracted from OpenRSC's quest plugins by ./gradlew :core:extractGameData;\n" +
            "# don't edit by hand.\n" +
            quests.entries.sortedBy { it.key }.joinToString("") { "${it.key}\t${it.value}\n" },
    )
    println("Extracted ${items.size} items and ${quests.size} quests into $out")
}

/** The item definition files the server loads, in its order (`EntityHandler.loadItems`). */
private val ITEM_FILES = listOf("ItemDefs.json", "ItemDefsCustom.json")

private data class ItemRow(val id: Int, val name: String, val stackable: Boolean)

/** A file holding one object whose only property is the array of items, as the server reads it. */
private fun readItems(file: Path): List<ItemRow> {
    val items = Json.parseToJsonElement(file.readText()).jsonObject.values.single().jsonArray
    return items.map { element ->
        val item = element.jsonObject
        val name = item.getValue("name").jsonPrimitive.content
        require('\t' !in name && '\n' !in name) { "Item ${item["id"]} has a tab or line break in its name" }
        ItemRow(
            id = item.getValue("id").jsonPrimitive.int,
            name = name,
            stackable = item.getValue("isStackable").jsonPrimitive.int == 1,
        )
    }
}

private val QUEST_CONSTANT = Regex("""public\s+static\s+final\s+int\s+([A-Z0-9_]+)\s*=\s*(-?\d+)\s*;""")
private val QUEST_ID = Regex("""getQuestId\(\)\s*\{\s*return\s+Quests\.([A-Z0-9_]+)\s*;""")
private val QUEST_NAME = Regex("""getQuestName\(\)\s*\{\s*return\s+"([^"\\]*)"\s*;""")

/** Each quest's number and name, from the quest plugins. */
private fun readQuests(server: Path): Map<Int, String> {
    val constants = QUEST_CONSTANT
        .findAll(server.resolve("src/com/openrsc/server/constants/Quests.java").readText())
        .associate { it.groupValues[1] to it.groupValues[2].toInt() }

    val quests = sortedMapOf<Int, String>()
    Files.walk(server.resolve("plugins")).use { paths ->
        paths.filter { it.isRegularFile() && it.extension == "java" }.forEach { file ->
            val source = file.readText()
            val constant = QUEST_ID.find(source)?.groupValues?.get(1) ?: return@forEach
            val name = requireNotNull(QUEST_NAME.find(source)?.groupValues?.get(1)) { "$file gives a quest number but no plain quest name" }
            val id = requireNotNull(constants[constant]) { "$file uses Quests.$constant, which Quests.java doesn't define" }
            val earlier = quests.put(id, name)
            require(earlier == null || earlier == name) { "Quest $id is named both \"$earlier\" and \"$name\"" }
        }
    }
    return quests
}
