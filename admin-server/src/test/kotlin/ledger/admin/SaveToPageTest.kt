package ledger.admin

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import ledger.core.config.CopyConfig
import ledger.core.fixture.Fixtures
import ledger.core.game.Skill
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * One game server save followed all the way to the pages: the save is written to a game
 * database as the server writes it, the ledger's copy loop copies it and records the skill
 * changes, and the API answers from that copy and history. Each piece is tested on its own
 * elsewhere; these look for places where two pieces disagree.
 *
 * Saves are made as the server makes them: everything in one transaction (`PlayerService.savePlayer`
 * saves a player's data and skills together, `atomically`), with `players.skill_total` the sum of
 * the full levels (`Skills.getTotalLevel`), and the file's modification time moving with each save.
 */
class SaveToPageTest {
    private val dir = createTempDirectory("ledger-save-to-page")
    private val live: Path = Fixtures.database(Files.createDirectories(dir.resolve("server")).resolve("preservation.db"))

    /** The game's first save, the ledger's first copy a minute later. */
    private val t0 = Instant.parse("2026-03-01T10:00:00Z")

    private var now = t0
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    private fun ledger(atMostEvery: Duration = 15.seconds) =
        Ledger(live, dir.resolve("data"), CopyConfig(checkEvery = 15.seconds, atMostEvery = atMostEvery), equipmentTab = false, clock = clock)

    private val skillColumns = listOf(
        "attack", "defense", "strength", "hits", "ranged", "prayer", "magic", "cooking", "woodcut", "fletching",
        "fishing", "firemaking", "crafting", "smithing", "mining", "herblaw", "agility", "thieving", "harvesting", "runecraft",
    )

    /**
     * Saves [statements] to the game database in one transaction at [at], as the server does,
     * setting every player's total level from their full levels as the server's save does.
     */
    private fun save(at: Instant, vararg statements: String) {
        DriverManager.getConnection("jdbc:sqlite:$live").use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statements.forEach(statement::executeUpdate)
                statement.executeUpdate(
                    "UPDATE players SET skill_total = (SELECT ${skillColumns.joinToString(" + ")} FROM maxstats WHERE maxstats.playerID = players.id) " +
                        "WHERE id IN (SELECT playerID FROM maxstats)",
                )
            }
            connection.commit()
        }
        Files.setLastModifiedTime(live, FileTime.from(at.toJavaInstant()))
    }

    /** Raises one skill of [player] to [level] with [storedExperience], as a save of their progress would. */
    private fun skill(player: Int, skill: String, level: Int, storedExperience: Int) = arrayOf(
        "UPDATE maxstats SET $skill = $level WHERE playerID = $player",
        "UPDATE curstats SET $skill = $level WHERE playerID = $player",
        "UPDATE experience SET $skill = $storedExperience WHERE playerID = $player",
    )

    private fun pages(ledger: Ledger, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { ledger.serve(this) }
        block()
    }

    private suspend fun ApplicationTestBuilder.status(path: String): Int = client.get(path).status.value

    private suspend fun ApplicationTestBuilder.gains(from: Instant, to: Instant, extra: String = ""): JsonElement =
        getJson("/api/history/gains?from=$from&to=$to$extra")

    private fun JsonElement.entry(playerId: Int): JsonElement? = this["entries"].jsonArray.firstOrNull { it["playerId"].jsonPrimitive.int == playerId }

    private fun JsonElement.skillRow(skill: String): JsonElement = this["skills"].jsonArray.single { it["skill"].jsonPrimitive.content == skill }

    /** The game as first saved, and the ledger's first copy of it a minute later. */
    private fun firstCopy(ledger: Ledger) {
        save(t0)
        now = t0 + 1.minutes
        assertTrue(ledger.copyLoop.checkOnce())
    }

    @Test
    fun `before the first copy, every page says so and the health check has no copy time`() {
        save(t0)
        pages(ledger()) {
            assertEquals(JsonNull, getJson("/api/health")["dataCopiedAt"])
            for (path in listOf("/api/players", "/api/players/1", "/api/leaderboards/overall", "/api/economy", "/api/items/10/holders", "/api/history/gains?from=$t0")) {
                assertEquals(503, status(path), path)
            }
        }
    }

    @Test
    fun `the first copy fills every page, and history starts at the save, with nothing counted as a gain`() {
        val ledger = ledger()
        firstCopy(ledger)
        pages(ledger) {
            assertEquals((t0 + 1.minutes).toString(), getJson("/api/health")["dataCopiedAt"].jsonPrimitive.content)
            assertEquals(listOf("Bramblewick", "Oakhollow", "Tansy Moss"), getJson("/api/players")["players"].strings("name"))
            val report = gains(t0 - 1.hours, t0 + 1.hours)
            assertEquals(t0.toString(), report["recordedSince"].jsonPrimitive.content, "history is stamped with the save's time, not the copy's")
            assertEquals(0, report["gains"].jsonArray.size)
        }
    }

    @Test
    fun `a save's skill gain reaches the player's page, the boards and the gains alike`() {
        val ledger = ledger()
        firstCopy(ledger)
        val t1 = t0 + 10.minutes
        save(t1, *skill(1, "woodcut", level = 10, storedExperience = 4 * 1200))
        now = t1 + 30.seconds
        assertTrue(ledger.copyLoop.checkOnce())

        pages(ledger) {
            val page = getJson("/api/players/1")
            assertEquals(10, page.skillRow("woodcut")["fullLevel"].jsonPrimitive.int)
            assertEquals(1200, page.skillRow("woodcut")["experience"].jsonPrimitive.int)

            val board = getJson("/api/leaderboards/woodcut")
            assertEquals(1, board.entry(1)!!["rank"].jsonPrimitive.int)
            assertEquals(1200L, board.entry(1)!!["experience"].jsonPrimitive.long)
            assertEquals(10, board.entry(1)!!["level"].jsonPrimitive.int)

            val gains = gains(t0, t1)["gains"].jsonArray
            assertEquals(1, gains.size)
            val gain = gains.single()
            assertEquals("Bramblewick", gain["name"].jsonPrimitive.content)
            assertEquals("woodcut", gain["skill"].jsonPrimitive.content)
            assertEquals(1200, gain["experienceGained"].jsonPrimitive.int)
            assertEquals(9, gain["levelsGained"].jsonPrimitive.int)
            assertEquals(page.skillRow("woodcut")["experience"], gain["experience"], "the gains and the page show the same experience")
            assertEquals(board.entry(1)!!["level"].jsonPrimitive.int, gain["level"].jsonPrimitive.int)
        }
    }

    @Test
    fun `a gain counts at the moment the server saved it, not when the ledger copied it`() {
        val ledger = ledger()
        firstCopy(ledger)
        val t1 = t0 + 10.minutes
        save(t1, *skill(1, "fishing", level = 5, storedExperience = 4 * 400))
        now = t1 + 5.minutes
        ledger.copyLoop.checkOnce()

        pages(ledger) {
            assertEquals(0, gains(t0, t1 - 1.milliseconds)["gains"].jsonArray.size, "nothing before the save")
            assertEquals(1, gains(t0, t1)["gains"].jsonArray.size, "a period ending at the save includes it")
            assertEquals(0, gains(t1, t1 + 1.hours)["gains"].jsonArray.size, "a period starting at the save already has it")
        }
    }

    @Test
    fun `every player's total level agrees between the player list, their page and the overall board`() {
        val ledger = ledger()
        firstCopy(ledger)
        save(t0 + 10.minutes, *skill(1, "woodcut", 30, 4 * 13363), *skill(3, "mining", 12, 4 * 1500))
        now = t0 + 11.minutes
        ledger.copyLoop.checkOnce()

        pages(ledger) {
            val overall = getJson("/api/leaderboards/overall")
            for (player in getJson("/api/players")["players"].jsonArray) {
                val id = player["id"].jsonPrimitive.int
                val fromPage = getJson("/api/players/$id")["skills"].jsonArray.sumOf { it["fullLevel"].jsonPrimitive.int }
                assertEquals(fromPage, player["totalLevel"].jsonPrimitive.int, "player $id in the list")
                assertEquals(fromPage, overall.entry(id)!!["level"].jsonPrimitive.int, "player $id on the overall board")
                val pageExperience = getJson("/api/players/$id")["skills"].jsonArray.sumOf { it["experience"].jsonPrimitive.int.toLong() }
                assertEquals(pageExperience, overall.entry(id)!!["experience"].jsonPrimitive.long, "player $id's experience on the overall board")
            }
        }
    }

    @Test
    fun `a save inside the minimum gap shows at the next copy, still stamped with its own time`() {
        val ledger = ledger(atMostEvery = 1.minutes)
        firstCopy(ledger)
        val t1 = now + 10.seconds
        save(t1, *skill(2, "cooking", 3, 4 * 200))
        now = t1 + 10.seconds
        assertFalse(ledger.copyLoop.checkOnce(), "too soon after the last copy")
        pages(ledger) {
            assertEquals(1, getJson("/api/players/2").skillRow("cooking")["fullLevel"].jsonPrimitive.int, "the page still shows the last copy")
        }

        now = t0 + 3.minutes
        assertTrue(ledger.copyLoop.checkOnce())
        pages(ledger) {
            assertEquals(3, getJson("/api/players/2").skillRow("cooking")["fullLevel"].jsonPrimitive.int)
            assertEquals(1, gains(t0, t1)["gains"].jsonArray.size)
        }
    }

    @Test
    fun `two saves between copies count as one gain from the first value to the last`() {
        val ledger = ledger()
        firstCopy(ledger)
        save(t0 + 10.minutes, *skill(1, "fishing", 5, 4 * 400))
        save(t0 + 20.minutes, *skill(1, "fishing", 8, 4 * 900))
        now = t0 + 21.minutes
        ledger.copyLoop.checkOnce()

        pages(ledger) {
            val gain = gains(t0, t0 + 1.hours)["gains"].jsonArray.single()
            assertEquals(900, gain["experienceGained"].jsonPrimitive.int)
            assertEquals(7, gain["levelsGained"].jsonPrimitive.int)
            assertEquals(0, gains(t0, t0 + 15.minutes)["gains"].jsonArray.size, "the ledger never saw the first save on its own")
        }
    }

    @Test
    fun `a new player shows everywhere at once, and what they started with isn't a gain`() {
        val ledger = ledger()
        firstCopy(ledger)
        val t1 = t0 + 10.minutes
        save(
            t1,
            "INSERT INTO players (id, username, pass, creation_date) VALUES (4, 'Wrenfield', 'placeholder-hash-4', 1772360000)",
            "INSERT INTO curstats (playerID) VALUES (4)",
            "INSERT INTO maxstats (playerID) VALUES (4)",
            "INSERT INTO experience (playerID) VALUES (4)",
        )
        now = t1 + 1.minutes
        ledger.copyLoop.checkOnce()
        val t2 = t1 + 10.minutes
        save(t2, *skill(4, "woodcut", 2, 4 * 100))
        now = t2 + 1.minutes
        ledger.copyLoop.checkOnce()

        pages(ledger) {
            assertTrue("Wrenfield" in getJson("/api/players")["players"].strings("name"))
            // A new player's full levels: 1 in each skill but hits, 10, so 29; and woodcut is now 2.
            assertEquals(30, getJson("/api/leaderboards/overall").entry(4)!!["level"].jsonPrimitive.int)
            assertEquals(0, gains(t0, t1)["gains"].jsonArray.size, "a new player's starting hits level and experience aren't a gain")
            val gain = gains(t0, t2)["gains"].jsonArray.single()
            assertEquals(listOf("Wrenfield", "woodcut"), listOf(gain["name"].jsonPrimitive.content, gain["skill"].jsonPrimitive.content))
            assertEquals(100, gain["experienceGained"].jsonPrimitive.int)
        }
    }

    @Test
    fun `a player removed from the game leaves the pages and boards, but their past gains stay, without a name`() {
        val ledger = ledger()
        firstCopy(ledger)
        save(t0 + 10.minutes, *skill(2, "fishing", 5, 4 * 400))
        now = t0 + 11.minutes
        ledger.copyLoop.checkOnce()
        save(
            t0 + 20.minutes,
            "DELETE FROM players WHERE id = 2",
            "DELETE FROM curstats WHERE playerID = 2",
            "DELETE FROM maxstats WHERE playerID = 2",
            "DELETE FROM experience WHERE playerID = 2",
        )
        now = t0 + 21.minutes
        ledger.copyLoop.checkOnce()

        pages(ledger) {
            assertEquals(404, status("/api/players/2"))
            assertEquals(null, getJson("/api/leaderboards/fishing").entry(2))
            val gain = gains(t0, t0 + 1.hours)["gains"].jsonArray.single()
            assertEquals(2, gain["playerId"].jsonPrimitive.int)
            assertEquals(JsonNull, gain["name"])
        }
    }

    @Test
    fun `a restarted ledger keeps its history, and an unchanged server adds nothing to it`() {
        val first = ledger()
        firstCopy(first)
        save(t0 + 10.minutes, *skill(1, "fishing", 5, 4 * 400))
        now = t0 + 11.minutes
        first.copyLoop.checkOnce()
        val before = first.history.latest()

        now = t0 + 2.hours
        val restarted = ledger()
        assertTrue(restarted.copyLoop.checkOnce(), "a restarted ledger copies at once")
        assertEquals(before, restarted.history.latest())
        pages(restarted) {
            assertEquals(t0.toString(), gains(t0 - 1.hours, now)["recordedSince"].jsonPrimitive.content)
            assertEquals(1, gains(t0, now)["gains"].jsonArray.size)
        }
    }

    @Test
    fun `the gains and the boards agree on who is staff`() {
        val ledger = ledger()
        firstCopy(ledger)
        save(t0 + 10.minutes, "UPDATE players SET group_id = 1 WHERE id = 3", *skill(3, "mining", 12, 4 * 1500), *skill(1, "mining", 5, 4 * 400))
        now = t0 + 11.minutes
        ledger.copyLoop.checkOnce()

        pages(ledger) {
            for (staff in listOf("", "&staff=false", "&staff=true")) {
                val onBoard = getJson("/api/leaderboards/mining?x=1$staff")["entries"].jsonArray
                    .filter { it["experience"].jsonPrimitive.long > 0 }.map { it["playerId"].jsonPrimitive.int to it["staff"] }
                val inGains = gains(t0, t0 + 1.hours, staff)["gains"].jsonArray.map { it["playerId"].jsonPrimitive.int to it["staff"] }
                assertEquals(onBoard.sortedBy { it.first }, inGains.sortedBy { it.first }, "asked with \"$staff\"")
            }
        }
    }

    @Test
    fun `an item a save adds shows in the economy and among its holders`() {
        val ledger = ledger()
        firstCopy(ledger)
        save(
            t0 + 10.minutes,
            "INSERT INTO itemstatuses (itemID, catalogID, amount) VALUES (401, 10, 75)",
            "INSERT INTO invitems (playerID, itemID, slot) VALUES (2, 401, 0)",
        )
        now = t0 + 11.minutes
        ledger.copyLoop.checkOnce()

        pages(ledger) {
            val coins = getJson("/api/economy")["coins"]
            assertEquals(1250L + 75, coins["inInventories"].jsonPrimitive.long)
            val holders = getJson("/api/items/10/holders")
            assertEquals(coins, holders["held"], "the economy's coin totals and the holders page agree")
            assertEquals(listOf("Bramblewick", "Tansy Moss"), holders["holders"].strings("name"))
        }
    }

    /** A viewer's local midnight in New York (UTC-5 in March), as the pages send it for "today". */
    private val midnight = Instant.parse("2026-03-02T05:00:00Z")

    /** Gains as the pages ask for them: from a time, with the end left to the ledger's clock. */
    private suspend fun ApplicationTestBuilder.gainsSince(from: Instant): JsonElement = getJson("/api/history/gains?from=$from")

    /** The warnings the ledger logs while [block] runs. */
    private fun warnings(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger("ledger.admin") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    @Test
    fun `a period of gains left open ends at the ledger's own clock`() {
        val ledger = ledger()
        firstCopy(ledger)
        pages(ledger) { assertEquals(now.toString(), gainsSince(t0)["to"].jsonPrimitive.content) }
    }

    @Test
    fun `a save just before midnight, copied after it, counts for its own day, and one at midnight for the day before`() {
        val ledger = ledger()
        firstCopy(ledger)
        save(midnight - 5.seconds, *skill(1, "fishing", 5, 4 * 400))
        now = midnight + 10.seconds
        ledger.copyLoop.checkOnce()
        save(midnight + 20.seconds, *skill(1, "fishing", 6, 4 * 500))
        now = midnight + 40.seconds
        ledger.copyLoop.checkOnce()
        save(midnight + 24.hours, *skill(2, "cooking", 3, 4 * 200))
        now = midnight + 24.hours + 10.seconds
        ledger.copyLoop.checkOnce()

        pages(ledger) {
            assertEquals(listOf(5), gains(midnight - 24.hours, midnight)["gains"].ints("level"), "yesterday's save is yesterday's gain")
            assertEquals(listOf(100), gains(midnight, midnight + 1.hours)["gains"].ints("experienceGained"), "today counts from the save before midnight")
            assertEquals(0, gainsSince(midnight + 24.hours)["gains"].jsonArray.size, "a save stamped exactly at midnight is the day before's")
        }
    }

    @Test
    fun `a database restored with an older file time is caught up by the next save`() {
        val ledger = ledger()
        firstCopy(ledger)
        now = midnight + 10.hours
        save(now - 1.minutes, *skill(1, "fishing", 5, 4 * 400))
        ledger.copyLoop.checkOnce()
        // Restored from last night's backup, keeping its time: older than what's recorded, so not recorded.
        now += 1.minutes
        save(midnight - 3.hours, *skill(1, "fishing", 3, 4 * 200))
        ledger.copyLoop.checkOnce()
        pages(ledger) { assertEquals(listOf(5), gainsSince(midnight)["gains"].ints("level")) }

        now += 1.minutes
        save(now - 10.seconds, *skill(1, "fishing", 4, 4 * 300))
        ledger.copyLoop.checkOnce()
        pages(ledger) {
            assertEquals(listOf(4), gainsSince(midnight)["gains"].ints("level"))
            assertEquals(4, getJson("/api/players/1").skillRow("fishing")["fullLevel"].jsonPrimitive.int, "the page and the gains agree")
        }
    }

    @Test
    fun `a file time ahead of the clock is recorded at the copy's time, with one warning each time it runs ahead`() {
        val ledger = ledger()
        firstCopy(ledger)
        val levels = mutableListOf<Int>()
        val warned = warnings {
            for ((level, ahead) in listOf(5 to 2.hours, 6 to 2.hours, 8 to Duration.ZERO, 9 to 1.hours)) {
                now += 1.minutes
                save(now - 10.seconds + ahead, *skill(1, "fishing", level, 4 * 100 * level))
                assertTrue(ledger.copyLoop.checkOnce())
                pages(ledger) {
                    val gain = gainsSince(t0)["gains"].jsonArray.single()
                    assertEquals(getJson("/api/players/1").skillRow("fishing")["fullLevel"].jsonPrimitive.int, gain["level"].jsonPrimitive.int, "the page and the gains agree")
                    levels += gain["level"].jsonPrimitive.int
                }
            }
        }
        assertEquals(listOf(5, 6, 8, 9), levels, "every save shows in the gains at once")
        assertEquals(2, warned.count { "after this machine's clock" in it }, "warned when it ran ahead, not again until it ran ahead anew: $warned")
        assertEquals(now, ledger.history.values(1, Skill.FISHING).last().first, "the last one stamped at its copy")
    }
}
