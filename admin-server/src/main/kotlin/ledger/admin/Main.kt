package ledger.admin

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ledger.core.config.CopyConfig
import ledger.core.config.LedgerConfig
import ledger.core.copy.CopyRecord
import ledger.core.copy.DatabaseCopier
import ledger.core.copy.DatabaseCopyLoop
import ledger.core.game.SqliteGameData
import ledger.core.history.HistoryStore
import ledger.core.openrsc.OpenRscServer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Instant

/** The admin console listens on the loopback address only: being unreachable from other machines is its security. */
const val HOST = "127.0.0.1"

private const val PRINT_LAUNCH_SETTINGS = "--print-launch-settings"
private const val CHECK = "--check"

const val USAGE = "Usage: ledger-admin [$PRINT_LAUNCH_SETTINGS | $CHECK] [config file]"

private val log = LoggerFactory.getLogger("ledger.admin")

/** What `ledger-admin` was asked to do with the config it reads. */
enum class Mode {
    /** Serve the API and the pages. */
    SERVE,
    /** Print what the launch script needs ([launchSettings]) and exit. */
    PRINT_LAUNCH_SETTINGS,
    /** Read everything once, print what was found ([check]) and exit. */
    CHECK,
}

/** What `ledger-admin` was asked to do: read the config at [configPath], and do [mode]. */
data class Arguments(val mode: Mode, val configPath: Path)

/**
 * Reads `ledger-admin [--print-launch-settings | --check] [config file]`, the flag before or
 * after the file. The config file is the argument, else [ledgerConf] (`$LEDGER_CONF`, unless
 * it's empty, as the launch script reads it), else `ledger.conf` in the working directory. Any
 * other option, both flags, or a second file, is an [IllegalArgumentException], so a mistyped
 * flag is never ignored.
 */
fun parseArguments(args: List<String>, ledgerConf: String? = System.getenv("LEDGER_CONF")): Arguments {
    val flags = setOf(PRINT_LAUNCH_SETTINGS, CHECK)
    val files = args.filter { it !in flags }
    files.firstOrNull { it.startsWith("-") }?.let { throw IllegalArgumentException("Unknown option $it") }
    require(files.size <= 1) { "Give one config file, not two" }
    require(!(PRINT_LAUNCH_SETTINGS in args && CHECK in args)) { "Give $PRINT_LAUNCH_SETTINGS or $CHECK, not both" }
    return Arguments(
        mode = when {
            PRINT_LAUNCH_SETTINGS in args -> Mode.PRINT_LAUNCH_SETTINGS
            CHECK in args -> Mode.CHECK
            else -> Mode.SERVE
        },
        configPath = Path.of(files.singleOrNull() ?: ledgerConf?.ifEmpty { null } ?: "ledger.conf"),
    )
}

/**
 * The ledger's settings at [configPath], and the game server they name. A mistake in either,
 * such as a missing file or text where a number goes, is an [IllegalArgumentException].
 */
fun loadSettings(configPath: Path): Pair<LedgerConfig, OpenRscServer> {
    val config = LedgerConfig.load(configPath)
    return config to OpenRscServer.load(config.openRsc)
}

/**
 * Starts the admin console ([parseArguments] says how it's called). With
 * `--print-launch-settings` it prints what the launch script needs ([launchSettings]) and
 * exits instead; with `--check` it reads everything once and prints what it found ([check]).
 */
fun main(args: Array<String>) {
    val (mode, configPath) = orExit(withUsage = true) { parseArguments(args.toList()) }

    val (config, server) = orExit { loadSettings(configPath) }
    when (mode) {
        Mode.PRINT_LAUNCH_SETTINGS -> { print(orExit { launchSettings(config, server) }); return }
        Mode.CHECK -> { print(orExit { check(configPath, config, server) }); return }
        Mode.SERVE -> {}
    }

    val liveDatabase = orExit { server.database() }
    val ledger = orExit { Ledger(liveDatabase, config.dataDir, config.copy, server.equipmentTab) }
    log.info("Reading the server's database {} through a copy at {}", liveDatabase, ledger.copier.copy)
    if (server.equipmentTab) { log.info("The server's equipment tab is on: reading worn items from its equipped table") }

    embeddedServer(Netty, host = HOST, port = config.port) {
        launch(Dispatchers.IO) { ledger.copyLoop.run() }
        ledger.serve(this)
    }.start(wait = true)
}

/**
 * The ledger's working parts, wired as [main] runs them, so tests can run the same wiring.
 * [copyLoop] copies the server's [liveDatabase] into [dataDir], and after each copy records
 * its skill changes in [history], stamped with the server's save time, not the copy's
 * ([recordingTime]); [serve] answers the API and the pages from the newest copy and that
 * history, with the same [clock]. A history file that can't be used is an
 * [IllegalArgumentException], as [HistoryStore] says.
 */
class Ledger(liveDatabase: Path, dataDir: Path, copy: CopyConfig, equipmentTab: Boolean, private val clock: Clock = Clock.System) {
    val copier = DatabaseCopier(liveDatabase, dataDir.resolve("server-copy.db"))
    val gameData = SqliteGameData(copier.copy, equipmentTab = equipmentTab)
    val history = HistoryStore(dataDir.resolve("history.db"))

    /** The players missing a stat row at the last copy, so the warning comes once each time the list changes. */
    private var missingStats = emptyList<Int>()

    /** Whether the last copy's file time was ahead of the clock, so the warning comes once each time it runs ahead. */
    private var fileTimeAhead = false

    val copyLoop = DatabaseCopyLoop(liveDatabase, copier, copy, clock) { record -> afterCopy(record) }

    fun serve(application: Application) =
        application.adminModule(lastCopy = { copyLoop.lastCopy }, gameData = gameData, history = history, clock = clock)

    private fun afterCopy(record: CopyRecord) {
        val written = history.record(recordingTime(record), gameData.allSkills())
        if (written > 0) { log.info("Recorded {} skill changes", written) }

        val missing = gameData.playersMissingStats()
        if (missing.isNotEmpty() && missing != missingStats) {
            log.warn("Players with ids {} have no maxstats or curstats row, so their pages say there's no such player", missing)
        }
        missingStats = missing
    }

    /**
     * When [record]'s changes are recorded: the server's save time, the file's modified time,
     * unless that's after the copy was made. A save is never after the clock that wrote it, so
     * such a file was stamped by another clock, such as one restored from a machine whose clock
     * was ahead. Stamped that late, history would hide its gains until the clock got there and
     * refuse every save before then, so it's recorded at the copy's time instead.
     */
    private fun recordingTime(record: CopyRecord): Instant {
        val ahead = record.liveModified > record.copiedAt
        if (ahead && !fileTimeAhead) {
            log.warn(
                "The server's database file is stamped {}, {} after this machine's clock, so its changes are recorded at {} instead",
                record.liveModified, record.liveModified - record.copiedAt, record.copiedAt,
            )
        }
        fileTimeAhead = ahead
        return if (ahead) record.copiedAt else record.liveModified
    }
}

/** Runs [block], turning a setup problem into a one-line message ([withUsage], then [USAGE]) and exit code 1. */
private fun <T> orExit(withUsage: Boolean = false, block: () -> T): T = try {
    block()
} catch (e: IllegalArgumentException) {
    System.err.println("openrsc-ledger can't start: ${e.message}")
    if (withUsage) { System.err.println(USAGE) }
    exitProcess(1)
}
