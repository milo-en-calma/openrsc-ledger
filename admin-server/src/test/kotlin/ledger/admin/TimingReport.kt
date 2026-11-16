package ledger.admin

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import ledger.core.copy.DatabaseCopier
import ledger.core.fixture.Fixtures
import ledger.core.game.PlayerSkills
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import ledger.core.game.SqliteGameData
import ledger.core.history.HistoryStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.time.measureTimedValue

/**
 * How long the ledger's work takes over a large invented world: a game database of
 * [PLAYERS] made-up players with full stats, inventories and banks, and history.db at each
 * size in `ledger.timingRows`. Reports only: it prints the times, SQLite's query plans and
 * history.db's size per row, and fails on nothing but an error. Run by the `timingReport`
 * task, never by `test` or `check`; everything it writes is in a temporary folder deleted at
 * the end.
 */
class TimingReport {
    private val rowCounts = System.getProperty("ledger.timingRows", "100000,1000000").split(',').map { it.trim().toInt() }

    @Test
    fun `time the ledger over a large invented world`() {
        val dir = Files.createTempDirectory("ledger-timing")
        try {
            report(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun report(dir: Path) {
        val live = time("Build the game database ($PLAYERS players, 30 carried and 150 banked items each)", runs = 1) { inventedGame(dir.resolve("live.db")) }
        say("  Game database: ${Files.size(live) / 1_000_000} MB")
        val copier = DatabaseCopier(live, Files.createDirectories(dir.resolve("data")).resolve("server-copy.db"))
        time("Copy the game database, with SQLite's check of the copy") { copier.copy() }
        val game = SqliteGameData(copier.copy)
        DriverManager.getConnection("jdbc:sqlite:${copier.copy}").use { connection -> GAME_QUERIES.forEach { plan(connection, it) } }

        for (rows in rowCounts) {
            say("\n== history.db with $rows rows over a year ==")
            val file = dir.resolve("history-$rows.db")
            time("Fill history.db", runs = 1) { inventedHistory(file, rows) }
            say("  history.db: ${Files.size(file) / 1_000_000} MB, ${Files.size(file) / rows} bytes per row")
            val history = HistoryStore(file)
            DriverManager.getConnection("jdbc:sqlite:$file").use { connection -> HISTORY_QUERIES.forEach { plan(connection, it) } }
            timeRecording(history)
            timeRoutes(game, history)
            Files.delete(file)
        }
    }

    /** The recording step each copy runs, with 50 of the world's skills gone up since the last. */
    private fun timeRecording(history: HistoryStore) {
        val latest = history.latest().entries.groupBy({ it.key.first }, { it.key.second to it.value })
        val risen = latest.keys.take(50).toSet()
        var at = HISTORY_END
        time("Record a copy with 50 skills changed among ${latest.size} players") {
            at = Instant.fromEpochMilliseconds(at.toEpochMilliseconds() + 30_000)
            val gain = (at.toEpochMilliseconds() / 30_000 % 1000).toInt() + 1
            history.record(at, latest.map { (player, skills) ->
                PlayerSkills(player, skills.associate { (skill, value) -> skill to if (player in risen) value.copy(experience = value.experience + gain) else value })
            })
        }
    }

    /** Every API route, served by Netty on a free port as the ledger serves it. */
    private fun timeRoutes(game: SqliteGameData, history: HistoryStore) {
        val server = embeddedServer(Netty, host = HOST, port = 0) {
            adminModule(lastCopy = { COPIED }, gameData = game, history = history)
        }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val client = HttpClient.newHttpClient()
            for (path in ROUTES) {
                val request = HttpRequest.newBuilder(URI("http://$HOST:$port$path")).build()
                val body = time("GET $path") { client.send(request, HttpResponse.BodyHandlers.ofString()).body() }
                say("      answer: ${body.length / 1000} KB")
            }
        } finally {
            server.stop(100, 1_000)
        }
    }

    private fun say(line: String) = println(line)

    /** Runs [block] [runs] times and reports the best time, then each. */
    private fun <T> time(label: String, runs: Int = 3, block: () -> T): T {
        val results = (1..runs).map { measureTimedValue(block) }
        val millis = results.map { it.duration.inWholeMilliseconds }
        say("  %-90s best %6d ms  (%s)".format(label, millis.min(), millis.joinToString(", ")))
        return results.last().value
    }

    /** Prints how SQLite runs [sql]: SCAN reads a whole table or index, SEARCH jumps to the rows it needs. */
    private fun plan(connection: Connection, sql: String) = connection.createStatement().use { statement ->
        statement.executeQuery("EXPLAIN QUERY PLAN $sql").use { rows ->
            val steps = generateSequence { if (rows.next()) rows.getString("detail") else null }.toList()
            say("  Plan: $sql\n      ${steps.joinToString(" | ")}")
        }
    }

    /** The fixture database, plus players 4 to [PLAYERS] with random stats, 30 carried items and 150 banked. */
    private fun inventedGame(file: Path): Path {
        Fixtures.database(file)
        val random = Random(7)
        val columns = Skill.entries.map { it.column }
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "INSERT INTO players (id, username, pass, group_id, combat, skill_total, login_date, online) VALUES (?, ?, 'x', ?, ?, ?, ?, ?)",
            ).use { insert ->
                for (id in 4..PLAYERS) {
                    insert.setInt(1, id)
                    insert.setString(2, "Player$id")
                    insert.setInt(3, if (id % 500 == 0) 1 else 10)
                    insert.setInt(4, random.nextInt(3, 124))
                    insert.setInt(5, random.nextInt(27, 1500))
                    insert.setLong(6, 1_760_000_000L + id)
                    insert.setInt(7, if (id % 50 == 0) 1 else 0)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            for (table in listOf("curstats", "maxstats", "experience")) {
                connection.prepareStatement("INSERT INTO $table (playerID, ${columns.joinToString()}) VALUES (?${", ?".repeat(columns.size)})").use { insert ->
                    for (id in 4..PLAYERS) {
                        insert.setInt(1, id)
                        columns.indices.forEach { insert.setInt(it + 2, if (table == "experience") random.nextInt(0, 5_000_000) else random.nextInt(1, 100)) }
                        insert.addBatch()
                    }
                    insert.executeBatch()
                }
            }
            var itemId = 1000
            val status = connection.prepareStatement("INSERT INTO itemstatuses (itemID, catalogID, amount, noted) VALUES (?, ?, ?, ?)")
            for ((table, slots) in listOf("invitems" to 30, "bank" to 150)) {
                connection.prepareStatement("INSERT INTO $table (playerID, itemID, slot) VALUES (?, ?, ?)").use { slot ->
                    for (id in 4..PLAYERS) {
                        for (number in 0 until slots) {
                            itemId++
                            status.setInt(1, itemId)
                            status.setInt(2, random.nextInt(0, 1300))
                            status.setInt(3, random.nextInt(1, if (table == "bank") 10_000 else 100))
                            status.setInt(4, if (table == "bank") random.nextInt(0, 2) else 0)
                            status.addBatch()
                            slot.setInt(1, id)
                            slot.setInt(2, itemId)
                            slot.setInt(3, number)
                            slot.addBatch()
                        }
                    }
                    slot.executeBatch()
                }
            }
            status.executeBatch()
            status.close()
            connection.commit()
        }
        return file
    }

    /** [rows] history rows evenly over the year before [HISTORY_END], each one skill of a random player going up. */
    private fun inventedHistory(file: Path, rows: Int) {
        HistoryStore(file) // the table and index exactly as the ledger makes them
        val random = Random(11)
        val start = HISTORY_END.toEpochMilliseconds() - YEAR_MILLIS
        val skills = Skill.entries
        val experience = HashMap<Pair<Int, Skill>, Int>()
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("INSERT INTO skill_history (player_id, skill, experience, level, recorded_at) VALUES (?, ?, ?, ?, ?)").use { insert ->
                for (row in 0 until rows) {
                    val key = random.nextInt(1, PLAYERS + 1) to skills[random.nextInt(skills.size)]
                    val value = experience.getOrDefault(key, 0) + random.nextInt(1, 400)
                    experience[key] = value
                    insert.setInt(1, key.first)
                    insert.setString(2, key.second.column)
                    insert.setInt(3, value)
                    insert.setInt(4, 1 + value / 50_000)
                    insert.setLong(5, start + row * (YEAR_MILLIS / rows))
                    insert.addBatch()
                    if (row % 100_000 == 99_999) { insert.executeBatch() }
                }
                insert.executeBatch()
            }
            connection.commit()
        }
    }

    private companion object {
        const val PLAYERS = 3000
        const val YEAR_MILLIS = 365L * 24 * 3600 * 1000
        val HISTORY_END = Instant.parse("2026-11-01T00:00:00Z")

        val ROUTES = listOf(
            "/api/health", "/api/players", "/api/players?search=player12", "/api/players/1234",
            "/api/leaderboards/overall", "/api/leaderboards/woodcut", "/api/economy", "/api/items/10/holders",
            "/api/history/gains?from=2026-10-31T00:00:00Z&to=2026-11-01T00:00:00Z",
            "/api/history/gains?from=2026-10-25T00:00:00Z&to=2026-11-01T00:00:00Z",
            "/api/history/gains?from=2025-11-01T00:00:00Z&to=2026-11-01T00:00:00Z",
            "/api/history/gains?from=2025-11-01T00:00:00Z&to=2026-11-01T00:00:00Z&player=1234",
        )

        /** The game database's queries, as the ledger's reads make them, for one player where they read one. */
        val GAME_QUERIES = listOf(
            "SELECT * FROM players WHERE lower(username) LIKE '%pl%' ESCAPE '\\' ORDER BY lower(username)",
            "SELECT * FROM maxstats LEFT JOIN experience ON maxstats.playerID = experience.playerID ORDER BY maxstats.playerID",
            "SELECT invitems.playerID, itemstatuses.catalogID, itemstatuses.amount FROM invitems JOIN itemstatuses ON invitems.itemID = itemstatuses.itemID",
            "SELECT * FROM players LEFT JOIN experience ON players.id = experience.playerID JOIN maxstats ON players.id = maxstats.playerID " +
                "JOIN curstats ON players.id = curstats.playerID WHERE players.id = 5",
            "SELECT * FROM bank JOIN itemstatuses ON bank.itemID = itemstatuses.itemID WHERE bank.playerID = 5 ORDER BY bank.slot",
            "SELECT * FROM quests WHERE playerID = 5",
        )

        /**
         * history.db's simple queries, as HistoryStore makes them: each player's and skill's
         * newest value, read once when the store opens, and the newest time, read at each
         * recording. Core's HistoryReadsTest checks the plan of the gains read.
         */
        val HISTORY_QUERIES = listOf(
            "SELECT player_id, skill, experience, level, MAX(recorded_at) FROM skill_history GROUP BY player_id, skill",
            "SELECT MAX(recorded_at) FROM skill_history",
        )
    }
}
