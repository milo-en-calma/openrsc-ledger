package ledger.core.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigUtil
import com.typesafe.config.ConfigValue
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
            return parse(refusingMistakes { ConfigFactory.parseFile(path.toFile()) }, path.toAbsolutePath().parent)
        }

        /**
         * Reads [config] over the defaults, resolving relative paths against [baseDir]. Every
         * mistake in it, such as text where a number goes, is an [IllegalArgumentException].
         */
        fun parse(config: Config, baseDir: Path): LedgerConfig = refusingMistakes { parseChecked(config, baseDir) }

        private fun parseChecked(config: Config, baseDir: Path): LedgerConfig {
            refuseUnknownSettings(config)
            val merged = config.withFallback(DEFAULTS).resolve()
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

        /**
         * Runs [block], turning the settings library's own error (an unclosed brace, text where
         * a number goes) into the same [IllegalArgumentException] as every other settings
         * mistake. Its message, which names the file, line and setting, is kept.
         */
        private inline fun <T> refusingMistakes(block: () -> T): T = try {
            block()
        } catch (e: ConfigException) {
            throw IllegalArgumentException(e.message, e)
        }

        private val DEFAULTS: Config = ConfigFactory.parseResources("ledger-defaults.conf")

        /** Every setting the ledger reads, as a path such as `copy.check-every`: each one with a default, and openrsc.dir. */
        private val KNOWN_SETTINGS: Set<String> = leafPaths(DEFAULTS.root(), emptyList()) + "openrsc.dir"

        /** The blocks those settings are in, such as `copy`. */
        private val KNOWN_BLOCKS: Set<String> = KNOWN_SETTINGS.flatMap { setting ->
            val keys = ConfigUtil.splitPath(setting)
            (1 until keys.size).map { ConfigUtil.joinPath(keys.subList(0, it)) }
        }.toSet()

        private fun leafPaths(block: ConfigObject, prefix: List<String>): Set<String> = block.flatMap { (key, value) ->
            if (value is ConfigObject) leafPaths(value, prefix + key) else setOf(ConfigUtil.joinPath(prefix + key))
        }.toSet()

        /**
         * Refuses a setting the ledger doesn't read, such as a typo (`at-most-evry`) or a setting
         * outside its block (`world` at the top level), which would otherwise be quietly ignored,
         * naming each one with its line and a known setting it may have meant.
         */
        private fun refuseUnknownSettings(config: Config) {
            val unknown = unknownSettings(config.root(), emptyList())
                .sortedWith(compareBy({ it.second.origin().lineNumber() }, { it.first }))
            require(unknown.isEmpty()) {
                unknown.joinToString("; ") { (setting, value) ->
                    val origin = value.origin()
                    val line = origin.lineNumber().takeIf { it > 0 }?.let { "line $it" }
                    val where = listOfNotNull(line, origin.filename()).joinToString(" of ")
                    val refusal = "$setting isn't a ledger setting" + (if (where.isEmpty()) "" else " ($where)")
                    suggestion(setting)?.let { "$refusal; did you mean $it?" } ?: refusal
                }
            }
        }

        /** The settings in [block] that the ledger doesn't know, each with its value, for where it was written. */
        private fun unknownSettings(block: ConfigObject, prefix: List<String>): List<Pair<String, ConfigValue>> =
            block.flatMap { (key, value) ->
                val path = ConfigUtil.joinPath(prefix + key)
                when {
                    path in KNOWN_SETTINGS -> emptyList()
                    // A known block given as one value, such as `copy = 15s`, is left to the type checks.
                    path in KNOWN_BLOCKS -> if (value is ConfigObject) unknownSettings(value, prefix + key) else emptyList()
                    else -> listOf(path to value)
                }
            }

        /** A known setting [unknown] may have meant: one with the same last name in another block, or one a few letters off. */
        private fun suggestion(unknown: String): String? {
            val name = ConfigUtil.splitPath(unknown).last()
            return KNOWN_SETTINGS.firstOrNull { ConfigUtil.splitPath(it).last() == name }
                ?: KNOWN_SETTINGS.map { it to editDistance(it, unknown) }.filter { it.second <= 3 }.minByOrNull { it.second }?.first
        }

        /** How many letters must be added, removed or changed to turn [a] into [b]. */
        private fun editDistance(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val current = IntArray(b.length + 1)
                current[0] = i
                for (j in 1..b.length) {
                    val change = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + change)
                }
                previous = current
            }
            return previous[b.length]
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
