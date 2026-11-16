package ledger.core.copy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ledger.core.config.CopyConfig
import ledger.core.fixture.Fixtures
import ledger.core.sqlite.openReadOnly
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

class DatabaseCopyLoopTest {
    private val config = CopyConfig(checkEvery = 15.seconds, atMostEvery = 60.seconds)

    private val dir = createTempDirectory("ledger-test")
    private val live = Fixtures.database(dir.resolve("live.db"))
    private val start = Instant.parse("2026-01-01T09:00:00Z")

    private var now = start
    private val clock = object : Clock {
        override fun now() = now
    }
    private val hookCalls = mutableListOf<CopyRecord>()
    private val copier = DatabaseCopier(live, dir.resolve("copy.db"))
    private val loop = DatabaseCopyLoop(live, copier, config, clock, onCopy = hookCalls::add)

    /** Where a test moves the live database while it is "missing". */
    private val aside = dir.resolve("aside.db")

    /** Moves time on, and has the server "save" at the new time if [save]. */
    private fun advance(by: Duration, save: Boolean) {
        now += by
        if (save) { Files.setLastModifiedTime(live, FileTime.from(now.toJavaInstant())) }
    }

    /** How many players the copy holds, read as the pages read it. */
    private fun copiedPlayers(): Int = openReadOnly(copier.copy).use { connection ->
        connection.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM players").use { rows -> rows.next(); rows.getInt(1) } }
    }

    /** Checks that a failed check left the first copy as it was: on disk, as the newest, and with no hook run. */
    private fun assertFirstCopyKept(first: CopyRecord?) {
        assertEquals(first, loop.lastCopy)
        assertEquals(3, copiedPlayers())
        assertEquals(listOf(first), hookCalls)
    }

    @Test
    fun `runs the hook once after each copy, and not when nothing is copied`() {
        loop.checkOnce()
        advance(by = 15.seconds, save = false)
        loop.checkOnce()
        advance(by = 2.minutes, save = true)
        loop.checkOnce()
        assertEquals(listOf(start, start + 15.seconds + 2.minutes), hookCalls.map { it.copiedAt })
        assertEquals(loop.lastCopy, hookCalls.last())
    }

    @Test
    fun `copies at the first check`() {
        assertTrue(loop.checkOnce())
    }

    @Test
    fun `doesn't copy while nothing changes`() {
        loop.checkOnce()
        advance(10.minutes, save = false)
        assertFalse(loop.checkOnce())
    }

    @Test
    fun `waits a minute between copies, however often the server saves`() {
        loop.checkOnce()
        advance(30.seconds, save = true)
        assertFalse(loop.checkOnce())
        advance(30.seconds, save = false)
        assertTrue(loop.checkOnce())
    }

    @Test
    fun `copies again once the database changes and a minute has passed`() {
        loop.checkOnce()
        advance(60.seconds, save = true)
        assertTrue(loop.checkOnce())
    }

    @Test
    fun `keeps the last copy while the server's database is missing`() {
        loop.checkOnce()
        val first = loop.lastCopy
        Files.move(live, aside)
        advance(2.minutes, save = false)
        assertFailsWith<NoSuchFileException> { loop.checkOnce() }
        assertFirstCopyKept(first)
    }

    @Test
    fun `keeps the last copy when the server's database isn't a database`() {
        loop.checkOnce()
        val first = loop.lastCopy
        Files.writeString(live, "half-written, or not SQLite at all")
        advance(2.minutes, save = true)
        assertFails { loop.checkOnce() }
        assertFirstCopyKept(first)
    }

    @Test
    fun `keeps the last copy when the server's database can't be read`() {
        loop.checkOnce()
        val first = loop.lastCopy
        advance(2.minutes, save = true)
        val permissions = Files.getPosixFilePermissions(live)
        Files.setPosixFilePermissions(live, emptySet())
        try {
            assertFails { loop.checkOnce() }
        } finally {
            Files.setPosixFilePermissions(live, permissions)
        }
        assertFirstCopyKept(first)
    }

    @Test
    fun `copies again once the server's database comes back`() {
        loop.checkOnce()
        Files.move(live, aside)
        advance(2.minutes, save = false)
        assertFails { loop.checkOnce() }

        Files.move(aside, live)
        advance(15.seconds, save = true)
        assertTrue(loop.checkOnce())
        assertEquals(now, loop.lastCopy?.copiedAt)
        assertEquals(2, hookCalls.size)
    }

    @Test
    fun `the running loop carries on while the database is missing, and copies once it's back`() = runBlocking {
        Files.move(live, aside)
        val quick = DatabaseCopyLoop(live, copier, CopyConfig(checkEvery = 10.milliseconds, atMostEvery = Duration.ZERO))
        val running = launch(Dispatchers.IO) { quick.run() }
        try {
            delay(100.milliseconds)
            assertTrue(running.isActive)
            assertNull(quick.lastCopy)

            Files.move(aside, live)
            withTimeout(5.seconds) { while (quick.lastCopy == null) { delay(10.milliseconds) } }
            assertEquals(3, copiedPlayers())
        } finally {
            running.cancelAndJoin()
        }
    }
}
