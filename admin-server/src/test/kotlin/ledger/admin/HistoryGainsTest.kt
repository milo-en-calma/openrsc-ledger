package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import ledger.core.game.GameData
import ledger.core.game.PlayerSummary
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import ledger.core.game.SqliteGameData
import ledger.core.history.HistoryReader
import ledger.core.history.SkillChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class HistoryGainsTest {
    private val game = SqliteGameData(testDatabase())

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
        Instant.parse("2026-01-01T08:00:00Z"),
        listOf(
            SkillChange(3, Skill.MINING, start = SkillValue(0, 1), end = SkillValue(400, 2)),
            SkillChange(1, Skill.WOODCUT, start = SkillValue(4000, 10), end = SkillValue(8000, 16)),
            SkillChange(2, Skill.WOODCUT, start = SkillValue(0, 1), end = SkillValue(400, 2)),
            // A player no longer in the server's database.
            SkillChange(9, Skill.FISHING, start = SkillValue(0, 1), end = SkillValue(40, 1)),
        ),
    )

    @Test
    fun `lists gains most experience first, with names, as players see experience`() = adminTest(game, history = history) {
        assertEquals(
            """{"from":"2026-01-01T09:00:00Z","to":"2026-01-01T10:00:00Z","recordedSince":"2026-01-01T08:00:00Z","gains":[""" +
                """{"playerId":1,"name":"Bramblewick","skill":"woodcut","skillName":"Woodcutting","experienceGained":1000,"levelsGained":6,"experience":2000,"level":16,"staff":false},""" +
                """{"playerId":2,"name":"Tansy Moss","skill":"woodcut","skillName":"Woodcutting","experienceGained":100,"levelsGained":1,"experience":100,"level":2,"staff":false},""" +
                """{"playerId":3,"name":"Oakhollow","skill":"mining","skillName":"Mining","experienceGained":100,"levelsGained":1,"experience":100,"level":2,"staff":false},""" +
                """{"playerId":9,"name":null,"skill":"fishing","skillName":"Fishing","experienceGained":10,"levelsGained":0,"experience":10,"level":1,"staff":false}]}""",
            client.get("/api/history/gains?from=2026-01-01T09:00:00Z&to=2026-01-01T10:00:00Z").bodyAsText(),
        )
        assertEquals(Instant.parse("2026-01-01T09:00:00Z") to Instant.parse("2026-01-01T10:00:00Z"), history.askedFor)
    }

    @Test
    fun `narrows to one player or one skill`() = adminTest(game, history = history) {
        suspend fun players(query: String) = getJson("/api/history/gains?from=2026-01-01T09:00:00Z&$query")["gains"].ints("playerId")

        assertEquals(listOf(1, 2), players("skill=woodcut"))
        assertEquals(listOf(3), players("player=3"))
        assertEquals(listOf(2), players("player=2&skill=woodcut"))
    }

    @Test
    fun `the period's end is now if left out`() = adminTest(game, history = history) {
        client.get("/api/history/gains?from=2026-01-01T09:00:00Z")
        val (_, to) = history.askedFor!!
        assertTrue(to > Instant.parse("2026-01-01T09:00:00Z"), "to was $to")
    }

    @Test
    fun `refuses a period, player or skill it can't read`() = adminTest(game, history = history) {
        val unreadable = listOf(
            "",
            "from=yesterday",
            "from=2026-01-02T00:00:00Z&to=2026-01-01T00:00:00Z",
            "from=2026-01-01T00:00:00Z&to=soon",
            "from=2026-01-01T00:00:00Z&skill=juggling",
            "from=2026-01-01T00:00:00Z&player=Bramblewick",
            "from=2026-01-01T00:00:00Z&player=",
            "from=2026-01-01T00:00:00Z&player=1.5",
            "from=2026-01-01T00:00:00Z&staff=1",
            "from=2026-01-01T00:00:00Z&staff=",
            "from=2026-01-01T00:00:00Z&staff=TRUE",
        )
        for (query in unreadable) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/history/gains?$query").status, query)
        }
    }

    /** The test game with Oakhollow (player 3) as staff. */
    private val gameWithStaff = object : GameData by game {
        override fun players(search: String?): List<PlayerSummary> =
            game.players(search).map { if (it.id == 3) it.copy(staff = true) else it }
    }

    @Test
    fun `leaves staff out unless asked, as the leaderboards do`() = adminTest(gameWithStaff, history = history) {
        suspend fun players(query: String) = getJson("/api/history/gains?from=2026-01-01T09:00:00Z$query")["gains"].ints("playerId")

        assertEquals(listOf(1, 2, 9), players(""))
        assertEquals(listOf(1, 2, 9), players("&staff=false"))
        assertEquals(listOf(1, 2, 3, 9), players("&staff=true"))
        assertEquals(emptyList(), players("&player=3"), "a staff player's own gains need staff=true too")
        assertEquals(listOf(3), players("&player=3&staff=true"))
    }

    @Test
    fun `marks staff rows, and counts a player no longer in the game as not staff`() = adminTest(gameWithStaff, history = history) {
        val gains = getJson("/api/history/gains?from=2026-01-01T09:00:00Z&staff=true")["gains"]
        assertEquals(listOf(1, 2, 3, 9), gains.ints("playerId"))
        assertEquals(listOf("false", "false", "true", "false"), gains.strings("staff"))
    }

    @Test
    fun `a skill whose experience went down is left out, not shown as a negative gain`() {
        val dropped = InventedHistory(
            Instant.parse("2026-01-01T08:00:00Z"),
            listOf(
                SkillChange(1, Skill.WOODCUT, start = SkillValue(8000, 16), end = SkillValue(6000, 14)),
                SkillChange(1, Skill.MINING, start = SkillValue(0, 1), end = SkillValue(400, 2)),
            ),
        )
        adminTest(game, history = dropped) {
            val gains = getJson("/api/history/gains?from=2026-01-01T09:00:00Z")["gains"]
            assertEquals(listOf("mining"), gains.strings("skill"))
        }
    }

    @Test
    fun `a player id nobody has gives no gains, not an error`() = adminTest(game, history = history) {
        assertEquals(emptyList(), getJson("/api/history/gains?from=2026-01-01T09:00:00Z&player=404")["gains"].ints("playerId"))
    }

    @Test
    fun `equal gains are listed by player id, then in the game's skill order`() {
        val tied = InventedHistory(
            Instant.parse("2026-01-01T08:00:00Z"),
            listOf(
                SkillChange(2, Skill.MINING, start = SkillValue(0, 1), end = SkillValue(400, 2)),
                SkillChange(2, Skill.WOODCUT, start = SkillValue(0, 1), end = SkillValue(400, 2)),
                SkillChange(1, Skill.MINING, start = SkillValue(0, 1), end = SkillValue(400, 2)),
                SkillChange(3, Skill.ATTACK, start = SkillValue(0, 1), end = SkillValue(800, 3)),
            ),
        )
        adminTest(game, history = tied) {
            val gains = getJson("/api/history/gains?from=2026-01-01T09:00:00Z")["gains"]
            // Player 3 gained most; then the three equal gains: player 1, then player 2's Woodcutting before its Mining.
            assertEquals(listOf(3, 1, 2, 2), gains.ints("playerId"))
            assertEquals(listOf("attack", "mining", "woodcut", "mining"), gains.strings("skill"))
        }
    }

    @Test
    fun `with nothing recorded there's no start and no gains`() = adminTest(game) {
        assertEquals(
            """{"from":"2026-01-01T00:00:00Z","to":"2026-01-02T00:00:00Z","recordedSince":null,"gains":[]}""",
            client.get("/api/history/gains?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z").bodyAsText(),
        )
    }
}
