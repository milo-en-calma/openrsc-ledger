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
