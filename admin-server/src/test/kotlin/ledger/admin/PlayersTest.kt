package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import ledger.admin.routes.NO_COPY_YET
import ledger.core.copy.CopyRecord
import ledger.core.fixture.Fixtures
import ledger.core.game.SqliteGameData
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class PlayersTest {
    private val copy = Fixtures.database(Files.createTempDirectory("players").resolve("copy.db"))
    private val copied = CopyRecord(copiedAt = Instant.parse("2026-01-01T09:20:00Z"), liveModified = Instant.parse("2026-01-01T09:19:00Z"))

    @Test
    fun `lists every player`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy)) }
        val response = client.get("/api/players")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            """{"players":[""" +
                """{"id":1,"name":"Bramblewick","combatLevel":32,"totalLevel":210,"lastLogin":"2024-10-27T03:33:20Z","online":false},""" +
                """{"id":3,"name":"Oakhollow","combatLevel":14,"totalLevel":95,"lastLogin":"2024-11-07T17:20:00Z","online":true},""" +
                """{"id":2,"name":"Tansy Moss","combatLevel":3,"totalLevel":27,"lastLogin":"2024-07-03T09:55:00Z","online":false}""" +
                """]}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `searches by name`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy)) }
        val body = client.get("/api/players?search=moss").bodyAsText()
        assertEquals(listOf("Tansy Moss"), Regex(""""name":"([^"]*)"""").findAll(body).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `never sends passwords, salts, emails or addresses`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy)) }
        val body = client.get("/api/players").bodyAsText()
        // The fixture's placeholders for those columns: "placeholder-hash-1", "...@example.invalid", "192.0.2.10".
        for (secret in listOf("placeholder", "example.invalid", "192.0.2.")) {
            assertFalse(secret in body, "The player list contains $secret")
        }
    }

    @Test
    fun `says there is no data before the first copy`() = testApplication {
        application { adminModule(lastCopy = { null }, gameData = SqliteGameData(copy)) }
        val response = client.get("/api/players")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("""{"message":"$NO_COPY_YET"}""", response.bodyAsText())
    }
}
