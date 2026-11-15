package ledger.core.copy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import ledger.core.config.CopyConfig
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/** One finished copy: when it was made, and the live file's modification time just before. */
data class CopyRecord(val copiedAt: Instant, val liveModified: Instant)

/**
 * Keeps the copy fresh. Every [CopyConfig.checkEvery] it looks at the live database's
 * modification time, which takes no lock, and copies when the file has changed since the
 * last copy and that copy is at least [CopyConfig.atMostEvery] old. The first check always
 * copies. The copy can never be fresher than the server's last save, so copying on change
 * is as fresh as it gets, and nothing is copied while nothing happens.
 */
class DatabaseCopyLoop(
    private val live: Path,
    private val copier: DatabaseCopier,
    private val config: CopyConfig,
    private val clock: Clock = Clock.System,
) {
    /** The newest copy, or null before the first one. */
    @Volatile
    var lastCopy: CopyRecord? = null
        private set

    /** Checks once, copying if it's time. Returns whether it copied. */
    fun checkOnce(): Boolean {
        // Read before copying, so a save made during the copy counts as a change next time.
        val liveModified = Files.getLastModifiedTime(live).toInstant().toKotlinInstant()
        val now = clock.now()
        if (!shouldCopy(lastCopy, liveModified, now, config)) { return false }

        copier.copy()
        lastCopy = CopyRecord(copiedAt = now, liveModified = liveModified)
        return true
    }

    /** Checks forever; a failed copy is logged and tried again at the next check. */
    suspend fun run() {
        while (true) {
            try {
                if (checkOnce()) { log.info("Copied the server's database to {}", copier.copy) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Couldn't copy the server's database; trying again in {}", config.checkEvery, e)
            }
            delay(config.checkEvery)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DatabaseCopyLoop::class.java)

        fun shouldCopy(last: CopyRecord?, liveModified: Instant, now: Instant, config: CopyConfig): Boolean =
            last == null || (liveModified != last.liveModified && now - last.copiedAt >= config.atMostEvery)
    }
}
