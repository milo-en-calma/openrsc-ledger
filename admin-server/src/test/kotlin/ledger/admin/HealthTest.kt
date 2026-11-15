package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import ledger.core.copy.CopyRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class HealthTest {
    @Test
    fun `health says ok before the first copy`() = testApplication {
        application { adminModule(lastCopy = { null }, gameData = NoGameData) }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok","dataCopiedAt":null}""", response.bodyAsText())
    }

    @Test
    fun `health says when the data was copied`() = testApplication {
        val copiedAt = Instant.parse("2026-01-01T09:20:00Z")
        application { adminModule(lastCopy = { CopyRecord(copiedAt = copiedAt, liveModified = copiedAt) }, gameData = NoGameData) }
        assertEquals(
            """{"status":"ok","dataCopiedAt":"2026-01-01T09:20:00Z"}""",
            client.get("/api/health").bodyAsText(),
        )
    }
}
