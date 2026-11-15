package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import ledger.admin.routes.NO_COPY_YET
import ledger.core.game.SqliteGameData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Before the ledger has copied the server's database once, every route that reads game data
 * says so instead of answering. Calls every GET route in the committed `openapi.json`, so a
 * new route is covered as soon as it is in the spec.
 */
class NoCopyYetTest {
    /** The only routes that answer before the first copy: they don't read game data. */
    private val answerWithoutACopy = setOf("/api/health", "/api/leaderboards")

    @Test
    fun `every route that reads game data waits for the first copy`() = adminTest(SqliteGameData(testDatabase()), copied = false) {
        val routes = specGetRoutes()
        assertTrue(routes.size >= 8, "Found only ${routes.size} routes in openapi.json")
        assertTrue(answerWithoutACopy.all { path -> routes.any { it.path == path } }, "a route in answerWithoutACopy is gone from openapi.json")

        for (route in routes) {
            val response = client.get(route.url)
            if (route.path in answerWithoutACopy) {
                assertEquals(HttpStatusCode.OK, response.status, route.url)
            } else {
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status, route.url)
                assertEquals("""{"message":"$NO_COPY_YET"}""", response.bodyAsText(), route.url)
                assertTrue("503" in route.statuses, "${route.path} doesn't declare its 503 in openapi.json")
            }
        }
    }
}
