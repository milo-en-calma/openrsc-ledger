package ledger.admin

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ledger.admin.routes.BAD_ITEM_ID
import ledger.admin.routes.BAD_PLAYER
import ledger.admin.routes.BAD_PLAYER_ID
import ledger.admin.routes.BAD_SEARCH
import ledger.admin.routes.NO_SUCH_ITEM
import ledger.admin.routes.NO_SUCH_PLAYER
import ledger.admin.routes.unknownQueryName
import ledger.core.game.SqliteGameData
import ledger.core.history.HistoryStore
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sends every route in the committed `openapi.json` the values a mistyped or hand-edited link
 * could carry, in each path and query value it lists, so a route or value added later is
 * covered as soon as it is in the spec. Runs on the Netty engine the ledger serves with, on a
 * free port, since the in-memory test engine doesn't apply Netty's limits, and sends each
 * request as raw text, so a value reaches the server exactly as written here.
 */
class BadValuesTest {
    /** A value, already %-escaped where it needs to be, as it would sit in an address. */
    private val badValues = listOf(
        "", "-1", "0", "99999999999999999999", "abc", "1.5", "%201", "01x", "TRUE", "null",
        "%00", "%F0%9F%90%9F", "%2F", "1%2F2", "..", "%25", "%5F",
        "2026-01-01", "-999999999-01-01T00:00:00Z", "+999999999-12-31T23:59:59.999999999Z",
    )

    /** Path values that aren't a whole number from 0 to 2147483647, already %-escaped where they need to be. */
    private val unreadableIds = listOf(
        "abc", "-1", "1.5", "2147483648", "99999999999999999999", "%201", "1%20", "01x", "0x10", "1e3", "TRUE", "null", "NaN",
        "%00", "%F0%9F%90%9F", "%25",
    )

    /** The id routes, each with its message for an id it can't read, and an id that reads but nobody has. */
    private val idRoutes = listOf(
        Triple("/api/players/{id}", BAD_PLAYER_ID, NO_SUCH_PLAYER),
        Triple("/api/items/{itemId}/holders", BAD_ITEM_ID, NO_SUCH_ITEM),
    )

    /** A value the web server can't decode: it answers before the ledger's code sees the request. */
    private val brokenEscape = "%zz"

    /** Long enough that the request line passes Netty's 4,096-byte limit. */
    private val tooLong = "a".repeat(10_000)

    @Test
    fun `no bad value gets a server error, and every answer is one the spec lists`() = onNetty { port ->
        val routes = specRoutes()
        assertTrue(routes.size >= 8, "Found only ${routes.size} routes in openapi.json")
        var sent = 0
        for (route in routes) {
            for (parameter in route.parameters) {
                for (value in badValues) {
                    val target = route.url(parameter to value)
                    val answer = get(port, target)
                    sent++
                    assertTrue(answer.status in route.statuses, "$target answered ${answer.status}, which the spec doesn't list for ${route.path}")
                    if (answer.status != 200) { assertIsMessage(answer, target) }
                }
            }
        }
        assertTrue(sent >= 200, "Sent only $sent requests")
    }

    @Test
    fun `an address the web server can't read gets its plain-text 400`() = onNetty { port ->
        for (route in specRoutes()) {
            for (parameter in route.parameters) {
                val broken = route.url(parameter to brokenEscape)
                val decodeFailed = get(port, broken)
                assertEquals(400, decodeFailed.status, broken)
                val expected = if (parameter.inPath) "Url decode failed for $broken" else "Unable to decode query parameters"
                assertEquals(expected, decodeFailed.body, broken)

                val long = route.url(parameter to tooLong)
                val tooLongAnswer = get(port, long)
                assertEquals(400, tooLongAnswer.status, "${route.path} with a long $parameter")
                assertEquals("An HTTP line is larger than 4096 bytes.", tooLongAnswer.body, "${route.path} with a long $parameter")
            }
        }
    }

