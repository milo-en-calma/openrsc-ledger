package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import ledger.core.copy.CopyRecord
import ledger.core.fixture.Fixtures
import ledger.core.game.SqliteGameData
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class LeaderboardsTest {
    private val copy = Fixtures.database(Files.createTempDirectory("boards").resolve("copy.db"))
    private val copied = CopyRecord(copiedAt = Instant.parse("2026-01-01T09:20:00Z"), liveModified = Instant.parse("2026-01-01T09:19:00Z"))

    @Test
    fun `lists overall first, then every skill`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy)) }
        val body = client.get("/api/leaderboards").bodyAsText()
        assertTrue(body.startsWith("""{"boards":[{"key":"overall","name":"Overall"},{"key":"attack","name":"Attack"},"""), body)
        assertEquals(21, Regex(""""key":""").findAll(body).count())
    }

    @Test
    fun `a skill's board ranks by experience as players see it`() = testApplication {
        DriverManager.getConnection("jdbc:sqlite:$copy").use {
            it.createStatement().executeUpdate("UPDATE experience SET woodcut = 2000 WHERE playerID = 3")
            it.createStatement().executeUpdate("UPDATE maxstats SET woodcut = 5 WHERE playerID = 3")
        }
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy)) }
        assertEquals(
            """{"board":{"key":"woodcut","name":"Woodcutting"},"entries":[""" +
                """{"rank":1,"playerId":3,"name":"Oakhollow","level":5,"experience":500},""" +
                """{"rank":2,"playerId":1,"name":"Bramblewick","level":1,"experience":0},""" +
                """{"rank":2,"playerId":2,"name":"Tansy Moss","level":1,"experience":0}]}""",
            client.get("/api/leaderboards/woodcut").bodyAsText(),
        )
    }

    @Test
    fun `the overall board ranks by total level`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy)) }
        val body = client.get("/api/leaderboards/overall").bodyAsText()
        assertEquals(listOf("Bramblewick", "Oakhollow", "Tansy Moss"), Regex(""""name":"([^"]*)","level"""").findAll(body).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `an unknown board is not found, and boards wait for the first copy`() = testApplication {
        application { adminModule(lastCopy = { null }, gameData = SqliteGameData(copy)) }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/leaderboards/juggling").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/leaderboards/overall").status)
    }
}
