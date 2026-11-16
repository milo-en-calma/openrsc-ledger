package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real built frontend, as the installed app serves it from the `ledger-web` jar. Runs only in
 * the `webTest` Gradle task, which puts that jar on the classpath ahead of the test resources'
 * stand-in page; `./gradlew check` runs it with the rest.
 */
class BuiltWebTest {
    private val webSource = Path.of(System.getProperty("ledger.webSource") ?: fail("Run this test with ./gradlew :admin-server:webTest"))

    /** A file from the built frontend, read from the jar, never from the test resources. */
    private fun builtFile(path: String): ByteArray {
        val resource = assertNotNull(javaClass.classLoader.getResource("web/$path"), "web/$path isn't on the classpath")
        assertEquals("jar", resource.protocol, "web/$path came from $resource, not the ledger-web jar")
        return resource.readBytes()
    }

    private val builtPage = builtFile("index.html")

    /** A value for each parameter in the frontend's page addresses; a page with another parameter fails the test. */
    private val pageValues = mapOf(
        "id" to listOf("1"),
        "board" to listOf("overall", "woodcut", "gained-today"),
        "itemId" to listOf("10"),
    )

    /** Every page address the frontend's router knows, from `<Route path="…">` in App.tsx, each parameter filled in. */
    private fun pageAddresses(): List<String> {
        val routes = Regex("""<Route path="([^"]+)"""").findAll(webSource.resolve("App.tsx").readText()).map { it.groupValues[1] }.toList()
        assertTrue(routes.size >= 6, "found only $routes in App.tsx; has the router's form changed?")
        return routes.flatMap { route ->
            Regex(":(\\w+)").findAll(route).map { it.groupValues[1] }.fold(listOf(route)) { addresses, name ->
                val values = pageValues[name] ?: fail("No value for :$name in $route; add one to pageValues")
                addresses.flatMap { address -> values.map { address.replace(":$name", it) } }
            }
        }
    }

    @Test
    fun `the page served is the built one, not a stand-in`() {
        val page = builtPage.decodeToString()
        assertTrue("<title>openrsc-ledger</title>" in page, page)
        assertTrue("""<div id="root">""" in page, page)
    }

    @Test
    fun `every page address the router knows gets the built page`() = adminTest {
        val addresses = pageAddresses()
        assertTrue("/players/1" in addresses && "/leaderboards/gained-today" in addresses, "addresses were $addresses")
        for (address in addresses + listOf("/index.html", "/leaderboards/woodcut?staff=1")) {
            val response = client.get(address)
            assertEquals(HttpStatusCode.OK, response.status, address)
            assertTrue(response.contentType()!!.match(ContentType.Text.Html), "$address answered ${response.contentType()}")
            assertContentEquals(builtPage, response.bodyAsBytes(), address)
        }
    }

    @Test
    fun `every script and stylesheet the page links to is served as itself`() = adminTest {
        val links = Regex("""(?:src|href)="(/[^"]+)"""").findAll(builtPage.decodeToString()).map { it.groupValues[1] }.toList()
        assertTrue(links.any { it.endsWith(".js") } && links.any { it.endsWith(".css") }, "links were $links")
        for (link in links) {
            val response = client.get(link)
            assertEquals(HttpStatusCode.OK, response.status, link)
            val type = response.contentType()!!.withoutParameters()
            when {
                link.endsWith(".js") -> assertTrue(type == ContentType.Application.JavaScript || type == ContentType.Text.JavaScript, "$link answered $type")
                link.endsWith(".css") -> assertEquals(ContentType.Text.CSS, type, link)
                else -> fail("The page links to $link, which this test doesn't know the type of")
            }
            assertContentEquals(builtFile(link.removePrefix("/")), response.bodyAsBytes(), link)
        }
    }

    @Test
    fun `every API address the frontend's code names is in the served spec`() = adminTest(copied = false) {
        val specPaths = Json.parseToJsonElement(client.get(OPENAPI_PATH).bodyAsText()).jsonObject.getValue("paths").jsonObject.keys
        val sources = Files.walk(webSource).use { files -> files.filter { it.isRegularFile() && it.extension in setOf("ts", "tsx") }.toList() }
        val named = sources.flatMap { file ->
            Regex("""["'`](/api[^"'`]*)["'`]""").findAll(file.readText()).map { match ->
                val address = match.groupValues[1]
                if ("\${" in address) {
                    fail("${webSource.relativize(file)} builds an API address from parts ($address); name it whole, through api.GET")
                }
                webSource.relativize(file) to address
            }.toList()
        }
        assertTrue(named.size >= 8, "found only $named; has the API client's form changed?")
        for ((file, address) in named) {
            assertTrue(address in specPaths, "$file asks for $address, which isn't in the spec: $specPaths")
        }
    }

    @Test
    fun `the page carries no caching headers, so a reload picks up a new build`() = adminTest {
        for (address in listOf("/", "/players/1")) {
            val headers = client.get(address).headers
            for (name in listOf(HttpHeaders.CacheControl, HttpHeaders.Expires, HttpHeaders.ETag, HttpHeaders.LastModified)) {
                assertEquals(null, headers[name], "$address sent $name")
            }
        }
    }
}
