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

class ServerDatabaseTest {
    private val dir: Path = createTempDirectory("ledger-test")

    private fun databaseIn(path: String) = dir.resolve("server/inc/sqlite/$path")

    @Test
    fun `local conf wins over the launched world`() {
        Fixtures.openRscCheckout(
            dir,
            mapOf("local.conf" to "database:\n\tdb_name: oakvale\n", "fenmoor.conf" to "\tdb_name: fenmoor\n"),
            dbName = "oakvale",
        )
        assertEquals(databaseIn("oakvale.db"), ServerDatabase.locate(OpenRscConfig(dir, "fenmoor")))
    }

    @Test
    fun `uses the launched world when there's no local conf`() {
        Fixtures.openRscCheckout(dir, mapOf("fenmoor.conf" to "\tdb_name: fenmoor\n"), dbName = "fenmoor")
        assertEquals(databaseIn("fenmoor.db"), ServerDatabase.locate(OpenRscConfig(dir, "fenmoor")))
    }

    @Test
    fun `defaults to preservation when no world config names one`() {
        Fixtures.openRscCheckout(dir, emptyMap(), dbName = "preservation")
        assertEquals(databaseIn("preservation.db"), ServerDatabase.locate(OpenRscConfig(dir, "default")))
    }

    @Test
    fun `a db_name in connections conf wins, as in the server`() {
        Fixtures.openRscCheckout(
            dir,
            mapOf("local.conf" to "\tdb_name: oakvale\n"),
            dbName = "shared",
            connections = "db_type: sqlite\ndb_name: shared\n",
        )
        assertEquals(databaseIn("shared.db"), ServerDatabase.locate(OpenRscConfig(dir, "local")))
    }

    @Test
    fun `refuses a MySQL server`() {
        Fixtures.openRscCheckout(dir, emptyMap(), dbName = "preservation", connections = "db_type: mysql\n")
        assertFailsWith<IllegalArgumentException> { ServerDatabase.locate(OpenRscConfig(dir, "default")) }
    }

    @Test
    fun `explains a missing database`() {
        Fixtures.openRscCheckout(dir, mapOf("local.conf" to "\tdb_name: elsewhere\n"), dbName = "preservation")
        val error = assertFailsWith<IllegalArgumentException> { ServerDatabase.locate(OpenRscConfig(dir, "local")) }
        assertTrue("elsewhere.db" in error.message!!)
    }

    @Test
    fun `explains a folder that isn't an OpenRSC checkout`() {
        Files.createDirectories(dir.resolve("not-openrsc"))
        assertFailsWith<IllegalArgumentException> { ServerDatabase.locate(OpenRscConfig(dir.resolve("not-openrsc"), "local")) }
    }

    @Test
    fun `reads db_type the way the server does`() {
        listOf(null, "sqlite", "SQLite", "1", "nonsense").forEach { assertTrue(ServerDatabase.isSqlite(it), "$it") }
        listOf("mysql", "MYSQL", "0", "7").forEach { assertFalse(ServerDatabase.isSqlite(it), "$it") }
    }
}
