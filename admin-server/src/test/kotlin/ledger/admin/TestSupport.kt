package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ledger.core.copy.CopyRecord
import ledger.core.fixture.Fixtures
import ledger.core.game.GameData
import ledger.core.game.HoldingRecord
import ledger.core.game.PlayerDetails
import ledger.core.game.PlayerSkills
import ledger.core.game.PlayerSummary
import ledger.core.history.HistoryReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail
import kotlin.time.Instant

/** A game with no players, for tests that don't read game data. */
object NoGameData : GameData {
    override fun players(search: String?): List<PlayerSummary> = emptyList()
    override fun allSkills(): List<PlayerSkills> = emptyList()
    override fun player(id: Int): PlayerDetails? = null
    override fun allHeldItems(): List<HoldingRecord> = emptyList()
}

/** The copy the tests' data comes from, as the routes report it. */
val COPIED = CopyRecord(copiedAt = Instant.parse("2026-01-01T09:20:00Z"), liveModified = Instant.parse("2026-01-01T09:19:00Z"))

/** A fresh copy of the test database (`fixture/openrsc.sql`), with invented players only. */
fun testDatabase(): Path = Fixtures.database(Files.createTempDirectory("ledger-admin").resolve("copy.db"))

/**
 * Runs [block] against the admin module serving [gameData] and [history], from the copy
 * [COPIED], or as before the first copy when [copied] is false.
 */
fun adminTest(
    gameData: GameData = NoGameData,
    copied: Boolean = true,
    history: HistoryReader = HistoryReader.NONE,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application { adminModule(lastCopy = { if (copied) COPIED else null }, gameData = gameData, history = history) }
    block()
}

/** GETs [path] and parses the response as JSON. */
suspend fun ApplicationTestBuilder.getJson(path: String): JsonElement = Json.parseToJsonElement(client.get(path).bodyAsText())

/** The field [key] of this JSON object. */
operator fun JsonElement.get(key: String): JsonElement = jsonObject.getValue(key)

/** Each object's [key] in this JSON array, as text. */
fun JsonElement.strings(key: String): List<String> = jsonArray.map { it[key].jsonPrimitive.content }

/** Each object's [key] in this JSON array, as a whole number. */
fun JsonElement.ints(key: String): List<Int> = jsonArray.map { it[key].jsonPrimitive.int }

/** A GET route from the committed spec: its [path], a [url] to call it by, and the status codes the spec says it answers. */
data class SpecRoute(val path: String, val url: String, val statuses: Set<String>)

/**
 * A value for each parameter a route may take, chosen so each route answers with data
 * from the test database: a player and an item it has, and a period in 2026.
 */
private val sampleValues = mapOf(
    "id" to "1",
    "itemId" to "10",
    "board" to "overall",
    "search" to "o",
    "staff" to "true",
    "from" to "2026-01-01T00:00:00Z",
    "to" to "2026-01-02T00:00:00Z",
    "player" to "1",
    "skill" to "woodcut",
)

/**
 * Every GET route in the committed `openapi.json`, each with every parameter given its
 * sample value. A route with a parameter missing from the sample values fails the test
 * rather than being skipped, so a test over every route covers a new one as soon as it is in the spec.
 */
fun specGetRoutes(): List<SpecRoute> {
    val spec = Json.parseToJsonElement(Files.readString(Path.of("openapi.json"))).jsonObject
    return spec.getValue("paths").jsonObject.mapNotNull { (path, operations) ->
        val get = operations.jsonObject["get"]?.jsonObject ?: return@mapNotNull null
        val parameters = get["parameters"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("name").jsonPrimitive.content }
        val statuses = get["responses"]?.jsonObject?.keys.orEmpty()
        SpecRoute(path, sampleUrl(path, parameters), statuses)
    }
}

private fun sampleUrl(path: String, parameters: List<String>): String {
    var url = path
    val query = mutableListOf<String>()
    for (name in parameters) {
        val value = sampleValues[name] ?: fail("No sample value for $name in $path; add one to sampleValues in TestSupport.kt")
        if ("{$name}" in url) {
            url = url.replace("{$name}", value)
        } else {
            query += "$name=$value"
        }
    }
    return if (query.isEmpty()) url else "$url?${query.joinToString("&")}"
}
