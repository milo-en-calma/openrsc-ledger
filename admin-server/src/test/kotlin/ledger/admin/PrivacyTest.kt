package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import ledger.core.fixture.Fixtures
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
 * Calls every GET route in the committed `openapi.json` against the test database and checks
 * that no response carries the database's private columns: password hashes, salts, email
 * addresses and IP addresses. A new route is covered as soon as it is in the spec.
 */
class PrivacyTest {
    /** History for every test player, within the sample period, so the history routes answer with names too. */
    private val history = object : HistoryReader {
        override fun recordedSince(): Instant = Instant.parse("2026-01-01T08:00:00Z")
        override fun changes(from: Instant, to: Instant): List<SkillChange> = (1..3).map {
            SkillChange(it, Skill.WOODCUT, start = SkillValue(0, 1), end = SkillValue(400, 2))
        }
    }

    /** The test database's invented values for the private columns (`fixture/openrsc.sql`). */
    private val secrets = listOf("placeholder-hash", "placeholder-salt", "@example.invalid", "192.0.2.")

    /** The private columns' names, which shouldn't appear as keys either. */
    private val privateKeys = setOf("email", "pass", "password", "salt", "creation_ip", "login_ip", "ip")

    @Test
    fun `no route sends private player data`() = adminTest(SqliteGameData(testDatabase()), history = history) {
        val urls = specGetRoutes().map { it.url }
        assertTrue(urls.size >= 8, "Found only ${urls.size} routes in openapi.json")

        for (url in urls) {
            val response = client.get(url)
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, "$url answered ${response.status}: $body")
            for (secret in secrets) {
                assertFalse(secret in body, "$url sends $secret")
            }
            val keys = keysIn(Json.parseToJsonElement(body))
            assertEquals(emptySet(), keys.filter { it.lowercase() in privateKeys }.toSet(), "$url sends a private column")
        }
    }

    @Test
    fun `the test database does hold the secrets looked for`() {
        // Guards the test above: if the fixture's placeholders changed, it would pass vacuously.
        // Read as a resource, since the test fixtures come to this module as a jar.
        val script = requireNotNull(Fixtures::class.java.getResource("/fixture/openrsc.sql")).readText()
        for (secret in secrets) {
            assertTrue(secret in script, "fixture/openrsc.sql no longer holds $secret")
        }
    }

    private fun keysIn(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.keys.toList() + element.values.flatMap(::keysIn)
        is JsonArray -> element.flatMap(::keysIn)
        else -> emptyList()
    }
}