    @Test
    fun `a query value given twice answers 400, naming it`() = onNetty { port ->
        var checked = 0
        for (route in specRoutes()) {
            for (parameter in route.parameters.filterNot { it.inPath }) {
                val sample = sampleValues.getValue(parameter.name)
                for (pair in listOf(listOf(sample, sample), listOf(sample, "abc"), listOf("abc", sample), listOf("true", "false"))) {
                    val target = route.url(parameter to pair.joinToString("&${parameter.name}="))
                    val answer = get(port, target)
                    assertEquals(400, answer.status, target)
                    assertEquals(message(givenTwice(parameter.name)), answer.body, target)
                    checked++
                }
            }
        }
        assertTrue(checked >= 20, "Checked only $checked pairs")
    }

    @Test
    fun `a query value the route doesn't read is still refused when given twice`() = onNetty { port ->
        val answer = get(port, "/api/economy?x=1&x=2")
        assertEquals(400, answer.status)
        assertEquals(message(givenTwice("x")), answer.body)
    }

    @Test
    fun `a player search holding a control character answers 400`() = onNetty { port ->
        for (control in listOf("%00", "o%00", "%09", "%0A", "o%0D", "%1F", "%7F", "%C2%85")) {
            val answer = get(port, "/api/players?search=$control")
            assertEquals(400, answer.status, control)
            assertEquals(message(BAD_SEARCH), answer.body, control)
        }
        // A space alone is no search at all, and an emoji is plain text that matches no one.
        assertEquals(3, players(get(port, "/api/players?search=%20")).size)
        assertEquals(0, players(get(port, "/api/players?search=%F0%9F%90%9F")).size)
    }

    @Test
    fun `a number written with a leading plus is read as the number`() = onNetty { port ->
        val page = Json.parseToJsonElement(get(port, "/api/players/+1").body)
        assertEquals(1, page["player"]["id"].jsonPrimitive.content.toInt())
        val holders = Json.parseToJsonElement(get(port, "/api/items/+10/holders").body)
        assertEquals(10, holders["itemId"].jsonPrimitive.content.toInt())
        assertEquals(200, get(port, "/api/history/gains?from=2026-01-01T00:00:00Z&player=%2B1").status)
        // In a query a bare "+" stands for a space, so this asks for player " 1", which isn't a number.
        val spaced = get(port, "/api/history/gains?from=2026-01-01T00:00:00Z&player=+1")
        assertEquals(400, spaced.status)
        assertEquals(message(BAD_PLAYER), spaced.body)
    }

    @Test
    fun `an id in the path that isn't a whole number answers 400, before and after the first copy`() {
        for (copied in listOf(true, false)) {
            onNetty(copied) { port ->
                for ((path, unreadable, nobody) in idRoutes) {
                    for (id in unreadableIds) {
                        val target = path.replace(Regex("\\{[^}]+}"), id)
                        assertEquals(Answer(400, message(unreadable)), get(port, target), "$target, copied=$copied")
                    }
                    // A whole number nobody has reads fine: it's no one, not a mistake in the link.
                    if (copied) {
                        val target = path.replace(Regex("\\{[^}]+}"), "2147483647")
                        assertEquals(Answer(404, message(nobody)), get(port, target), target)
                    }
                }
            }
        }
    }

    @Test
    fun `a query name a route doesn't take answers 400, naming the ones it does, before and after the first copy`() {
        for (copied in listOf(true, false)) {
            onNetty(copied) { port ->
                val routes = specRoutes()
                for (route in routes) {
                    val taken = route.parameters.filterNot { it.inPath }.map { it.name }
                    for (unknown in listOf("nosuch", "plyer", "STAFF", "staff_")) {
                        val target = route.sample + (if ('?' in route.sample) "&" else "?") + "$unknown=1"
                        assertEquals(Answer(400, message(unknownQueryName(unknown, taken))), get(port, target), "$target, copied=$copied")
                    }
                }
                assertTrue(routes.any { route -> route.parameters.count { !it.inPath } >= 2 }, "no route in openapi.json takes two query values")
            }
        }
    }

