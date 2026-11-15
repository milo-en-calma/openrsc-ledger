package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthTest {
    @Test
    fun `health says ok`() = testApplication {
        application { adminModule() }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok"}""", response.bodyAsText())
    }
}
