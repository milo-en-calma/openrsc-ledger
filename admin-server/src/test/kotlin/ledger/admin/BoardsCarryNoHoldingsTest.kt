package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import ledger.core.game.SqliteGameData
import ledger.core.history.HistoryReader
import ledger.core.history.SkillChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Leaderboards show ranks, levels and experience, never what anyone carries. Every answer the
 * Leaderboards page uses is checked against an allow-list of fields at each level, so a field
 * added later fails here until it's added on purpose, and against the names of every item the
 * test database's players hold in their inventories, equipment and banks.
 */
class BoardsCarryNoHoldingsTest {
    /** A gain for every test player, so the gains answer names the players who hold things. */
    private val history = object : HistoryReader {
        override fun recordedSince(): Instant = Instant.parse("2026-01-01T08:00:00Z")
        override fun changes(from: Instant, to: Instant): List<SkillChange> = (1..3).map {
            SkillChange(it, Skill.WOODCUT, start = SkillValue(0, 1), end = SkillValue(400, 2))
        }
    }

    /** Each field an answer may carry, by where it sits: the answer itself, or each row of one of its lists. */
    private val boardList = Fields(setOf("boards"), "boards" to Fields(setOf("key", "name")))
    private val leaderboard = Fields(
        setOf("board", "entries"),
        "board" to Fields(setOf("key", "name")),
        "entries" to Fields(setOf("rank", "playerId", "name", "level", "experience", "staff")),
    )
    private val gainsReport = Fields(
        setOf("from", "to", "recordedSince", "gains"),
        "gains" to Fields(
            setOf("playerId", "name", "skill", "skillName", "experienceGained", "levelsGained", "experience", "level", "staff"),
        ),
    )

    @Test
    fun `no answer the leaderboards use carries a field or an item from what anyone holds`() =
        adminTest(SqliteGameData(testDatabase()), history = history) {
            val heldNames = heldItemNames()
            // Guards the check below: with nothing held, it would pass whatever the boards carried.
            assertTrue(heldNames.size >= 5, "The test database's players hold only $heldNames")

            val list = answer("/api/leaderboards")
            check("/api/leaderboards", list, boardList, heldNames)
            val keys = list["boards"].strings("key")
            assertEquals(1 + Skill.entries.size, keys.size, "Boards listed: $keys")

            val urls = keys.flatMap { listOf("/api/leaderboards/$it", "/api/leaderboards/$it?staff=true") } +
                listOf("/api/history/gains?from=2026-01-01T00:00:00Z", "/api/history/gains?from=2026-01-01T00:00:00Z&staff=true")
            for (url in urls) {
                val body = answer(url)
                val rows = if (url.startsWith("/api/history")) body["gains"] else body["entries"]
                assertTrue(rows.jsonArray.isNotEmpty(), "$url has no rows to check")
                check(url, body, if (url.startsWith("/api/history")) gainsReport else leaderboard, heldNames)
            }
        }

    /** The name of every item anyone holds, as the economy lists them. */
    private suspend fun ApplicationTestBuilder.heldItemNames(): List<String> =
        answer("/api/economy")["items"].jsonArray.mapNotNull { (it["name"] as? JsonPrimitive)?.takeIf { name -> name.isString }?.content }

    private suspend fun ApplicationTestBuilder.answer(url: String): JsonElement {
        val response = client.get(url)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, "$url answered ${response.status}: $body")
        return Json.parseToJsonElement(body)
    }

    private fun check(url: String, body: JsonElement, fields: Fields, heldNames: List<String>) {
        fields.check(url, body)
        val text = body.toString().lowercase()
        for (name in heldNames) {
            assertFalse(name.lowercase() in text, "$url names $name, which a player holds")
        }
    }

    /** The fields an object may carry, and for each that holds an object or a list of them, what those may carry. */
    private class Fields(val allowed: Set<String>, vararg nested: Pair<String, Fields>) {
        private val nested = nested.toMap()

        fun check(where: String, element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach { check(where, it) }
                is JsonObject -> {
                    assertEquals(emptySet(), element.keys - allowed, "$where carries fields the leaderboards don't allow")
                    for ((key, inner) in nested) {
                        element[key]?.let { inner.check("$where › $key", it) }
                    }
                }
                else -> error("$where should be an object or a list, not $element")
            }
        }
    }
}
