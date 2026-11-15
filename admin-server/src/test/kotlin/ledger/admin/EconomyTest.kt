package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import ledger.core.copy.CopyRecord
import ledger.core.fixture.Fixtures
import ledger.core.game.SqliteGameData
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class EconomyTest {
    private val copy = Fixtures.database(Files.createTempDirectory("economy").resolve("copy.db"))
    private val copied = CopyRecord(copiedAt = Instant.parse("2026-01-01T09:20:00Z"), liveModified = Instant.parse("2026-01-01T09:19:00Z"))

    @Test
    fun `totals coins and lists items most-held first, with names`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy)) }
        val body = client.get("/api/economy").bodyAsText()
        assertTrue(body.startsWith("""{"coins":{"inInventories":1250,"inBanks":50000,"total":51250},"items":["""), body)
        assertTrue(
            """"items":[{"itemId":10,"name":"Coins","held":{"inInventories":1250,"inBanks":50000,"total":51250},"holders":1},""" +
                """{"itemId":14,"name":"Logs","held":{"inInventories":0,"inBanks":120,"total":120},"holders":1},""" in body,
            body,
        )
    }

    @Test
    fun `no coins is zero, not missing`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = NoGameData) }
        assertEquals("""{"coins":{"inInventories":0,"inBanks":0,"total":0},"items":[]}""", client.get("/api/economy").bodyAsText())
    }

    @Test
    fun `lists who holds an item, with the world's total`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy)) }
        assertEquals(
            """{"itemId":10,"name":"Coins","held":{"inInventories":1250,"inBanks":50000,"total":51250},"holders":[""" +
                """{"playerId":1,"name":"Bramblewick","staff":false,"held":{"inInventories":1250,"inBanks":50000,"total":51250}}]}""",
            client.get("/api/items/10/holders").bodyAsText(),
        )
    }

    @Test
    fun `a named item nobody holds has no holders, and an unknown one isn't found`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy)) }
        // Item 1 is an iron short sword, which nobody in the test database holds.
        assertEquals(
            """{"itemId":1,"name":"Iron Short Sword","held":{"inInventories":0,"inBanks":0,"total":0},"holders":[]}""",
            client.get("/api/items/1/holders").bodyAsText(),
        )
        assertEquals(HttpStatusCode.NotFound, client.get("/api/items/999999/holders").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/items/coins/holders").status)
    }

    @Test
    fun `waits for the first copy`() = testApplication {
        application { adminModule(lastCopy = { null }, gameData = SqliteGameData(copy)) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/economy").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/items/10/holders").status)
    }
}
