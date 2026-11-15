package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthTest {
    @Test
    fun `health says ok before the first copy`() = adminTest(copied = false) {
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok","dataCopiedAt":null}""", response.bodyAsText())
    }

    @Test
    fun `health says when the data was copied`() = adminTest {
        assertEquals(
            """{"status":"ok","dataCopiedAt":"2026-01-01T09:20:00Z"}""",
            client.get("/api/health").bodyAsText(),
        )
    }
}
