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
            val checkEvery = merged.duration("copy.check-every")
            val atMostEvery = merged.duration("copy.at-most-every")
            require(checkEvery.isPositive()) { "copy.check-every must be more than zero, such as 15s" }
            require(!atMostEvery.isNegative()) { "copy.at-most-every can't be below zero" }
            return LedgerConfig(
                port = merged.port(),
                dataDir = baseDir.resolve(merged.nonEmptyString("data-dir")).normalize(),
                openRsc = OpenRscConfig(
                    dir = baseDir.resolve(merged.nonEmptyString("openrsc.dir")).normalize(),
                    world = merged.nonEmptyString("openrsc.world"),
                ),
                copy = CopyConfig(checkEvery = checkEvery, atMostEvery = atMostEvery),
            )
        }

        private val BARE_NUMBER = Regex("""\s*[+-]?[0-9.]+\s*""")

        /** A duration, refusing a bare number, which HOCON would quietly read as milliseconds. */
        private fun Config.duration(path: String): Duration {
            require(!BARE_NUMBER.matches(getValue(path).unwrapped().toString())) {
                "$path needs a unit, such as 15s"
            }
            return getDuration(path).toKotlinDuration()
        }

        /** The admin console's port: a whole number from 1 to 65535. */
        private fun Config.port(): Int {
            val number = getNumber("port").toDouble()
            require(number == Math.rint(number) && number in 1.0..65535.0) {
                "port must be a whole number from 1 to 65535"
            }
            return number.toInt()
        }

        private fun Config.nonEmptyString(path: String): String =
            getString(path).also { require(it.isNotBlank()) { "$path can't be empty" } }
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
