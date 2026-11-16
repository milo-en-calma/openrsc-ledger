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
    fun `retries a failed copy at the next check, without waiting for another save`() {
        loop.checkOnce()
        advance(2.minutes, save = true)
        val permissions = Files.getPosixFilePermissions(live)
        Files.setPosixFilePermissions(live, emptySet())
        try {
            assertFails { loop.checkOnce() }
        } finally {
            Files.setPosixFilePermissions(live, permissions)
        }

        advance(15.seconds, save = false)
        assertTrue(loop.checkOnce())
        assertEquals(now, loop.lastCopy?.copiedAt)
    }

    @Test
    fun `retries a failed first copy at once`() {
        Files.move(live, aside)
        assertFails { loop.checkOnce() }
        Files.move(aside, live)
        assertTrue(loop.checkOnce())
    }

    @Test
    fun `copies when the database's time goes back, as after a restore from a backup`() {
        loop.checkOnce()
        val restored = start - 3.minutes
        advance(2.minutes, save = false)
        Files.setLastModifiedTime(live, FileTime.from(restored.toJavaInstant()))
        assertTrue(loop.checkOnce())
        assertEquals(restored, loop.lastCopy?.liveModified)
    }

    @Test
    fun `copies again after a save made while the last copy ran`() {
        val savingLoop = DatabaseCopyLoop(live, copier, config, clock) {
            // The server saves after the loop read the file's time, while the copy runs.
            Files.setLastModifiedTime(live, FileTime.from((now + 1.seconds).toJavaInstant()))
        }
        savingLoop.checkOnce()
        advance(60.seconds, save = false)
        assertTrue(savingLoop.checkOnce())
    }

    @Test
    fun `copies at every change when there's no minimum gap`() {
        val eager = DatabaseCopyLoop(live, copier, CopyConfig(checkEvery = 15.seconds, atMostEvery = Duration.ZERO), clock)
        eager.checkOnce()
        advance(1.seconds, save = true)
        assertTrue(eager.checkOnce())
        assertFalse(eager.checkOnce())
    }

    @Test
    fun `copies after a change when the clock has been set back`() {
        loop.checkOnce()
        now -= 1.minutes * 60
        Files.setLastModifiedTime(live, FileTime.from((start + 1.seconds).toJavaInstant()))
        advance(15.seconds, save = false)
        assertTrue(loop.checkOnce())
    }

    @Test
    fun `a failed after-copy step still counts the copy`() {
        var fail = true
        val failing = DatabaseCopyLoop(live, copier, config, clock) { if (fail) { error("history couldn't be written") } }
        assertFails { failing.checkOnce() }
        assertEquals(start, failing.lastCopy?.copiedAt)
        assertEquals(3, copiedPlayers())

        fail = false
        advance(15.seconds, save = false)
        assertFalse(failing.checkOnce())
    }

    @Test
    fun `runs a failed after-copy step again at the next check, with the same copy, until it works`() {
        val failures = ArrayDeque(listOf(true, true, false))
        val calls = mutableListOf<CopyRecord>()
        val failing = DatabaseCopyLoop(live, copier, config, clock) {
            calls += it
            if (failures.removeFirst()) { error("history couldn't be written") }
        }
        val failed = assertFailsWith<AfterCopyFailed> { failing.checkOnce() }
        assertEquals("history couldn't be written", failed.cause?.message)
        val first = failing.lastCopy

        advance(15.seconds, save = false)
        assertFailsWith<AfterCopyFailed> { failing.checkOnce() }
        advance(15.seconds, save = false)
        assertFalse(failing.checkOnce())
        assertEquals(listOf(first, first, first), calls)

        advance(15.seconds, save = false)
        assertFalse(failing.checkOnce())
        assertEquals(3, calls.size)
    }

    @Test
    fun `drops a failed after-copy step when a newer copy comes first`() {
        var fail = true
        val calls = mutableListOf<CopyRecord>()
        val failing = DatabaseCopyLoop(live, copier, config, clock) {
            calls += it
            if (fail) { error("history couldn't be written") }
        }
        assertFails { failing.checkOnce() }

        fail = false
        advance(2.minutes, save = true)
        assertTrue(failing.checkOnce())
        advance(15.seconds, save = false)
        assertFalse(failing.checkOnce())
        assertEquals(listOf(start, start + 2.minutes), calls.map { it.copiedAt })
    }

    @Test
    fun `a failed copy, once retried, runs its own after-copy step in place of an older failed one`() {
        var fail = true
        val calls = mutableListOf<CopyRecord>()
        val failing = DatabaseCopyLoop(live, copier, config, clock) {
            calls += it
            if (fail) { error("history couldn't be written") }
        }
        assertFails { failing.checkOnce() }

        fail = false
        advance(2.minutes, save = true)
        val permissions = Files.getPosixFilePermissions(live)
        Files.setPosixFilePermissions(live, emptySet())
        try {
            assertFails { failing.checkOnce() }
        } finally {
            Files.setPosixFilePermissions(live, permissions)
        }
        assertEquals(listOf(start), calls.map { it.copiedAt })

        // The copy is retried first, and its own step takes the old one's place.
        advance(15.seconds, save = false)
        assertTrue(failing.checkOnce())
        assertEquals(listOf(start, now), calls.map { it.copiedAt })
    }

    @Test
    fun `a failed copy isn't taken for a failed after-copy step`() {
        Files.move(live, aside)
        val failed = assertFails { loop.checkOnce() }
        assertFalse(failed is AfterCopyFailed)
    }

    @Test
    fun `doesn't copy when the clock has been set back but nothing changed`() {
        loop.checkOnce()
        now -= 1.minutes * 60
        assertFalse(loop.checkOnce())
    }

    @Test
    fun `the running loop carries on when the after-copy step fails`() = runBlocking {
        var calls = 0
        val quick = DatabaseCopyLoop(live, copier, CopyConfig(checkEvery = 10.milliseconds, atMostEvery = Duration.ZERO)) {
            calls++
            error("history couldn't be written")
        }
        val running = launch(Dispatchers.IO) { quick.run() }
        try {
            withTimeout(5.seconds) { while (quick.lastCopy == null) { delay(10.milliseconds) } }
            Files.setLastModifiedTime(live, FileTime.from(Instant.parse("2030-01-01T00:00:00Z").toJavaInstant()))
            withTimeout(5.seconds) { while (calls < 2) { delay(10.milliseconds) } }
            assertTrue(running.isActive)
        } finally {
            running.cancelAndJoin()
        }
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
