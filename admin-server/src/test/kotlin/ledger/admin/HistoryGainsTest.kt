package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import ledger.core.copy.CopyRecord
import ledger.core.fixture.Fixtures
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import ledger.core.game.SqliteGameData
import ledger.core.history.HistoryReader
import ledger.core.history.SkillChange
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class HistoryGainsTest {
    private val copy = Fixtures.database(Files.createTempDirectory("gains").resolve("copy.db"))
    private val copied = CopyRecord(copiedAt = Instant.parse("2026-01-01T09:20:00Z"), liveModified = Instant.parse("2026-01-01T09:19:00Z"))
    private val began = Instant.parse("2026-01-01T08:00:00Z")

    /** Invented history: the same changes for any period, remembering the period it was asked for. */
    private class InventedHistory(private val began: Instant, private val changes: List<SkillChange>) : HistoryReader {
        var askedFor: Pair<Instant, Instant>? = null
        override fun recordedSince(): Instant = began
        override fun changes(from: Instant, to: Instant): List<SkillChange> {
            askedFor = from to to
            return changes
        }
    }

    private val history = InventedHistory(
        began,
        listOf(
            SkillChange(3, Skill.MINING, start = SkillValue(0, 1), end = SkillValue(400, 2)),
            SkillChange(1, Skill.WOODCUT, start = SkillValue(4000, 10), end = SkillValue(8000, 16)),
            SkillChange(2, Skill.WOODCUT, start = SkillValue(0, 1), end = SkillValue(400, 2)),
            // A player no longer in the server's database.
            SkillChange(9, Skill.FISHING, start = SkillValue(0, 1), end = SkillValue(40, 1)),
        ),
    )

    @Test
    fun `lists gains most experience first, with names, as players see experience`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy), history = history) }
        assertEquals(
            """{"from":"2026-01-01T09:00:00Z","to":"2026-01-01T10:00:00Z","recordedSince":"2026-01-01T08:00:00Z","gains":[""" +
                """{"playerId":1,"name":"Bramblewick","skill":"woodcut","skillName":"Woodcutting","experienceGained":1000,"levelsGained":6,"experience":2000,"level":16},""" +
                """{"playerId":2,"name":"Tansy Moss","skill":"woodcut","skillName":"Woodcutting","experienceGained":100,"levelsGained":1,"experience":100,"level":2},""" +
                """{"playerId":3,"name":"Oakhollow","skill":"mining","skillName":"Mining","experienceGained":100,"levelsGained":1,"experience":100,"level":2},""" +
                """{"playerId":9,"name":null,"skill":"fishing","skillName":"Fishing","experienceGained":10,"levelsGained":0,"experience":10,"level":1}]}""",
            client.get("/api/history/gains?from=2026-01-01T09:00:00Z&to=2026-01-01T10:00:00Z").bodyAsText(),
        )
        assertEquals(Instant.parse("2026-01-01T09:00:00Z") to Instant.parse("2026-01-01T10:00:00Z"), history.askedFor)
    }

    @Test
    fun `narrows to one player or one skill`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy), history = history) }
        val player = Regex(""""playerId":(\d+)""")
        suspend fun players(query: String) =
            player.findAll(client.get("/api/history/gains?from=2026-01-01T09:00:00Z&$query").bodyAsText()).map { it.groupValues[1].toInt() }.toList()

        assertEquals(listOf(1, 2), players("skill=woodcut"))
        assertEquals(listOf(3), players("player=3"))
        assertEquals(listOf(2), players("player=2&skill=woodcut"))
    }

    @Test
    fun `the period's end is now if left out`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy), history = history) }
        client.get("/api/history/gains?from=2026-01-01T09:00:00Z")
        val (_, to) = history.askedFor!!
        assertTrue(to > Instant.parse("2026-01-01T09:00:00Z"), "to was $to")
    }

    @Test
    fun `refuses a period or skill it can't read`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy), history = history) }
        for (query in listOf("", "from=yesterday", "from=2026-01-02T00:00:00Z&to=2026-01-01T00:00:00Z", "from=2026-01-01T00:00:00Z&to=soon", "from=2026-01-01T00:00:00Z&skill=juggling")) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/history/gains?$query").status, query)
        }
    }

    @Test
    fun `waits for the first copy`() = testApplication {
        application { adminModule(lastCopy = { null }, gameData = SqliteGameData(copy), history = history) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/history/gains?from=2026-01-01T00:00:00Z").status)
    }

    @Test
    fun `with nothing recorded there's no start and no gains`() = testApplication {
        application { adminModule(lastCopy = { copied }, gameData = SqliteGameData(copy)) }
        assertEquals(
            """{"from":"2026-01-01T00:00:00Z","to":"2026-01-02T00:00:00Z","recordedSince":null,"gains":[]}""",
            client.get("/api/history/gains?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z").bodyAsText(),
        )
    }
}
