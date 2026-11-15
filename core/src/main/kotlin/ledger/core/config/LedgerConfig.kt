package ledger.core.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * The ledger's settings, read from `ledger.conf`: plain `key = value` lines with `#`
 * comments, so the launch script can read the same file. `ledger.conf.example` lists
 * every key.
 */
data class LedgerConfig(
    /** The port the admin console listens on, on 127.0.0.1 only. */
    val port: Int,
) {
    companion object {
        const val DEFAULT_PORT = 8080

        fun load(path: Path): LedgerConfig {
            require(Files.isRegularFile(path)) {
                "No config file at ${path.toAbsolutePath()}. Copy ledger.conf.example to ledger.conf and edit it."
            }
            val properties = Properties().apply { Files.newBufferedReader(path).use { load(it) } }
            return parse(properties)
        }

        fun parse(properties: Properties): LedgerConfig {
            val port = properties.getProperty("port")?.trim()?.let {
                requireNotNull(it.toIntOrNull()) { "port must be a number, not '$it'" }
            } ?: DEFAULT_PORT
            return LedgerConfig(port = port)
        }
    }
}
