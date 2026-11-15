package ledger.admin

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ledger.core.config.LedgerConfig
import ledger.core.copy.DatabaseCopier
import ledger.core.copy.DatabaseCopyLoop
import ledger.core.game.SqliteGameData
import ledger.core.openrsc.OpenRscServer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.system.exitProcess

/** The admin console listens on the loopback address only: being unreachable from other machines is its security. */
const val HOST = "127.0.0.1"

private const val PRINT_LAUNCH_SETTINGS = "--print-launch-settings"

private val log = LoggerFactory.getLogger("ledger.admin")

/**
 * Starts the admin console: `ledger-admin [--print-launch-settings] [config file]`. The
 * config file is the argument, else `$LEDGER_CONF`, else `ledger.conf` in the working
 * directory. With `--print-launch-settings` it prints what the launch script needs
 * ([launchSettings]) and exits instead.
 */
fun main(args: Array<String>) {
    val printLaunchSettings = PRINT_LAUNCH_SETTINGS in args
    val configArg = args.firstOrNull { it != PRINT_LAUNCH_SETTINGS }
    val configPath = Path.of(configArg ?: System.getenv("LEDGER_CONF") ?: "ledger.conf")

    val (config, server) = orExit {
        val config = LedgerConfig.load(configPath)
        config to OpenRscServer.load(config.openRsc)
    }
    if (printLaunchSettings) {
        print(launchSettings(config, server))
        return
    }

    val liveDatabase = orExit { server.database() }
    val copier = DatabaseCopier(liveDatabase, config.dataDir.resolve("server-copy.db"))
    val copyLoop = DatabaseCopyLoop(liveDatabase, copier, config.copy)
    log.info("Reading the server's database {} through a copy at {}", liveDatabase, copier.copy)

    embeddedServer(Netty, host = HOST, port = config.port) {
        launch(Dispatchers.IO) { copyLoop.run() }
        adminModule(lastCopy = { copyLoop.lastCopy }, gameData = SqliteGameData(copier.copy))
    }.start(wait = true)
}

/** Runs [block], turning a setup problem into a one-line message and exit code 1. */
private fun <T> orExit(block: () -> T): T = try {
    block()
} catch (e: IllegalArgumentException) {
    System.err.println("openrsc-ledger can't start: ${e.message}")
    exitProcess(1)
}
