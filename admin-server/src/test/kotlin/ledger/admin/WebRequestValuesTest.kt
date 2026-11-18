package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import ledger.admin.routes.BAD_PLAYER
import ledger.admin.routes.BAD_ITEM_ID
import ledger.admin.routes.BAD_PLAYER_ID
import ledger.core.game.SqliteGameData
import ledger.core.history.HistoryReader
import ledger.core.history.SkillChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The API answers the values exactly as the web's pages send them. The pages call through
 * openapi-fetch, which writes each query value with JavaScript's `encodeURIComponent` (so a time's
 * colons arrive as `%3A`) and `true` and `false` as words; times come from `Date.toISOString()`,
 * which always has milliseconds. The player and item pages refuse an address that isn't a
 * number themselves and never send it, but a link written by hand still gets a message.
 */
class WebRequestValuesTest {
    private val game = SqliteGameData(testDatabase())

    /** No recorded changes, remembering the period it was asked for. */
    private class AskedHistory : HistoryReader {
        var askedFrom: Instant? = null
        override fun recordedSince(): Instant = Instant.parse("2026-01-01T08:00:00Z")
        override fun changes(from: Instant, to: Instant): List<SkillChange> {
            askedFrom = from
            return emptyList()
        }
    }

    /** `new Date(…).toISOString()` for 09:00:00.123, as openapi-fetch puts it in the address. */
    private val pageFrom = "2026-01-01T09%3A00%3A00.123Z"

    @Test
    fun `the gains take the time and staff values the pages send`() {
        val history = AskedHistory()
        adminTest(game, history = history) {
            // The "gained today" board sends staff=false or true; a player's own page sends its id and staff=true.
            for (query in listOf("from=$pageFrom&staff=false", "from=$pageFrom&staff=true", "from=$pageFrom&player=1&staff=true")) {
                val response = client.get("/api/history/gains?$query")
                assertEquals(HttpStatusCode.OK, response.status, "$query: ${response.bodyAsText()}")
                assertEquals(Instant.parse("2026-01-01T09:00:00.123Z"), history.askedFrom, query)
            }
        }
    }

    @Test
    fun `a search with a space in it, as the players page sends it, finds the player`() = adminTest(game) {
        assertEquals(listOf("Tansy Moss"), getJson("/api/players?search=Tansy%20Moss")["players"].strings("name"))
    }

    @Test
    fun `the player and item pages' own calls answer`() = adminTest(game) {
        val player = client.get("/api/players/1")
        assertEquals(HttpStatusCode.OK, player.status)
        assertTrue("\"Bramblewick\"" in player.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/items/10/holders").status)
    }

    @Test
    fun `an address that isn't a number gets a message the page can show, not a server error`() = adminTest(game, history = AskedHistory()) {
        val expected = listOf(
            "/api/players/NaN" to (HttpStatusCode.BadRequest to BAD_PLAYER_ID),
            "/api/items/NaN/holders" to (HttpStatusCode.BadRequest to BAD_ITEM_ID),
            "/api/history/gains?from=$pageFrom&player=NaN&staff=true" to (HttpStatusCode.BadRequest to BAD_PLAYER),
        )
        for ((address, answer) in expected) {
            val (status, message) = answer
            assertEquals(status, client.get(address).status, address)
            assertEquals(message, getJson(address)["message"].jsonPrimitive.content, address)
        }
    }
}