    @Test
    fun `an unknown query name names every value the gains take`() = onNetty { port ->
        val answer = get(port, "/api/history/gains?from=2026-01-01T00:00:00Z&plyer=1")
        assertEquals(400, answer.status)
        assertEquals(
            message("There's no query value `plyer` here; this route takes `from`, `to`, `player`, `skill` or `staff`."),
            answer.body,
        )
        assertEquals(message("There's no query value `x` here; this route takes none."), get(port, "/api/economy?x=1").body)
        assertEquals(message("There's no query value `stafff` here; this route takes `staff`."), get(port, "/api/leaderboards/overall?stafff=true").body)
    }

    /** One value a route takes: its [name], and whether it sits in the path rather than the query. */
    private data class Parameter(val name: String, val inPath: Boolean) {
        override fun toString() = name
    }

    /** A GET route from the committed spec, with the values it takes and the status codes it lists. */
    private data class Route(val path: String, val parameters: List<Parameter>, val statuses: Set<Int>) {
        /** An address for this route with every value at its sample. */
        val sample: String get() = url(null)

        /** An address for this route with every value at its sample, except [given]'s. */
        fun url(given: Pair<Parameter, String>?): String {
            var url = path
            val query = mutableListOf<String>()
            for (parameter in parameters) {
                val value = if (given != null && parameter == given.first) given.second else sampleValues.getValue(parameter.name)
                if (parameter.inPath) { url = url.replace("{${parameter.name}}", value) } else { query += "${parameter.name}=$value" }
            }
            return if (query.isEmpty()) url else "$url?${query.joinToString("&")}"
        }
    }

    private fun specRoutes(): List<Route> {
        val spec = Json.parseToJsonElement(Files.readString(Path.of("openapi.json"))).jsonObject
        return spec.getValue("paths").jsonObject.mapNotNull { (path, operations) ->
            val get = operations.jsonObject["get"]?.jsonObject ?: return@mapNotNull null
            val parameters = get["parameters"]?.jsonArray.orEmpty().map {
                Parameter(it["name"].jsonPrimitive.content, inPath = it["in"].jsonPrimitive.content == "path")
            }
            Route(path, parameters, get.getValue("responses").jsonObject.keys.map { it.toInt() }.toSet())
        }
    }

    private data class Answer(val status: Int, val body: String)

    /** GETs [target] as raw text, exactly as written, so nothing re-escapes it on the way. */
    private fun get(port: Int, target: String): Answer = Socket(HOST, port).use { socket ->
        socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: $HOST\r\nConnection: close\r\n\r\n".toByteArray())
        val text = socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
        Answer(status = text.substringAfter(' ').take(3).toInt(), body = text.substringAfter("\r\n\r\n"))
    }

    /** Fails unless [answer] is the API's error shape, a JSON object holding only a `message`, with no stack trace in it. */
    private fun assertIsMessage(answer: Answer, target: String) {
        val json = runCatching { Json.parseToJsonElement(answer.body) }.getOrNull()
        assertTrue(json is JsonObject && json.keys == setOf("message") && json["message"] is JsonPrimitive, "$target answered ${answer.body}")
        assertTrue("Exception" !in answer.body && "\tat " !in answer.body, "$target answered with a stack trace")
    }

    private fun message(text: String) = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("message" to JsonPrimitive(text))))

    private fun players(answer: Answer) = Json.parseToJsonElement(answer.body)["players"].jsonArray

    /**
     * Runs [block] against the admin module on Netty, on a free port, over the test database's
     * invented players, or as before the first copy when [copied] is false.
     */
    private fun onNetty(copied: Boolean = true, block: (port: Int) -> Unit) {
        val history = HistoryStore(Files.createTempDirectory("ledger-bad-values").resolve("history.db"))
        val server = embeddedServer(Netty, host = HOST, port = 0) {
            adminModule(lastCopy = { if (copied) COPIED else null }, gameData = SqliteGameData(testDatabase()), history = history)
        }.start(wait = false)
        try {
            block(runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            server.stop(100, 1_000)
        }
    }
}
