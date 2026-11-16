package ledger.admin

import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** How `ledger-admin [--print-launch-settings | --check] [config file]` reads its arguments and settings. */
class MainTest {
    private val flag = "--print-launch-settings"

    @Test
    fun `with nothing given it serves, from ledger_conf in the working directory`() {
        assertEquals(Arguments(Mode.SERVE, configPath = Path.of("ledger.conf")), parseArguments(emptyList(), ledgerConf = null))
    }

    @Test
    fun `LEDGER_CONF names the config when no file is given`() {
        assertEquals(Path.of("/etc/ledger/site.conf"), parseArguments(emptyList(), ledgerConf = "/etc/ledger/site.conf").configPath)
    }

    @Test
    fun `a file given beats LEDGER_CONF`() {
        assertEquals(Path.of("mine.conf"), parseArguments(listOf("mine.conf"), ledgerConf = "/etc/ledger/site.conf").configPath)
    }

    @Test
    fun `an empty LEDGER_CONF counts as unset, as the launch script reads it`() {
        assertEquals(Path.of("ledger.conf"), parseArguments(emptyList(), ledgerConf = "").configPath)
        assertEquals(Path.of("ledger.conf"), parseArguments(listOf(flag), ledgerConf = "").configPath)
        assertEquals(Path.of("mine.conf"), parseArguments(listOf("mine.conf"), ledgerConf = "").configPath)
    }

    @Test
    fun `the flag alone prints the launch settings of the usual config`() {
        assertEquals(Arguments(Mode.PRINT_LAUNCH_SETTINGS, configPath = Path.of("ledger.conf")), parseArguments(listOf(flag), ledgerConf = null))
        assertEquals(Arguments(Mode.PRINT_LAUNCH_SETTINGS, configPath = Path.of("site.conf")), parseArguments(listOf(flag), ledgerConf = "site.conf"))
    }

    @Test
    fun `the flag may come before or after the file`() {
        val expected = Arguments(Mode.PRINT_LAUNCH_SETTINGS, configPath = Path.of("mine.conf"))
        assertEquals(expected, parseArguments(listOf(flag, "mine.conf"), ledgerConf = null))
        assertEquals(expected, parseArguments(listOf("mine.conf", flag), ledgerConf = null))
    }

    /** The message reading [args] is refused with. */
    private fun refusal(vararg args: String): String =
        assertFailsWith<IllegalArgumentException> { parseArguments(args.toList(), ledgerConf = null) }.message!!

    @Test
    fun `a second file is refused, not ignored`() {
        assertEquals("Give one config file, not two", refusal("first.conf", "second.conf"))
        assertEquals("Give one config file, not two", refusal("first.conf", flag, "second.conf"))
    }

    @Test
    fun `a mistyped flag is refused wherever it comes, never ignored or read as a file`() {
        assertEquals("Unknown option --print-launch-setings", refusal("mine.conf", "--print-launch-setings"))
        assertEquals("Unknown option --print-launch-setings", refusal("--print-launch-setings", "mine.conf"))
        assertEquals("Unknown option --help", refusal("--help"))
        assertEquals("Unknown option -p", refusal(flag, "-p"))
    }

    @Test
    fun `--check checks the given config, or the usual one, the flag before or after the file`() {
        assertEquals(Arguments(Mode.CHECK, Path.of("ledger.conf")), parseArguments(listOf("--check"), ledgerConf = null))
        assertEquals(Arguments(Mode.CHECK, Path.of("site.conf")), parseArguments(listOf("--check"), ledgerConf = "site.conf"))
        assertEquals(Arguments(Mode.CHECK, Path.of("mine.conf")), parseArguments(listOf("--check", "mine.conf"), ledgerConf = null))
        assertEquals(Arguments(Mode.CHECK, Path.of("mine.conf")), parseArguments(listOf("mine.conf", "--check"), ledgerConf = null))
    }

    @Test
    fun `--check and the launch settings flag together are refused, either way round`() {
        assertEquals("Give --print-launch-settings or --check, not both", refusal("--check", flag))
        assertEquals("Give --print-launch-settings or --check, not both", refusal(flag, "mine.conf", "--check"))
        assertEquals("Unknown option --chek", refusal("--chek"))
    }

    @Test
    fun `the usage line names both flags and the file`() {
        assertEquals("Usage: ledger-admin [--print-launch-settings | --check] [config file]", USAGE)
    }

    /** A game server checkout, and a ledger.conf beside it holding [settings] after its `openrsc.dir`. */
    private fun site(settings: String = ""): Path {
        val dir = createTempDirectory("ledger-test")
        Fixtures.openRscCheckout(dir.resolve("openrsc"), mapOf("local.conf" to "\tserver_port: 43597\n"), dbName = "preservation")
        return dir.resolve("ledger.conf").also { it.writeText("openrsc.dir = openrsc\n$settings") }
    }

    @Test
    fun `loads the settings and the server they name`() {
        val (config, server) = loadSettings(site("port = 9123\n"))
        assertEquals(9123, config.port)
        assertEquals(43597, server.gamePort)
    }

    @Test
    fun `loading needs no database, so the launch settings print before the server has made one`() {
        val conf = site()
        Files.list(conf.resolveSibling("openrsc/server/inc/sqlite")).use { files -> files.forEach(Path::deleteExisting) }
        assertEquals(43597, loadSettings(conf).second.gamePort)
    }

    @Test
    fun `every settings mistake is a plain refusal, never a crash`() {
        val dir = createTempDirectory("ledger-test")
        assertContains(assertFailsWith<IllegalArgumentException> { loadSettings(dir.resolve("ledger.conf")) }.message!!, "No config file at")
        assertContains(assertFailsWith<IllegalArgumentException> { loadSettings(site("port = abc\n")) }.message!!, "port has type STRING")
        assertContains(assertFailsWith<IllegalArgumentException> { loadSettings(site("copy {\n")) }.message!!, "ledger.conf: 3:")

        val noServer = dir.resolve("elsewhere").createDirectories().resolve("ledger.conf").also { it.writeText("openrsc.dir = nowhere\n") }
        assertContains(assertFailsWith<IllegalArgumentException> { loadSettings(noServer) }.message!!, "No server/ folder in")
    }

    @Test
    fun `a server settings file it can't read is a plain refusal naming it`() {
        val conf = site()
        val local = conf.resolveSibling("openrsc/server/local.conf")
        local.toFile().setReadable(false)
        assertContains(assertFailsWith<IllegalArgumentException> { loadSettings(conf) }.message!!, "Can't read the server's settings file $local")
    }
}
