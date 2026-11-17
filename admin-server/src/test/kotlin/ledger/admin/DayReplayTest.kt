package ledger.admin

import io.ktor.client.request.get
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ledger.core.config.CopyConfig
import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * One invented day replayed through the ledger, from 22:00 the evening before to 02:00 the
 * morning after, New York time, on a fake clock. Six invented players save every 2 to 10
 * minutes, a third of their saves changing nothing; a seventh joins at 09:00, one leaves at
 * 15:00, coins move in and out of a bank, and the ledger is restarted at 12:00. The copy loop
 * is stepped every 15 seconds, as configured, with at most one copy a minute.
 *
 * [SaveToPageTest] follows single saves; this looks for what only shows over many: after every
 * copy, the pages agree with each other and with the saves ([checkCopy]); history.db gets a
 * row only for a value that changed; and at the end, the day's gains add up however the day
 * is split, and equal each player's experience at the end of the day less that at midnight
 * ([checkDay]). The server knows no time zones: a page asks for "today" from the viewer's
 * own midnight, as these do.
 *
 * At a few set moments ([drawnAfterMidnight]) it also saves what the "Experience gained today"
 * board would be drawn from, the server's real answers fetched as the page fetches them, with the
 * board the pages imply ([todaysBoard]). Once the day has passed every check, they're written to
 * the folder in the `ledger.dayReplayPages` property, for the web's `dayReplay.test.js` to draw.
 *
 * Each day takes several seconds, so it runs in its own task, `:admin-server:dayReplay`, not
 * in `test` or `check`. `:web:dayReplayPages` runs it, then draws the boards.
 */
class DayReplayTest {
    private val zone = ZoneId.of("America/New_York")
    private val dir = createTempDirectory("ledger-day-replay")
    private val live: Path = Fixtures.database(Files.createDirectories(dir.resolve("server")).resolve("preservation.db"))
    private val data: Path = dir.resolve("data")
    private val saves = ServerSaves(live)

    @AfterTest
    fun closeSaves() = saves.close()

    private var now = Instant.DISTANT_PAST
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    private fun ledger() = Ledger(live, data, CopyConfig(checkEvery = 15.seconds, atMostEvery = 1.minutes), equipmentTab = false, clock = clock)

    @Test
    fun `an ordinary day`() = replay(LocalDate.of(2026, 10, 14), hours = 24)

    @Test
    fun `the day the clocks go back, 25 hours long`() = replay(LocalDate.of(2026, 11, 1), hours = 25)

    /** A local time on [date] in New York. */
    private fun at(date: LocalDate, hour: Int, minute: Int = 0, second: Int = 0): Instant =
        date.atTime(hour, minute, second).atZone(zone).toInstant().toKotlinInstant()

