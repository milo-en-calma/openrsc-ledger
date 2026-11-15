package ledger.core.config

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `needs the OpenRSC folder`() {
        assertFailsWith<IllegalArgumentException> { parse("port = 9123") }
    }

    @Test
    fun `rejects a port that isn't a number`() {
        assertFailsWith<ConfigException.WrongType> { parse("openrsc.dir = /srv/openrsc\nport = eighty") }
    }
}
