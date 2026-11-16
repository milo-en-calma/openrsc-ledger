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

/** The copy worked, but [onCopy][DatabaseCopyLoop] failed on it; it runs again at the next check. */
class AfterCopyFailed(val record: CopyRecord, cause: Throwable) :
    Exception("The step after the copy at ${record.copiedAt} failed", cause)

/**
 * Keeps the copy fresh. Every [CopyConfig.checkEvery] it looks at the live database's
 * modification time, which takes no lock, and copies when the file has changed since the
 * last copy and that copy is at least [CopyConfig.atMostEvery] old. The first check always
 * copies. The copy can never be fresher than the server's last save, so copying on change
 * is as fresh as it gets, and nothing is copied while nothing happens.
 *
 * [onCopy] runs after each copy, with that copy's record. If it fails, the copy still
 * counts, and [onCopy] runs again with the same record at each check until it works, unless
 * a newer copy comes first, whose own [onCopy] then takes its place.
 *
 * If the clock goes back, a warning says so, and a changed file is copied at once rather than
 * waiting for the clock to catch up with the last copy's time.
 */
class DatabaseCopyLoop(
    private val live: Path,
    private val copier: DatabaseCopier,
    private val config: CopyConfig,
    private val clock: Clock = Clock.System,
    private val onCopy: (CopyRecord) -> Unit = {},
) {
    /** The newest copy, or null before the first one. */
    @Volatile
    var lastCopy: CopyRecord? = null
        private set

    /** The copy whose [onCopy] failed and hasn't yet worked, or null. */
    private var pending: CopyRecord? = null

    /** When the previous check ran, to notice the clock going back. */
    private var lastCheckAt: Instant? = null

    /**
     * Checks once, copying if it's time, or else rerunning a failed [onCopy]. Returns whether it
     * copied; throws if the copy failed, or [AfterCopyFailed] if [onCopy] did.
     */
    fun checkOnce(): Boolean {
        // Read before copying, so a save made during the copy counts as a change next time.
        val liveModified = Files.getLastModifiedTime(live).toInstant().toKotlinInstant()
        val now = clock.now()
        lastCheckAt?.let { if (now < it) { log.warn("The clock went back from {} to {}", it, now) } }
        lastCheckAt = now

        if (!shouldCopy(lastCopy, liveModified, now, config)) {
            val waiting = pending ?: return false
            runOnCopy(waiting)
            log.info("Recorded the changes from the copy at {} on a later try", waiting.copiedAt)
            return false
        }

        copier.copy()
        val record = CopyRecord(copiedAt = now, liveModified = liveModified)
        lastCopy = record
        runOnCopy(record)
        return true
    }

    private fun runOnCopy(record: CopyRecord) {
        pending = record
        try {
            onCopy(record)
        } catch (e: Exception) {
            throw AfterCopyFailed(record, e)
        }
        pending = null
    }

    /** Checks forever; a failed copy or [onCopy] is logged and tried again at the next check. */
    suspend fun run() {
        while (true) {
            try {
                if (checkOnce()) { log.info("Copied the server's database to {}", copier.copy) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AfterCopyFailed) {
                log.warn("Copied the server's database, but recording its changes failed; trying again in {}", config.checkEvery, e.cause)
            } catch (e: Exception) {
                log.warn("Couldn't copy the server's database; trying again in {}", config.checkEvery, e)
            }
            delay(config.checkEvery)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DatabaseCopyLoop::class.java)

        /** A check before the last copy's time means the clock went back, and counts as past the gap. */
        fun shouldCopy(last: CopyRecord?, liveModified: Instant, now: Instant, config: CopyConfig): Boolean =
            last == null || (liveModified != last.liveModified && (now < last.copiedAt || now - last.copiedAt >= config.atMostEvery))
    }
}
