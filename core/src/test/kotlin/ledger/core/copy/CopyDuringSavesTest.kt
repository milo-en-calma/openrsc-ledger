package ledger.core.copy

import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The copier against a database the game server is saving to while it copies.
 *
 * Each save here is written as the server writes one: a single transaction, opened with
 * `BEGIN TRANSACTION` and closed with `END TRANSACTION` on a connection with the driver's
 * default three-second wait for a lock, that deletes a player's banked items (the `bank` rows
 * and their `itemstatuses` rows), writes them again under new item ids, then updates the
 * player's row and experience. Save number `n` writes `n` into every one of those places, so a
 * copy holding part of one save and part of another shows up as values that differ.
 */
class CopyDuringSavesTest {
    private val dir: Path = createTempDirectory("ledger-test")
    private val live = Fixtures.database(dir.resolve("live.db"))
    private val copier = DatabaseCopier(live, dir.resolve("data/server-copy.db"))

    /** The invented players the saves go to, after the fixture's own three. */
    private val players = (FIRST_PLAYER until FIRST_PLAYER + PLAYERS).toList()
    private var nextItemId = 1_000_000L

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun deleteTempFolder() = dir.deleteRecursively()

    /** A connection opened as the server opens its own. */
    private fun open(file: Path): Connection = DriverManager.getConnection("jdbc:sqlite:$file")

    private fun Connection.run(sql: String) { createStatement().use { it.executeUpdate(sql) } }

