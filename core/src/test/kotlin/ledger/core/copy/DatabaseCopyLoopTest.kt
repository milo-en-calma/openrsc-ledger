package ledger.core.copy

import ledger.core.config.CopyConfig
import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
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
    private val loop = DatabaseCopyLoop(live, DatabaseCopier(live, dir.resolve("copy.db")), config, clock)

    /** Moves time on, and has the server "save" at the new time if [save]. */
    private fun advance(by: Duration, save: Boolean) {
        now += by
        if (save) { Files.setLastModifiedTime(live, FileTime.from(now.toJavaInstant())) }
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
}
