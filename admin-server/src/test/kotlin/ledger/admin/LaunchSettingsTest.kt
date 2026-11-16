package ledger.admin

import com.typesafe.config.ConfigFactory
import ledger.core.config.LedgerConfig
import ledger.core.fixture.Fixtures
import ledger.core.openrsc.OpenRscServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LaunchSettingsTest {
    private val dir = createTempDirectory("ledger-test")

    /** The keys the `ledger` script reads, in the order [launchSettings] prints them. */
    private val keys = listOf("server_dir", "world", "game_port", "ledger_port", "data_dir", "world_log", "default_log")

    /** The `ledger` script's own `read_settings` function, taken from the script. */
    private val readSettings: String = Regex("""(?ms)^read_settings\(\) \{.*?^}""")
        .find(Files.readString(Path.of("../ledger")))
        ?.value
        ?: error("The ledger script has no read_settings function")

    /** Sets up a game server checkout in [openRscDir] and reads [hocon] as `ledger.conf`, both under [dir]. */
    private fun settings(hocon: String, openRscDir: String = "openrsc", world: String = "local", worldConf: String = "\tserver_port: 43597\n"): String {
        Fixtures.openRscCheckout(dir.resolve(openRscDir), mapOf("$world.conf" to worldConf), dbName = "preservation")
        val config = LedgerConfig.parse(ConfigFactory.parseString(hocon), dir)
        return launchSettings(config, OpenRscServer.load(config.openRsc))
    }

    /** Runs the script's `read_settings` on [printed], as `./ledger` does, and returns what each key's variable then holds. */
    private fun readBack(printed: String): Map<String, String> {
        val script = "$readSettings\nread_settings\nprintf '%s\\0' " + keys.joinToString(" ") { "\"\$$it\"" }
        val bash = ProcessBuilder("bash", "-c", script).start()
        bash.outputStream.bufferedWriter().use { it.write(printed) }
        val output = bash.inputStream.bufferedReader().readText()
        val errors = bash.errorStream.bufferedReader().readText()
        assertTrue(bash.waitFor(10, TimeUnit.SECONDS), "bash didn't finish")
        assertEquals(0, bash.exitValue(), errors)
        return keys.zip(output.removeSuffix("\u0000").split("\u0000")).toMap()
    }

    /** What [launchSettings] printed, as a map from key to value, split at each line's first `=`. */
    private fun printedValues(printed: String): Map<String, String> =
        printed.lines().filter { it.isNotEmpty() }.associate { it.substringBefore("=") to it.substringAfter("=") }

    @Test
    fun `prints what the launch script reads, one key=value a line`() {
        Fixtures.openRscCheckout(dir.resolve("openrsc"), mapOf("local.conf" to "\tserver_port: 43597\n"), dbName = "preservation")
        val config = LedgerConfig.parse(
            ConfigFactory.parseString("openrsc { dir = openrsc, world = fenmoor }\nport = 9123"),
            dir,
        )
        assertEquals(
            """
            server_dir=$dir/openrsc/server
            world=fenmoor
            game_port=43597
            ledger_port=9123
            data_dir=$dir/data
            world_log=$dir/openrsc/server/logs/runescape_1.log
            default_log=$dir/openrsc/server/logs/default.log

            """.trimIndent(),
            launchSettings(config, OpenRscServer.load(config.openRsc)),
        )
    }

    @Test
    fun `prints every key the script reads, once each, in a fixed order`() {
        val printed = settings("openrsc { dir = openrsc, world = local }")
        assertEquals(keys, printed.lines().filter { it.isNotEmpty() }.map { it.substringBefore("=") })
        assertTrue(printed.endsWith("\n"))
    }

    @Test
    fun `the script reads back plain settings exactly as printed`() {
        val printed = settings("openrsc { dir = openrsc, world = local }")
        assertEquals(printedValues(printed), readBack(printed))
    }

    @Test
    fun `the script reads back spaces, quotes and shell characters exactly as printed`() {
        val odd = "open rsc a=b #c \$d `e` 'f' \"g\" ü"
        val printed = settings(
            """openrsc { dir = "${odd.replace("\"", "\\\"")}", world = "my world" }""" + "\n" +
                """data-dir = "my data=x #y ${'$'}z"""",
            openRscDir = odd,
            world = "my world",
            worldConf = "\tserver_port: 43597\n\tserver_name: Fen Moor\n",
        )
        val values = printedValues(printed)
        assertEquals("$dir/$odd/server", values["server_dir"])
        assertEquals("my world", values["world"])
        assertEquals("$dir/my data=x #y \$z", values["data_dir"])
        assertEquals("$dir/$odd/server/logs/fen_moor_1.log", values["world_log"])
        assertEquals(values, readBack(printed))
    }

    @Test
    fun `the script keeps a value's trailing equals sign`() {
        val printed = settings("openrsc { dir = \"openrsc=\", world = local }\ndata-dir = \"data=\"", openRscDir = "openrsc=")
        assertEquals("$dir/data=", printedValues(printed)["data_dir"])
        assertEquals(printedValues(printed), readBack(printed))
    }

    @Test
    fun `the script ignores a line it doesn't know`() {
        val printed = settings("openrsc { dir = openrsc, world = local }")
        assertEquals(printedValues(printed), readBack("something_new=1\nno equals sign\n$printed"))
    }

    @Test
    fun `refuses a data-dir with a line break in it, naming the setting`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            settings("openrsc { dir = openrsc, world = local }\ndata-dir = \"da\\nta\"")
        }
        assertEquals("data-dir has a line break in it, which the ledger script can't read", refused.message)
    }

    @Test
    fun `refuses an openrsc dir with a line break in it, naming the setting`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            settings("openrsc { dir = \"open\\nrsc\", world = local }", openRscDir = "open\nrsc")
        }
        assertEquals("openrsc.dir has a line break in it, which the ledger script can't read", refused.message)
    }

    @Test
    fun `refuses a world with a line break in it, naming the setting`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            settings("openrsc { dir = openrsc, world = \"lo\\ncal\" }", world = "lo\ncal")
        }
        assertEquals("openrsc.world has a line break in it, which the ledger script can't read", refused.message)
    }
}
