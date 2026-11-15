package ledger.core.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * The ledger's settings, read from `ledger.conf` (HOCON), with defaults from
 * `ledger-defaults.conf`. `ledger.conf.example` explains every setting. Relative paths
 * are resolved against the folder `ledger.conf` is in, so they don't depend on where the
 * app is started from.
 */
data class LedgerConfig(
    /** The port the admin console listens on, on 127.0.0.1 only. */
    val port: Int,
    /** Where the ledger keeps its own files, such as its copy of the server's database. */
    val dataDir: Path,
    val openRsc: OpenRscConfig,
    val copy: CopyConfig,
) {
    companion object {
        fun load(path: Path): LedgerConfig {
            require(Files.isRegularFile(path)) {
                "No config file at ${path.toAbsolutePath()}. Copy ledger.conf.example to ledger.conf and edit it."
            }
            return parse(ConfigFactory.parseFile(path.toFile()), path.toAbsolutePath().parent)
        }

        /** Reads [config] over the defaults, resolving relative paths against [baseDir]. */
        fun parse(config: Config, baseDir: Path): LedgerConfig {
            val merged = config.withFallback(ConfigFactory.parseResources("ledger-defaults.conf")).resolve()
            require(merged.hasPath("openrsc.dir")) {
                "openrsc.dir is not set: it must name the OpenRSC checkout, the folder that holds server/"
            }
            return LedgerConfig(
                port = merged.getInt("port"),
                dataDir = baseDir.resolve(merged.getString("data-dir")).normalize(),
                openRsc = OpenRscConfig(
                    dir = baseDir.resolve(merged.getString("openrsc.dir")).normalize(),
                    world = merged.getString("openrsc.world"),
                ),
                copy = CopyConfig(
                    checkEvery = merged.getDuration("copy.check-every").toKotlinDuration(),
                    atMostEvery = merged.getDuration("copy.at-most-every").toKotlinDuration(),
                ),
            )
        }
    }
}

/** Where the OpenRSC server is, and which world config it is launched with. */
data class OpenRscConfig(
    /** The OpenRSC checkout: the folder that holds `server/`. */
    val dir: Path,
    /** The world config's name without `.conf`, such as `local` or `preservation`. */
    val world: String,
)

/** When the ledger copies the server's database. */
data class CopyConfig(
    /** How often to check the live database's modification time. */
    val checkEvery: Duration,
    /** The least time between two copies, however often the database changes. */
    val atMostEvery: Duration,
)
