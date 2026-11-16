package ledger.admin

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ledger.core.config.LedgerConfig
import ledger.core.copy.DatabaseCopier
import ledger.core.copy.DatabaseCopyLoop
import ledger.core.game.SqliteGameData
import ledger.core.history.HistoryStore
import ledger.core.openrsc.OpenRscServer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.system.exitProcess

/** The admin console listens on the loopback address only: being unreachable from other machines is its security. */
const val HOST = "127.0.0.1"

private const val PRINT_LAUNCH_SETTINGS = "--print-launch-settings"

const val USAGE = "Usage: ledger-admin [$PRINT_LAUNCH_SETTINGS] [config file]"

private val log = LoggerFactory.getLogger("ledger.admin")

/** What `ledger-admin` was asked to do: read the config at [configPath], and print the launch settings or serve. */
data class Arguments(val printLaunchSettings: Boolean, val configPath: Path)

/**
 * Reads `ledger-admin [--print-launch-settings] [config file]`, the flag before or after the
 * file. The config file is the argument, else [ledgerConf] (`$LEDGER_CONF`, unless it's empty,
 * as the launch script reads it), else `ledger.conf` in the working directory. Any other option,
 * or a second file, is an [IllegalArgumentException], so a mistyped flag is never ignored.
 */
fun parseArguments(args: List<String>, ledgerConf: String? = System.getenv("LEDGER_CONF")): Arguments {
    val files = args.filter { it != PRINT_LAUNCH_SETTINGS }
    files.firstOrNull { it.startsWith("-") }?.let { throw IllegalArgumentException("Unknown option $it") }
    require(files.size <= 1) { "Give one config file, not two" }
    return Arguments(
        printLaunchSettings = PRINT_LAUNCH_SETTINGS in args,
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
 * exits instead.
 */
fun main(args: Array<String>) {
    val (printLaunchSettings, configPath) = orExit(withUsage = true) { parseArguments(args.toList()) }

    val (config, server) = orExit { loadSettings(configPath) }
    if (printLaunchSettings) {
        print(launchSettings(config, server))
        return
    }

    val liveDatabase = orExit { server.database() }
    val copier = DatabaseCopier(liveDatabase, config.dataDir.resolve("server-copy.db"))
    val gameData = SqliteGameData(copier.copy, equipmentTab = server.equipmentTab)
    val history = HistoryStore(config.dataDir.resolve("history.db"))
    // Each copy's changes are stamped with the server's save time, not the copy's.
    val copyLoop = DatabaseCopyLoop(liveDatabase, copier, config.copy) { copy ->
        val written = history.record(copy.liveModified, gameData.allSkills())
        if (written > 0) { log.info("Recorded {} skill changes", written) }
    }
    log.info("Reading the server's database {} through a copy at {}", liveDatabase, copier.copy)
    if (server.equipmentTab) { log.info("The server's equipment tab is on: reading worn items from its equipped table") }

    embeddedServer(Netty, host = HOST, port = config.port) {
        launch(Dispatchers.IO) { copyLoop.run() }
        adminModule(lastCopy = { copyLoop.lastCopy }, gameData = gameData, history = history)
    }.start(wait = true)
}

/** Runs [block], turning a setup problem into a one-line message ([withUsage], then [USAGE]) and exit code 1. */
private fun <T> orExit(withUsage: Boolean = false, block: () -> T): T = try {
    block()
} catch (e: IllegalArgumentException) {
    System.err.println("openrsc-ledger can't start: ${e.message}")
    if (withUsage) { System.err.println(USAGE) }
    exitProcess(1)
}
