package ledger.admin

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ledger.core.config.LedgerConfig
import ledger.core.copy.DatabaseCopier
import ledger.core.copy.DatabaseCopyLoop
import ledger.core.openrsc.ServerDatabase
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.system.exitProcess

/** The admin console listens on the loopback address only: being unreachable from other machines is its security. */
const val HOST = "127.0.0.1"

private val log = LoggerFactory.getLogger("ledger.admin")

/**
 * Starts the admin console. The config file is the first argument, else `$LEDGER_CONF`,
 * else `ledger.conf` in the working directory.
 */
fun main(args: Array<String>) {
    val configPath = Path.of(args.firstOrNull() ?: System.getenv("LEDGER_CONF") ?: "ledger.conf")
    val (config, liveDatabase) = try {
        val config = LedgerConfig.load(configPath)
        config to ServerDatabase.locate(config.openRsc)
    } catch (e: IllegalArgumentException) {
        System.err.println("openrsc-ledger can't start: ${e.message}")
        exitProcess(1)
    }

    val copier = DatabaseCopier(liveDatabase, config.dataDir.resolve("server-copy.db"))
    val copyLoop = DatabaseCopyLoop(liveDatabase, copier, config.copy)
    log.info("Reading the server's database {} through a copy at {}", liveDatabase, copier.copy)

    embeddedServer(Netty, host = HOST, port = config.port) {
        launch(Dispatchers.IO) { copyLoop.run() }
        adminModule(lastCopy = { copyLoop.lastCopy })
    }.start(wait = true)
}