    private fun Connection.longs(sql: String): List<Long> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) { add(rows.getLong(1)) } } }
    }

    /** Adds the invented players, then saves each once, so save `n` belongs to player `players[(n - 1) % PLAYERS]`. */
    private fun addPlayers(server: Connection) {
        server.run("BEGIN TRANSACTION")
        for (id in players) {
            server.run("INSERT INTO players (id, username, pass) VALUES ($id, 'Hollyfen $id', 'x')")
            server.run("INSERT INTO curstats (playerID) VALUES ($id)")
            server.run("INSERT INTO maxstats (playerID) VALUES ($id)")
            server.run("INSERT INTO experience (playerID) VALUES ($id)")
        }
        server.run("END TRANSACTION")
        for (n in 1..PLAYERS) { save(server, n) }
    }

    /** Save number [n], as one transaction in the order the server's player save writes. */
    private fun save(server: Connection, n: Int) {
        val player = players[(n - 1) % PLAYERS]
        server.run("BEGIN TRANSACTION")
        val oldItems = server.longs("SELECT itemID FROM bank WHERE playerID = $player")
        server.run("DELETE FROM bank WHERE playerID = $player")
        if (oldItems.isNotEmpty()) { server.run("DELETE FROM itemstatuses WHERE itemID IN (${oldItems.joinToString()})") }
        server.prepareStatement("INSERT INTO itemstatuses (itemID, catalogID, amount) VALUES (?, ?, ?)").use { status ->
            server.prepareStatement("INSERT INTO bank (playerID, itemID, slot) VALUES (?, ?, ?)").use { bank ->
                for (slot in 0 until ITEMS_EACH) {
                    val itemId = nextItemId++
                    status.setLong(1, itemId); status.setInt(2, 10 + slot); status.setInt(3, n); status.addBatch()
                    bank.setInt(1, player); bank.setLong(2, itemId); bank.setInt(3, slot); bank.addBatch()
                }
                status.executeBatch()
                bank.executeBatch()
            }
        }
        server.run("UPDATE players SET kills = $n WHERE id = $player")
        server.run("UPDATE experience SET woodcut = $n WHERE playerID = $player")
        server.run("END TRANSACTION")
    }

    /**
     * Checks [file] holds whole saves only, every one up to some save `N` and none after it,
     * and returns `N`. Each player's row, experience and every banked item carry the number of
     * that player's last save; the players' numbers are then exactly `N - PLAYERS + 1` to `N`.
     */
    private fun newestWholeSave(file: Path): Int = open(file).use { copy ->
        val saves = players.map { player ->
            val kills = copy.longs("SELECT kills FROM players WHERE id = $player").single()
            val woodcut = copy.longs("SELECT woodcut FROM experience WHERE playerID = $player").single()
            val amounts = copy.longs(
                "SELECT s.amount FROM bank b JOIN itemstatuses s ON s.itemID = b.itemID WHERE b.playerID = $player",
            )
            val bankRows = copy.longs("SELECT count(*) FROM bank WHERE playerID = $player").single()
            assertEquals(kills, woodcut, "player $player's row and experience come from different saves")
            assertEquals(ITEMS_EACH.toLong(), bankRows, "player $player's bank is half written")
            assertEquals(List(ITEMS_EACH) { kills }, amounts, "player $player's items come from different saves")
            assertEquals(player, players[((kills - 1) % PLAYERS).toInt()], "save $kills belongs to another player")
            kills.toInt()
        }
        val orphans = copy.longs(
            "SELECT count(*) FROM itemstatuses WHERE itemID NOT IN " +
                "(SELECT itemID FROM bank UNION ALL SELECT itemID FROM invitems UNION ALL SELECT itemID FROM equipped)",
        ).single()
        assertEquals(0, orphans, "items deleted by one save are still in the copy")
        val newest = saves.max()
        assertEquals((newest - PLAYERS + 1..newest).toList(), saves.sorted(), "a later save is in the copy but an earlier one isn't")
        newest
    }

    @Test
    fun `every copy taken while the server saves holds whole saves, in order`() {
        val server = open(live)
        addPlayers(server)
        val stop = CountDownLatch(1)
        val saverFailure = AtomicReference<Throwable>()
        var lastSave = PLAYERS
        val saver = thread {
            try {
                while (!stop.await(SAVE_GAP_MILLIS, TimeUnit.MILLISECONDS)) { save(server, ++lastSave) }
            } catch (e: Throwable) {
                saverFailure.set(e)
            }
        }

        val seen = mutableListOf<Int>()
        try {
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COPYING_MILLIS)
            while (System.nanoTime() < until) {
                copier.copy()
                seen += newestWholeSave(copier.copy)
            }
        } finally {
            stop.countDown()
            saver.join()
            server.close()
        }
        assertNull(saverFailure.get(), "a save failed while copies were taken")
        assertEquals(seen.sorted(), seen, "a copy went back to older saves than the copy before it")
        // Printed before its check, so every run's JUnit report shows how close it came to the 10 it needs.
        println("Took ${seen.size} copies in $COPYING_MILLIS ms, seeing ${seen.distinct().size} different saves (needs at least 10)")
        assertTrue(seen.distinct().size >= 10, "too few copies landed between saves to tell anything: $seen")
        copier.copy()
        assertEquals(lastSave, newestWholeSave(copier.copy))
    }

    @Test
    fun `a save that holds its lock too long makes the copy give up, keeping the last good copy`() {
        open(live).use { addPlayers(it) }
        copier.copy()
        val before = Files.readAllBytes(copier.copy)

        val server = open(live)
        server.run("BEGIN EXCLUSIVE")
        server.run("UPDATE players SET kills = -1")
        val gaveUp = CountDownLatch(1)
        val release = thread {
            gaveUp.await(LOCK_HELD_AT_MOST_SECONDS, TimeUnit.SECONDS)
            server.run("ROLLBACK")
            server.close()
        }
        val started = System.nanoTime()
        val failure = try {
            assertFailsWith<IllegalStateException> { copier.copy() }
        } finally {
            gaveUp.countDown()
            release.join()
        }
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        // About six seconds: the driver's three-second wait, then 30 tries 100 ms apart.
        assertTrue(waitedMillis in 5_000..20_000, "gave up after $waitedMillis ms")
        assertContains(failure.message.orEmpty(), "failed with SQLite result code 5")
        assertContentEquals(before, Files.readAllBytes(copier.copy))
        assertEquals(listOf(copier.copy), copier.copy.parent.listDirectoryEntries())
        copier.copy()
        assertEquals(PLAYERS, newestWholeSave(copier.copy))
    }

    private companion object {
        const val FIRST_PLAYER = 101
        /** 300 players with 60 banked items each make a database of about a megabyte, many copy steps long. */
        const val PLAYERS = 300
        const val ITEMS_EACH = 60
        /**
         * A save every millisecond or so, hundreds a second, where the server saves each player
         * once every 30 seconds. With no gap at all, a copy keeps restarting as saves land and
         * gives up after its retries, keeping the last good copy, as when a save holds its lock.
         */
        const val SAVE_GAP_MILLIS = 1L
        /** Long enough for about 75 copies between saves, well over the 10 the test needs (about 150 in 3 seconds). */
        const val COPYING_MILLIS = 1_500L
        const val LOCK_HELD_AT_MOST_SECONDS = 180L
    }
}
