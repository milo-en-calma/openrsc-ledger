package ledger.core.openrsc

import ledger.core.config.OpenRscConfig
import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRscServerTest {
    private val dir: Path = createTempDirectory("ledger-test")

    private fun databaseIn(path: String) = dir.resolve("server/inc/sqlite/$path")

    private fun server(world: String) = OpenRscServer.load(OpenRscConfig(dir, world))

    @Test
    fun `local conf wins over the launched world`() {
        Fixtures.openRscCheckout(
            dir,
            mapOf("local.conf" to "database:\n\tdb_name: oakvale\n", "fenmoor.conf" to "\tdb_name: fenmoor\n"),
            dbName = "oakvale",
        )
        assertEquals(databaseIn("oakvale.db"), server("fenmoor").database())
    }

    @Test
    fun `uses the launched world when there's no local conf`() {
        Fixtures.openRscCheckout(dir, mapOf("fenmoor.conf" to "\tdb_name: fenmoor\n"), dbName = "fenmoor")
        assertEquals(databaseIn("fenmoor.db"), server("fenmoor").database())
    }

    @Test
    fun `defaults to preservation when no world config names one`() {
        Fixtures.openRscCheckout(dir, emptyMap(), dbName = "preservation")
        assertEquals(databaseIn("preservation.db"), server("default").database())
    }

    @Test
    fun `a db_name in connections conf wins, as in the server`() {
        Fixtures.openRscCheckout(
            dir,
            mapOf("local.conf" to "\tdb_name: oakvale\n"),
            dbName = "shared",
            connections = "db_type: sqlite\ndb_name: shared\n",
        )
        assertEquals(databaseIn("shared.db"), server("local").database())
    }

    @Test
    fun `reads the game port, defaulting to 43594`() {
        Fixtures.openRscCheckout(dir, mapOf("local.conf" to "\tserver_port: 43597 # a comment\n"), dbName = "preservation")
        assertEquals(43597, server("local").gamePort)
        Files.delete(dir.resolve("server/local.conf"))
        assertEquals(43594, server("local").gamePort)
    }

    @Test
    fun `refuses a MySQL server`() {
        Fixtures.openRscCheckout(dir, emptyMap(), dbName = "preservation", connections = "db_type: mysql\n")
        assertFailsWith<IllegalArgumentException> { server("default").database() }
    }

    @Test
    fun `explains a missing database`() {
        Fixtures.openRscCheckout(dir, mapOf("local.conf" to "\tdb_name: elsewhere\n"), dbName = "preservation")
        val error = assertFailsWith<IllegalArgumentException> { server("local").database() }
        assertTrue("elsewhere.db" in error.message!!)
    }

    @Test
    fun `explains a folder that isn't an OpenRSC checkout`() {
        Files.createDirectories(dir.resolve("not-openrsc"))
        assertFailsWith<IllegalArgumentException> { OpenRscServer.load(OpenRscConfig(dir.resolve("not-openrsc"), "local")) }
    }

    @Test
    fun `reads db_type the way the server does`() {
        listOf(null, "sqlite", "SQLite", "1", "nonsense").forEach { assertTrue(OpenRscServer.isSqlite(it), "$it") }
        listOf("mysql", "MYSQL", "0", "7").forEach { assertFalse(OpenRscServer.isSqlite(it), "$it") }
    }
}
