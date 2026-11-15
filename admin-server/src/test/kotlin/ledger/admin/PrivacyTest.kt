package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ledger.core.fixture.Fixtures
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import ledger.core.game.SqliteGameData
import ledger.core.history.HistoryReader
import ledger.core.history.SkillChange
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/**
 * Calls every GET route in the committed `openapi.json` against the test database and checks
 * that no response carries the database's private columns: password hashes, salts, email
 * addresses and IP addresses. A new route is covered as soon as it is in the spec.
 */
class PrivacyTest {
    /** History for every test player, so the history routes answer with names too. */
    private val history = object : HistoryReader {
        override fun recordedSince(): Instant = Instant.parse("2026-01-01T08:00:00Z")
        override fun changes(from: Instant, to: Instant): List<SkillChange> = (1..3).map {
            SkillChange(it, Skill.WOODCUT, start = SkillValue(0, 1), end = SkillValue(400, 2))
        }
    }

    /**
     * A value for each parameter a route may take, chosen so each route answers with data:
     * a player and an item the test database has, and a period covering the history above.
     * A route with a parameter missing here fails the test rather than being skipped.
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

    /** The test database's invented values for the private columns (`fixture/openrsc.sql`). */
    private val secrets = listOf("placeholder-hash", "placeholder-salt", "@example.invalid", "192.0.2.")

    /** The private columns' names, which shouldn't appear as keys either. */
    private val privateKeys = setOf("email", "pass", "password", "salt", "creation_ip", "login_ip", "ip")

    @Test
    fun `no route sends private player data`() = adminTest(SqliteGameData(testDatabase()), history = history) {
        val urls = getRoutes().map(::sampleUrl)
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

    /** Every GET route in the committed spec, as its path and its parameters' names. */
    private fun getRoutes(): List<Pair<String, List<String>>> {
        val spec = Json.parseToJsonElement(Files.readString(Path.of("openapi.json"))).jsonObject
        return spec.getValue("paths").jsonObject.mapNotNull { (path, operations) ->
            val get = operations.jsonObject["get"]?.jsonObject ?: return@mapNotNull null
            val parameters = get["parameters"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("name").jsonPrimitive.content }
            path to parameters
        }
    }

    /** The route's URL with every parameter given its sample value. */
    private fun sampleUrl(route: Pair<String, List<String>>): String {
        val (path, parameters) = route
        var url = path
        val query = mutableListOf<String>()
        for (name in parameters) {
            val value = sampleValues[name] ?: fail("No sample value for $name in $path; add one to sampleValues")
            if ("{$name}" in url) {
                url = url.replace("{$name}", value)
            } else {
                query += "$name=$value"
            }
        }
        return if (query.isEmpty()) url else "$url?${query.joinToString("&")}"
    }

    private fun keysIn(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.keys.toList() + element.values.flatMap(::keysIn)
        is JsonArray -> element.flatMap(::keysIn)
        else -> emptyList()
    }
}
