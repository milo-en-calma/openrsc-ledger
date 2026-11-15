package ledger.admin

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import ledger.core.config.LedgerConfig
import java.nio.file.Path

/** The admin console listens on the loopback address only: being unreachable from other machines is its security. */
const val HOST = "127.0.0.1"

/**
 * Starts the admin console. The config file is the first argument, else `$LEDGER_CONF`,
 * else `ledger.conf` in the working directory.
 */
fun main(args: Array<String>) {
    val configPath = Path.of(args.firstOrNull() ?: System.getenv("LEDGER_CONF") ?: "ledger.conf")
    val config = LedgerConfig.load(configPath)
    embeddedServer(Netty, host = HOST, port = config.port) { adminModule() }.start(wait = true)
}