    private fun midnight(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant().toKotlinInstant()

    // The world as the saves leave it, which the pages must show after each copy.

    /** Every skill a save has raised, by player and column, as stored (four times the experience players see). */
    private val stored = mutableMapOf<Pair<Int, String>, Int>()
    private val players = sortedSetOf(1, 2, 3)
    private var coinsInInventories = 1250L
    private var coinsInBanks = 50000L

    private fun level(storedExperience: Int) = minOf(99, 1 + storedExperience / 400)

    private class Event(val at: Instant, val happen: () -> Array<String>)

    private val events = mutableListOf<Event>()

    private fun event(at: Instant, happen: () -> Array<String>) {
        events += Event(at, happen)
    }

    /** What each player trains, in turn. */
    private val trains = mapOf(
        1 to listOf("woodcut", "fishing", "cooking"),
        2 to listOf("mining", "smithing"),
        3 to listOf("fishing", "cooking"),
        4 to listOf("attack", "strength", "defense"),
        5 to listOf("magic", "prayer"),
        6 to listOf("thieving", "agility", "herblaw"),
        7 to listOf("woodcut", "firemaking"),
    )

    private fun join(id: Int, name: String, at: Instant) = arrayOf(
        "INSERT INTO players (id, username, pass, creation_date) VALUES ($id, '$name', 'placeholder-hash-$id', ${at.epochSeconds})",
        "INSERT INTO curstats (playerID) VALUES ($id)",
        "INSERT INTO maxstats (playerID) VALUES ($id)",
        "INSERT INTO experience (playerID) VALUES ($id)",
    ).also { players += id }

    /** A save by [player] at [at]: the [k]th, which raises a skill unless it's every third. */
    private fun progress(player: Int, k: Int): Array<String> {
        if (k % 3 == 2) { return emptyArray() }
        val skill = trains.getValue(player)[k % trains.getValue(player).size]
        val experience = stored.getOrDefault(player to skill, 0) + 4 * (10 + (player * 13 + k * 7) % 90)
        stored[player to skill] = experience
        return raiseSkill(player, skill, level(experience), experience)
    }

    /** [player]'s saves from [from] until [until], every 2 to 10 minutes. */
    private fun savesOf(player: Int, from: Instant, until: Instant) {
        var at = from
        var k = 0
        while (at < until) {
            val n = k
            event(at) { progress(player, n) }
            at += (2 + (player * 7 + k * 3) % 9).minutes
            k++
        }
    }

    /** Coins moved between player 1's inventory and bank: their 1250 coins, item 103. */
    private fun moveCoins(toBank: Boolean): Array<String> = if (toBank) {
        coinsInInventories -= 1250
        coinsInBanks += 1250
        arrayOf("DELETE FROM invitems WHERE itemID = 103", "INSERT INTO bank (playerID, itemID, slot) VALUES (1, 103, 3)")
    } else {
        coinsInInventories += 1250
        coinsInBanks -= 1250
        arrayOf("DELETE FROM bank WHERE itemID = 103", "INSERT INTO invitems (playerID, itemID, slot) VALUES (1, 103, 2)")
    }

    private fun schedule(date: LocalDate) {
        val start = at(date.minusDays(1), 22)
        val end = at(date.plusDays(1), 2)
        val joins = at(date, 9)
        val leaves = at(date, 15)
        for (player in 1..6) {
            savesOf(player, start + player.minutes, if (player == 2) leaves else end)
        }
        savesOf(7, joins + 5.minutes, end)
        event(joins) { join(7, "Pebblecroft", joins) }
        event(leaves) {
            players -= 2
            arrayOf("players", "curstats", "maxstats", "experience").map { "DELETE FROM $it WHERE ${if (it == "players") "id" else "playerID"} = 2" }.toTypedArray()
        }
        // Just before midnight, so it's copied after it, and exactly at it: both the day before's.
        event(midnight(date) - 2.seconds) { progress(1, 0) }
        event(midnight(date)) { progress(3, 0) }
        event(midnight(date.plusDays(1))) { progress(4, 0) }
        var hour = 23
        var toBank = true
        while (hour < 24 + 24) {
            val time = at(date.minusDays(1).plusDays(hour / 24L), hour % 24, 30)
            val move = toBank
            event(time) { moveCoins(move) }
            toBank = !toBank
            hour += 3
        }
        event(at(date, 10, 7)) {
            coinsInInventories += 75
            arrayOf(
                "INSERT INTO itemstatuses (itemID, catalogID, amount) VALUES (401, 10, 75)",
                "INSERT INTO invitems (playerID, itemID, slot) VALUES (3, 401, 1)",
            )
        }
    }

    // What the pages showed after each copy.

    /** Each player's page after a copy of the server's save at [saved]: experience and full level by skill. */
    private class Snapshot(val saved: Instant, val pages: Map<Int, Map<String, Pair<Int, Int>>>)

    private val snapshots = mutableListOf<Snapshot>()

    /** The newest value of each player's skill in history.db, as the pages showed it, to count the rows it should hold. */
    private val newest = mutableMapOf<Pair<Int, String>, Pair<Int, Int>>()
    private var rowsExpected = 0

    private fun historyRows(): Int = DriverManager.getConnection("jdbc:sqlite:${data.resolve("history.db")}").use { connection ->
        connection.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM skill_history").apply { next() }.getInt(1) }
    }

    // What the "Experience gained today" board is drawn from, at set moments.

    /**
     * When, after each midnight, the board's answers are saved; and a minute before the next
     * midnight. On the day the clocks go back, 1:30 and 2:30 after midnight are both 01:30 on the
     * clock, once each time round, so the repeated hour is drawn in both.
     */
    private val drawnAfterMidnight = listOf(5.minutes, 1.hours + 30.minutes, 2.hours + 30.minutes, 9.hours + 30.minutes, 11.hours + 59.minutes, 18.hours + 45.minutes)

    /** A time as a browser's `Date.toISOString()` writes it, milliseconds included. */
    private val browserTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private fun Instant.asBrowserWrites(): String = browserTime.format(toJavaInstant())

    /**
     * The answers the board is drawn from now, fetched as the page fetches them (gains since
     * [date]'s midnight on a New York clock, staff left out, until the server's own now), and the
     * board the pages imply: each player's experience now less that at midnight, or when they
     * joined, ranked as the board ranks, with every skill tied for the most gained. A player no
     * longer on the server is named by number.
     */
    private suspend fun ApplicationTestBuilder.todaysBoard(date: LocalDate): JsonObject {
        val from = midnight(date)
        val names = getJson("/api/players")["players"].jsonArray.associate { it["id"].jsonPrimitive.int to it["name"].jsonPrimitive.content }
        val skillNames = getJson("/api/players/1")["skills"].jsonArray.associate { it["skill"].jsonPrimitive.content to it["name"].jsonPrimitive.content }
        val rows = (1..7).mapNotNull { player ->
            val end = pageAt(player, now) ?: return@mapNotNull null
            val start = pageAt(player, from) ?: snapshots.first { player in it.pages }.pages.getValue(player)
            val gained = end.mapValues { (skill, value) -> value.first - start.getValue(skill).first to value.second - start.getValue(skill).second }
            val experience = gained.values.sumOf { it.first }
            if (experience <= 0) { return@mapNotNull null }
            val most = gained.values.maxOf { it.first }
            val board = buildJsonObject {
                put("name", names[player] ?: "Player $player")
                put("experienceGained", experience)
                put("levelsGained", gained.values.sumOf { it.second })
                putJsonArray("mostIn") { gained.filterValues { it.first == most }.keys.forEach { add(skillNames.getValue(it)) } }
            }
            Triple(experience, (names[player] ?: "Player $player").lowercase(), board)
        }.sortedWith(compareByDescending<Triple<Int, String, JsonObject>> { it.first }.thenBy { it.second }).map { it.third }

        val health = getJson("/api/health")
        val boards = getJson("/api/leaderboards")
        val gains = getJson("/api/history/gains?from=${from.asBrowserWrites()}&staff=false")
        return buildJsonObject {
            put("now", now.asBrowserWrites())
            put("from", from.asBrowserWrites())
            putJsonObject("answers") {
                put("health", health)
                put("leaderboards", boards)
                put("gains", gains)
            }
            put("board", JsonArray(rows))
        }
    }

    private fun replay(date: LocalDate, hours: Int) {
        // A day that fails leaves no answers behind to be drawn as if it had passed.
        val pagesDir = System.getProperty("ledger.dayReplayPages")?.let { Path.of(it) }
        val pagesFile = pagesDir?.resolve("$date.json")
        pagesFile?.deleteIfExists()
        val drawAt = ArrayDeque(drawnAfterMidnight.map { midnight(date) + it } + (midnight(date.plusDays(1)) - 1.minutes))
        val drawn = mutableListOf<JsonObject>()

        schedule(date)
        val pending = ArrayDeque(events.sortedBy { it.at })
        val start = at(date.minusDays(1), 22)
        saves.save(start, *join(4, "Wrenfield", start), *join(5, "Marrowby", start), *join(6, "Thistledown", start))
        // Checks fall 7 seconds past each quarter minute, as on a ledger started at any moment,
        // so the save 2 seconds before midnight is copied after it.
        now = start + 7.seconds

        /** Steps the clock and the copy loop until [until], checking the pages after every copy. */
        fun run(ledger: Ledger, until: Instant, atEnd: suspend ApplicationTestBuilder.() -> Unit = {}) = testApplication {
            application { ledger.serve(this) }
            while (now < until) {
                now += 15.seconds
                while (pending.isNotEmpty() && pending.first().at <= now) {
                    val event = pending.removeFirst()
                    saves.save(event.at, *event.happen())
                }
                if (ledger.copyLoop.checkOnce()) { checkCopy(ledger) }
                if (drawAt.isNotEmpty() && drawAt.first() <= now) {
                    drawAt.removeFirst()
                    drawn += todaysBoard(date)
                }
            }
            atEnd()
        }

        run(ledger(), at(date, 12))
        // Restarted: it reads its history back and carries on.
        run(ledger(), at(date.plusDays(1), 2)) { checkDay(date, hours) }
        assertTrue(pending.isEmpty())
        assertTrue(drawAt.isEmpty())

        if (pagesFile != null) {
            Files.createDirectories(pagesDir)
            val pages = buildJsonObject {
                put("date", date.toString())
                put("zone", zone.id)
                put("moments", JsonArray(drawn))
            }
            pagesFile.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), pages))
        }
    }

    private suspend fun ApplicationTestBuilder.checkCopy(ledger: Ledger) {
        val saved = ledger.copyLoop.lastCopy!!.liveModified
        val list = getJson("/api/players")["players"]
        assertEquals(players.toList(), list.ints("id").sorted(), "the players at $saved")
        val overall = getJson("/api/leaderboards/overall")
        val pages = mutableMapOf<Int, Map<String, Pair<Int, Int>>>()
        for (player in list.jsonArray) {
            val id = player["id"].jsonPrimitive.int
            val skills = getJson("/api/players/$id")["skills"].jsonArray
            val page = skills.associate { it["skill"].jsonPrimitive.content to (it["experience"].jsonPrimitive.int to it["fullLevel"].jsonPrimitive.int) }
            pages[id] = page
            val board = overall["entries"].jsonArray.single { it["playerId"].jsonPrimitive.int == id }
            assertEquals(page.values.sumOf { it.second }, player["totalLevel"].jsonPrimitive.int, "player $id's total level in the list at $saved")
            assertEquals(page.values.sumOf { it.second }, board["level"].jsonPrimitive.int, "player $id's total level on the overall board at $saved")
            assertEquals(page.values.sumOf { it.first.toLong() }, board["experience"].jsonPrimitive.long, "player $id's experience on the overall board at $saved")
            for ((key, experience) in stored.filterKeys { it.first == id }) {
                assertEquals(experience / 4 to level(experience), page.getValue(key.second), "player $id's ${key.second} at $saved")
            }
        }

        val gains = getJson("/api/history/gains?from=${snapshots.firstOrNull()?.saved ?: saved}&to=$now&staff=true")["gains"].jsonArray
        for (gain in gains) {
            val id = gain["playerId"].jsonPrimitive.int
            val page = pages[id]
            if (page == null) {
                assertEquals(JsonNull, gain["name"], "a removed player's gains keep no name")
                continue
            }
            val skill = gain["skill"].jsonPrimitive.content
            assertEquals(page.getValue(skill), gain["experience"].jsonPrimitive.int to gain["level"].jsonPrimitive.int, "player $id's $skill in the gains and on their page at $saved")
        }

        val coins = getJson("/api/economy")["coins"]
        assertEquals(coins, getJson("/api/items/10/holders")["held"], "the economy's coins and the holders page at $saved")
        assertEquals(listOf(coinsInInventories, coinsInBanks), listOf(coins["inInventories"].jsonPrimitive.long, coins["inBanks"].jsonPrimitive.long), "coins at $saved")

        for ((id, page) in pages) {
            for ((skill, value) in page) {
                if (newest.put(id to skill, value) != value) { rowsExpected++ }
            }
        }
        assertEquals(rowsExpected, historyRows(), "history.db's rows at $saved: one for each value that changed")
        snapshots += Snapshot(saved, pages)
    }

    private suspend fun ApplicationTestBuilder.gains(from: Instant, to: Instant): Map<Pair<Int, String>, Pair<Int, Int>> =
        getJson("/api/history/gains?from=$from&to=$to&staff=true")["gains"].jsonArray.associate {
            (it["playerId"].jsonPrimitive.int to it["skill"].jsonPrimitive.content) to (it["experienceGained"].jsonPrimitive.int to it["levelsGained"].jsonPrimitive.int)
        }

    private fun Map<Pair<Int, String>, Pair<Int, Int>>.plus(other: Map<Pair<Int, String>, Pair<Int, Int>>) =
        (keys + other.keys).associateWith {
            val (a, b) = getOrDefault(it, 0 to 0)
            val (c, d) = other.getOrDefault(it, 0 to 0)
            a + c to b + d
        }.filterValues { it != 0 to 0 }

    /** [player]'s page in the newest snapshot of a save at or before [at]. */
    private fun pageAt(player: Int, at: Instant) = snapshots.lastOrNull { it.saved <= at && player in it.pages }?.pages?.get(player)

    private suspend fun ApplicationTestBuilder.checkDay(date: LocalDate, hours: Int) {
        val dayStart = midnight(date)
        val dayEnd = midnight(date.plusDays(1))
        assertEquals(hours.hours, dayEnd - dayStart, "the length of the day")
        val day = gains(dayStart, dayEnd)
        assertTrue(day.isNotEmpty())

        // Split hour by hour, as the clock on the wall reads, and at a few odd moments: the parts add up to the whole.
        val hourly = (0..hours).map { date.atStartOfDay(zone).plusHours(it.toLong()).toInstant().toKotlinInstant() }
        val odd = listOf(dayStart + 1.seconds, dayStart + 7.hours + 13.minutes + 5.seconds, dayEnd - 1.seconds)
        val splits = (hourly + odd).sorted()
        val parts = splits.zipWithNext().fold(emptyMap<Pair<Int, String>, Pair<Int, Int>>()) { sum, (from, to) -> sum.plus(gains(from, to)) }
        assertEquals(day, parts, "the day's gains, and the sum of its parts")

        // Each player's gain for the day is their experience at its end less that at midnight, or when they joined.
        val expected = mutableMapOf<Pair<Int, String>, Pair<Int, Int>>()
        for (player in 1..7) {
            val end = pageAt(player, dayEnd) ?: continue
            val start = pageAt(player, dayStart) ?: snapshots.first { player in it.pages }.pages.getValue(player)
            for ((skill, value) in end) {
                val (startExperience, startLevel) = start.getValue(skill)
                if (value != startExperience to startLevel) { expected[player to skill] = value.first - startExperience to value.second - startLevel }
            }
        }
        assertEquals(expected, day, "the day's gains, and the pages at its end less those at midnight")

        // The boards at the end of the day: the pages' experience, highest first, without the player who left.
        val last = snapshots.last().pages
        assertEquals(404, client.get("/api/players/2").status.value)
        for (skill in SKILL_COLUMNS) {
            val entries = getJson("/api/leaderboards/$skill")["entries"].jsonArray
            assertEquals(last.mapValues { it.value.getValue(skill).first.toLong() }, entries.associate { it["playerId"].jsonPrimitive.int to it["experience"].jsonPrimitive.long }, "the $skill board")
            val ordered = entries.sortedBy { it["rank"].jsonPrimitive.int }.map { it["experience"].jsonPrimitive.long }
            assertEquals(ordered.sortedDescending(), ordered, "the $skill board's ranks")
        }

        println("Day replay $date: ${snapshots.size} copies, ${historyRows()} history rows, history.db ${Files.size(data.resolve("history.db"))} bytes")
    }
}
