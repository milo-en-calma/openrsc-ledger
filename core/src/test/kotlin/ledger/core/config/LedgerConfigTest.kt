package ledger.core.config

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LedgerConfigTest {
    private val baseDir = Path.of("/srv/ledger")

    private fun parse(text: String) = LedgerConfig.parse(ConfigFactory.parseString(text), baseDir)

    @Test
    fun `reads every setting`() {
        val config = parse(
            """
            port = 9123
            data-dir = /var/ledger
            openrsc { dir = /srv/openrsc, world = preservation }
            copy { check-every = 5s, at-most-every = 2m }
            """,
        )
        assertEquals(9123, config.port)
        assertEquals(Path.of("/var/ledger"), config.dataDir)
        assertEquals(OpenRscConfig(Path.of("/srv/openrsc"), "preservation"), config.openRsc)
        assertEquals(CopyConfig(checkEvery = 5.seconds, atMostEvery = 120.seconds), config.copy)
    }

    @Test
    fun `fills in the defaults`() {
        val config = parse("openrsc.dir = /srv/openrsc")
        assertEquals(8080, config.port)
        assertEquals("default", config.openRsc.world)
        assertEquals(CopyConfig(checkEvery = 15.seconds, atMostEvery = 60.seconds), config.copy)
    }

    @Test
    fun `resolves relative paths against the config file's folder`() {
        val config = parse("openrsc.dir = ../openrsc")
        assertEquals(Path.of("/srv/openrsc"), config.openRsc.dir)
        assertEquals(Path.of("/srv/ledger/data"), config.dataDir)
    }

    @Test
    fun `the example config loads, and shows the defaults`() {
        val example = LedgerConfig.load(Path.of("../ledger.conf.example"))
        val defaults = parse("openrsc.dir = /path/to/openrsc")
        assertEquals(defaults.copy(dataDir = example.dataDir), example)
        assertEquals(Path.of("/path/to/openrsc"), example.openRsc.dir)
    }

    @Test
    fun `setting one value in a block keeps the block's other defaults`() {
        val config = parse("openrsc.dir = /srv/openrsc\ncopy { at-most-every = 15s }")
        assertEquals(CopyConfig(checkEvery = 15.seconds, atMostEvery = 15.seconds), config.copy)
        assertEquals("default", config.openRsc.world)
    }

    @Test
    fun `substitutions are resolved, and an unset optional one keeps the earlier value`() {
        val config = parse(
            """
            openrsc { dir = /srv/openrsc, world = preservation }
            data-dir = /var/ledger/${'$'}{openrsc.world}
            data-dir = ${'$'}{?LEDGER_TEST_UNSET_VARIABLE}
            """,
        )
        assertEquals(Path.of("/var/ledger/preservation"), config.dataDir)
    }

    @Test
    fun `refuses a mistyped setting, naming its line and the setting it may have meant`() {
        assertEquals(
            "copy.at-most-evry isn't a ledger setting (line 2); did you mean copy.at-most-every?",
            refusal("copy { at-most-evry = 15s }"),
        )
        assertEquals("prot isn't a ledger setting (line 2); did you mean port?", refusal("prot = 9000"))
    }

    @Test
    fun `refuses a setting outside its block, naming the block it belongs in`() {
        assertEquals("world isn't a ledger setting (line 2); did you mean openrsc.world?", refusal("world = local"))
        assertEquals("copy.dir isn't a ledger setting (line 2); did you mean openrsc.dir?", refusal("copy.dir = /srv"))
    }

    @Test
    fun `refuses a setting like nothing the ledger reads, with no guess`() {
        assertEquals("colour isn't a ledger setting (line 2)", refusal("colour = blue"))
        assertEquals("openrsc.host isn't a ledger setting (line 2)", refusal("openrsc.host = example.org"))
    }

    @Test
    fun `refuses a value kept only for a substitution, since it can't be told from a typo`() {
        val error = assertFailsWith<IllegalArgumentException> { parse("base = /srv\nopenrsc.dir = ${'$'}{base}/openrsc") }
        assertEquals("base isn't a ledger setting (line 1)", error.message)
    }

    @Test
    fun `names every unknown setting at once, in the order they're written`() {
        assertEquals(
            "prot isn't a ledger setting (line 2); did you mean port?; " +
                "copy.chek-every isn't a ledger setting (line 3); did you mean copy.check-every?",
            refusal("prot = 9000\ncopy.chek-every = 5s"),
        )
    }

    @Test
    fun `a block given as one value is refused by its type, not as unknown`() {
        assertContains(refusal("copy = 15s"), "copy")
    }

    @Test
    fun `load names the file an unknown setting is in`() {
        val file = createTempDirectory("ledger-config").resolve("ledger.conf")
        file.writeText("openrsc {\n  dir = /srv/openrsc\n  wrold = local\n}\n")
        val error = assertFailsWith<IllegalArgumentException> { LedgerConfig.load(file) }
        assertEquals("openrsc.wrold isn't a ledger setting (line 3 of $file); did you mean openrsc.world?", error.message)
    }

    @Test
    fun `a port given in quotes is read as a number`() {
        assertEquals(9000, parse("openrsc.dir = /srv/openrsc\nport = \"9000\"").port)
    }

    @Test
    fun `load resolves relative paths against the folder the file is in`() {
        val folder = createTempDirectory("ledger-config").resolve("site")
        folder.createDirectories()
        val file = folder.resolve("ledger.conf")
        file.writeText("openrsc.dir = ../openrsc\ndata-dir = copies\n")

        val config = LedgerConfig.load(file)
        assertEquals(folder.parent.resolve("openrsc"), config.openRsc.dir)
        assertEquals(folder.resolve("copies"), config.dataDir)
    }

    @Test
    fun `load names a missing file and says where to start`() {
        val missing = createTempDirectory("ledger-config").resolve("ledger.conf")
        val error = assertFailsWith<IllegalArgumentException> { LedgerConfig.load(missing) }
        assertContains(error.message!!, missing.toString())
        assertContains(error.message!!, "ledger.conf.example")
    }

    @Test
    fun `load refuses a folder in place of the file`() {
        val folder = createTempDirectory("ledger-config")
        assertFailsWith<IllegalArgumentException> { LedgerConfig.load(folder) }
    }

    @Test
    fun `needs the OpenRSC folder`() {
        val error = assertFailsWith<IllegalArgumentException> { parse("port = 9123") }
        assertContains(error.message!!, "openrsc.dir")
    }

    /** The message parsing [settings] fails with, on top of a valid openrsc.dir. */
    private fun refusal(settings: String): String =
        assertFailsWith<IllegalArgumentException> { parse("openrsc.dir = /srv/openrsc\n$settings") }.message!!

    @Test
    fun `refuses a check interval of zero or less`() {
        assertEquals("copy.check-every must be more than zero, such as 15s", refusal("copy.check-every = 0s"))
        assertEquals("copy.check-every must be more than zero, such as 15s", refusal("copy.check-every = -5s"))
        assertEquals(500.milliseconds, parse("openrsc.dir = /srv/openrsc\ncopy.check-every = 500ms").copy.checkEvery)
    }

    @Test
    fun `refuses a negative copy gap, but zero copies on every save`() {
        assertEquals("copy.at-most-every can't be below zero", refusal("copy.at-most-every = -1s"))
        assertEquals(Duration.ZERO, parse("openrsc.dir = /srv/openrsc\ncopy.at-most-every = 0s").copy.atMostEvery)
    }

    @Test
    fun `refuses a copy interval with no unit, which would read as milliseconds`() {
        assertEquals("copy.check-every needs a unit, such as 15s", refusal("copy.check-every = 15"))
        assertEquals("copy.check-every needs a unit, such as 15s", refusal("copy.check-every = \"15\""))
        assertEquals("copy.check-every needs a unit, such as 15s", refusal("copy.check-every = 1.5"))
        assertEquals("copy.at-most-every needs a unit, such as 15s", refusal("copy.at-most-every = 60"))
        assertEquals(90.seconds, parse("openrsc.dir = /srv/openrsc\ncopy.at-most-every = 1.5m").copy.atMostEvery)
    }

    @Test
    fun `refuses a port outside 1 to 65535 or with a fraction`() {
        val message = "port must be a whole number from 1 to 65535"
        listOf("0", "-1", "65536", "70000", "80.5").forEach { assertEquals(message, refusal("port = $it"), "port = $it") }
        assertEquals(1, parse("openrsc.dir = /srv/openrsc\nport = 1").port)
        assertEquals(65535, parse("openrsc.dir = /srv/openrsc\nport = 65535").port)
    }

    @Test
    fun `refuses an empty folder or world`() {
        assertEquals("openrsc.dir can't be empty", assertFailsWith<IllegalArgumentException> { parse("openrsc.dir = \"\"") }.message)
        assertEquals("data-dir can't be empty", refusal("data-dir = \"\""))
        assertEquals("openrsc.world can't be empty", refusal("openrsc.world = \" \""))
    }

    @Test
    fun `refuses a value of the wrong type plainly, naming the setting`() {
        val port = assertFailsWith<IllegalArgumentException> { parse("openrsc.dir = /srv/openrsc\nport = eighty") }
        assertContains(port.message!!, "port has type STRING rather than NUMBER")
        assertIs<ConfigException.WrongType>(port.cause)
        assertContains(refusal("openrsc.dir { a = 1 }"), "openrsc.dir has type OBJECT rather than STRING")
    }

    @Test
    fun `refuses a duration with no number plainly`() {
        assertContains(refusal("copy.check-every = soon"), "No number in duration value 'soon'")
    }

    @Test
    fun `refuses an unset substitution plainly`() {
        assertContains(refusal("data-dir = ${'$'}{LEDGER_TEST_UNSET_VARIABLE}"), "LEDGER_TEST_UNSET_VARIABLE")
    }

    @Test
    fun `load refuses a file that doesn't parse plainly, naming the file and line`() {
        val file = createTempDirectory("ledger-config").resolve("ledger.conf")
        file.writeText("openrsc {\n  dir = /srv/openrsc\n")
        val error = assertFailsWith<IllegalArgumentException> { LedgerConfig.load(file) }
        assertContains(error.message!!, "$file: 3:")
    }
}
