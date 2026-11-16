package ledger.admin

import ledger.core.config.CopyConfig
import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The ledger stopped part way through a copy, as by a power cut or `kill -9`: no exception,
 * no `finally`, the process simply gone. A separate Java process runs the ledger's own wiring
 * ([Ledger]: copy the game database, then record its skill changes) and is killed at a
 * moment spread evenly across that work, round after round. After each kill the ledger starts
 * again as it would, and its history must match, row for row, that of a ledger that was never
 * stopped, with no temporary file left behind.
 */
class StoppedPartWayTest {
    private val dir: Path = createTempDirectory("ledger-test")
    private val live: Path = Fixtures.database(Files.createDirectories(dir.resolve("server")).resolve("preservation.db"))
    private val killedData = dir.resolve("killed")
    private val neverStopped = Ledger(live, dir.resolve("never-stopped"), copyConfig, equipmentTab = false)

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun deleteTempFolder() = dir.deleteRecursively()

    private fun open(file: Path): Connection = DriverManager.getConnection("jdbc:sqlite:$file")

    private fun Connection.run(sql: String) { createStatement().use { it.executeUpdate(sql) } }

    private fun Connection.strings(sql: String): List<String> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            buildList { while (rows.next()) { add((1..rows.metaData.columnCount).joinToString("|") { rows.getString(it) }) } }
        }
    }

    private fun history(data: Path): List<String> =
        if (!data.resolve("history.db").exists()) emptyList()
        else open(data.resolve("history.db")).use { it.strings("SELECT player_id, skill, experience, level, recorded_at FROM skill_history ORDER BY 1, 2, 5") }

    /** Invented players, enough that a copy and its recording take a while to kill part way through. */
    private fun addPlayers() = open(live).use { server ->
        server.run("BEGIN TRANSACTION")
        for (id in FIRST_PLAYER until FIRST_PLAYER + PLAYERS) {
            server.run("INSERT INTO players (id, username, pass) VALUES ($id, 'Hollyfen $id', 'x')")
            server.run("INSERT INTO curstats (playerID) VALUES ($id)")
            server.run("INSERT INTO maxstats (playerID) VALUES ($id)")
            server.run("INSERT INTO experience (playerID) VALUES ($id)")
        }
        server.run("END TRANSACTION")
    }

    /** Save [round]: every player's every skill gains experience, saved at a time a second after the last. */
    private fun save(round: Int) {
        open(live).use { server ->
            val skills = server.strings("SELECT name FROM pragma_table_info('experience') WHERE name <> 'playerID'")
            server.run("UPDATE experience SET ${skills.joinToString { "$it = $it + $round" }}")
        }
        Files.setLastModifiedTime(live, FileTime.fromMillis(SAVED_FROM + round * 1_000L))
    }

    /** Starts the ledger's copy-and-record in its own Java process, warmed up and waiting on "go". */
    private fun startKillable(): Process {
        val java = ProcessHandle.current().info().command().orElseThrow()
        val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), KillableLedger::class.java.name, live.toString(), killedData.toString(), Files.createTempDirectory(dir, "warm-up").toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // Its log lines come first, on the same output.
        val output = process.inputReader()
        while (true) {
            val line = checkNotNull(output.readLine()) { "the killable ledger stopped before it was ready" }
            if (line == "ready") { return process }
        }
    }

    /** What was on disk when the process was killed. */
    private fun killedDuring(round: Int): String {
        val copy = killedData.resolve("server-copy.db")
        val stamp = SAVED_FROM + round * 1_000L
        return when {
            killedData.resolve("server-copy.db.tmp").exists() -> "copying"
            killedData.resolve("history.db-journal").exists() -> "recording"
            history(killedData).any { it.endsWith("|$stamp") } -> "recorded"
            copy.exists() && open(copy).use { it.strings("SELECT woodcut FROM experience WHERE playerID = $FIRST_PLAYER") } == listOf("${round * (round + 1) / 2}") -> "copied"
            else -> "before copying"
        }
    }

    @Test
    fun `a ledger killed at any point of a copy starts again with the same history as one never stopped`() {
        addPlayers()
        // One run left alone, to time it, then its files removed so the rounds start from nothing.
        val timeOne = startKillable().let { process ->
            val started = System.nanoTime()
            process.outputWriter().apply { write("go\n"); flush() }
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "the killable ledger failed on its own" }
            TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started)
        }
        Files.delete(killedData.resolve("history.db"))
        Files.delete(killedData.resolve("server-copy.db"))

        val seen = mutableListOf<String>()
        for (round in 1..ROUNDS) {
            save(round)
            val process = startKillable()
            process.outputWriter().apply { write("go\n"); flush() }
            TimeUnit.MICROSECONDS.sleep(killAfter(round, timeOne))
            process.destroyForcibly().waitFor()
            seen += killedDuring(round)

            Ledger(live, killedData, copyConfig, equipmentTab = false).copyLoop.checkOnce()
            neverStopped.copyLoop.checkOnce()

            assertEquals(listOf(killedData.resolve("history.db"), killedData.resolve("server-copy.db")), killedData.listDirectoryEntries().sorted(), "after round $round")
            assertEquals(history(dir.resolve("never-stopped")), history(killedData), "after round $round")
            assertEquals(listOf("ok"), open(killedData.resolve("history.db")).use { it.strings("PRAGMA quick_check") })
        }
        // Mostly all five of before copying, copying, copied, recording and recorded; three at the least on a busy machine.
        assertTrue(seen.distinct().size >= 3, "the kills landed in too few places to tell anything: $seen")
    }

    /**
     * How long after "go" round [round] kills the process, given that one uninterrupted run
     * took [timeOne] microseconds: the first [EARLY_ROUNDS] every 250 µs from 0, inside the
     * copy, which takes a millisecond or two; the rest spread evenly to half again past a
     * whole run, inside the recording and after it.
     */
    private fun killAfter(round: Int, timeOne: Long): Long =
        if (round <= EARLY_ROUNDS) (round - 1) * 250L
        else timeOne * 3 / 2 * (round - EARLY_ROUNDS) / (ROUNDS - EARLY_ROUNDS)

    companion object {
        const val FIRST_PLAYER = 101
        const val PLAYERS = 1_000
        const val ROUNDS = 16
        const val EARLY_ROUNDS = 8
        const val SAVED_FROM = 1_760_000_000_000L
        val copyConfig = CopyConfig(checkEvery = 15.seconds, atMostEvery = Duration.ZERO)
    }
}

/** The ledger's copy-and-record alone, for [StoppedPartWayTest] to kill: `<live database> <data folder> <empty folder>`. */
object KillableLedger {
    @JvmStatic
    fun main(args: Array<String>) {
        val (live, data, warmUp) = args.map { Path.of(it) }
        // Load every class the work needs first, on a small world in the empty folder, so a kill lands in the work itself.
        val small = Fixtures.database(warmUp.resolve("small.db"))
        Ledger(small, warmUp.resolve("data"), StoppedPartWayTest.copyConfig, equipmentTab = false).copyLoop.checkOnce()
        val ledger = Ledger(live, data, StoppedPartWayTest.copyConfig, equipmentTab = false)
        println("ready")
        System.out.flush()
        check(readlnOrNull() == "go")
        ledger.copyLoop.checkOnce()
    }
}
