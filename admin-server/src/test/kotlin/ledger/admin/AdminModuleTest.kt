package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the admin module serves besides the API's routes: the frontend for every other address,
 * and a 404 for an address under /api that is no route. The frontend here is the stand-in
 * `web/index.html` in the test resources.
 */
class AdminModuleTest {
    private val noSuchRoute = """{"message":"$NO_SUCH_API_ROUTE"}"""

    private suspend fun ApplicationTestBuilder.assertFrontend(path: String) {
        val response = client.get(path)
        assertEquals(HttpStatusCode.OK, response.status, path)
        assertTrue(response.contentType()!!.match(ContentType.Text.Html), "$path answered ${response.contentType()}")
        assertTrue("<title>test frontend</title>" in response.bodyAsText(), path)
    }

    private suspend fun ApplicationTestBuilder.assertNoSuchRoute(path: String) {
        val response = client.get(path)
        assertEquals(HttpStatusCode.NotFound, response.status, path)
        assertEquals(noSuchRoute, response.bodyAsText(), path)
    }

    @Test
    fun `an address no page claims still gets the frontend, which shows its not-found page`() = adminTest {
        // The addresses pages claim are BuiltWebTest's, against the real built page.
        for (path in listOf("/players", "/players/1/history", "/no-such-page")) {
            assertFrontend(path)
        }
    }

    @Test
    fun `an address that only starts like the API's is the frontend's`() = adminTest {
        assertFrontend("/apis")
        assertFrontend("/api-docs")
    }

    @Test
    fun `an unknown address under api is a 404, not the frontend`() = adminTest {
        for (path in listOf("/api", "/api/", "/api/no-such-route", "/api/player", "/api/players/1/no-such-page", "/api/index.html")) {
            assertNoSuchRoute(path)
        }
    }

    @Test
    fun `a real route with a trailing slash is a 404, since it isn't that route`() = adminTest {
        assertNoSuchRoute("/api/players/")
        assertNoSuchRoute("/api/health/")
    }

    @Test
    fun `an unknown address under api is a 404 before the first copy too`() = adminTest(copied = false) {
        assertNoSuchRoute("/api/no-such-route")
    }

    @Test
    fun `a write to the read-only API is a 404 too`() = adminTest {
        val response = client.post("/api/players")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(noSuchRoute, response.bodyAsText())
    }

    @Test
    fun `the real routes still answer, the spec included`() = adminTest {
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/players").status)
        val spec = client.get(OPENAPI_PATH)
        assertEquals(HttpStatusCode.OK, spec.status)
        assertTrue(spec.contentType()!!.match(ContentType.Application.Json), "the spec answered ${spec.contentType()}")
    }
}
