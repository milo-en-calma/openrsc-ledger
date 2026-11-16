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
    fun `names the world's log as the server does, defaulting to runescape_1`() {
        Fixtures.openRscCheckout(
            dir,
            mapOf("local.conf" to "\tserver_name: Fenmoor Vale  # shown at login\n\tworld_number: 3\n"),
            dbName = "preservation",
        )
        assertEquals(dir.resolve("server/logs/fenmoor_vale_3.log"), server("local").worldLog)
        Files.delete(dir.resolve("server/local.conf"))
        assertEquals(dir.resolve("server/logs/runescape_1.log"), server("local").worldLog)
    }

    @Test
    fun `reads the equipment tab setting, off unless set`() {
        Fixtures.openRscCheckout(dir, mapOf("local.conf" to "\twant_equipment_tab: TRUE # Adds an equipment tab\n"), dbName = "preservation")
        assertTrue(server("local").equipmentTab)
        Files.writeString(dir.resolve("server/local.conf"), "\twant_equipment_tab: false\n")
        assertFalse(server("local").equipmentTab)
        Files.delete(dir.resolve("server/local.conf"))
        assertFalse(server("local").equipmentTab)
    }

    @Test
    fun `the equipment tab setting comes from local conf over the launched world, as db_name does`() {
        Fixtures.openRscCheckout(
            dir,
            mapOf("local.conf" to "\twant_equipment_tab: false\n", "cabbage.conf" to "\twant_equipment_tab: true\n"),
            dbName = "preservation",
        )
        assertFalse(server("cabbage").equipmentTab)
        Files.delete(dir.resolve("server/local.conf"))
        assertTrue(server("cabbage").equipmentTab)
    }

    @Test
    fun `refuses an equipment tab setting the server would refuse`() {
        Fixtures.openRscCheckout(dir, mapOf("local.conf" to "\twant_equipment_tab: yes\n"), dbName = "preservation")
        val error = assertFailsWith<IllegalArgumentException> { server("local") }
        assertTrue("want_equipment_tab" in error.message!!, error.message)
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

    @Test
    fun `reads a db_type number as Java's parseInt does, sign and leading zeros included`() {
        listOf("+1", "01", "", "1.0", "one").forEach { assertTrue(OpenRscServer.isSqlite(it), "\"$it\"") }
        listOf("-1", "+0", "00", "2147483647").forEach { assertFalse(OpenRscServer.isSqlite(it), "\"$it\"") }
    }

    @Test
    fun `a db_type in a world config counts, as the server reads every file into one set`() {
        Fixtures.openRscCheckout(dir, mapOf("local.conf" to "\tdb_type: mysql\n"), dbName = "preservation", connections = "")
        assertFailsWith<IllegalArgumentException> { server("local").database() }
    }

    @Test
    fun `explains a checkout with no connections conf`() {
        Fixtures.openRscCheckout(dir, emptyMap(), dbName = "preservation")
        Files.delete(dir.resolve("server/connections.conf"))
        val error = assertFailsWith<IllegalArgumentException> { server("default") }
        assertTrue("connections.conf" in error.message!!, error.message)
    }

    @Test
    fun `the database is inc_sqlite_db_name_db whatever db_name holds, as the server builds it`() {
        Fixtures.openRscCheckout(dir, mapOf("local.conf" to "\tdb_name: null\n"), dbName = "null")
        assertEquals(databaseIn("null.db"), server("local").database())
        Files.writeString(dir.resolve("server/local.conf"), "\tdb_name: Oak Vale\n")
        Fixtures.database(databaseIn("Oak Vale.db"))
        assertEquals(databaseIn("Oak Vale.db"), server("local").database())
    }

    @Test
    fun `refuses a database path that is a folder`() {
        Fixtures.openRscCheckout(dir, mapOf("local.conf" to "\tdb_name: folder\n"), dbName = "preservation")
        Files.createDirectories(databaseIn("folder.db"))
        assertFailsWith<IllegalArgumentException> { server("local").database() }
    }

    @Test
    fun `finding the server doesn't need its database, only database() does`() {
        Fixtures.openRscCheckout(dir, mapOf("local.conf" to "\tdb_name: elsewhere\n"), dbName = "preservation")
        assertEquals(43594, server("local").gamePort)
    }

    @Test
    fun `names the world's log as the server's LogUtil does`() {
        assertEquals("fenmoor_vale", OpenRscServer.worldLogName("  Fenmoor Vale "))
        assertEquals("fen__moor", OpenRscServer.worldLogName("Fen  Moor"))
        assertEquals("fen\tmoor", OpenRscServer.worldLogName("Fen\tMoor"))
        assertEquals("", OpenRscServer.worldLogName(""))
    }

    @Test
    fun `a world name given as blank logs to _number_log, as in the server`() {
        Fixtures.openRscCheckout(dir, mapOf("local.conf" to "\tserver_name:   \n\tworld_number: 2\n"), dbName = "preservation")
        assertEquals(dir.resolve("server/logs/_2.log"), server("local").worldLog)
    }

    @Test
    fun `reads a port or world number with a sign or leading zeros as the server does`() {
        Fixtures.openRscCheckout(dir, mapOf("local.conf" to "\tserver_port: +43597\n\tworld_number: 003\n"), dbName = "preservation")
        assertEquals(43597, server("local").gamePort)
        assertEquals(dir.resolve("server/logs/runescape_3.log"), server("local").worldLog)
        Files.writeString(dir.resolve("server/local.conf"), "\tserver_port: 043597\n\tworld_number: -1\n")
        assertEquals(43597, server("local").gamePort)
        assertEquals(dir.resolve("server/logs/runescape_-1.log"), server("local").worldLog)
    }

    @Test
    fun `refuses a port or world number the server would refuse, naming the setting and value`() {
        val refused = mapOf(
            "server_port: abc" to "The server's server_port is \"abc\"; the server accepts only a whole number",
            "server_port: 43597.0" to "The server's server_port is \"43597.0\"; the server accepts only a whole number",
            "server_port: 2147483648" to "The server's server_port is \"2147483648\"; the server accepts only a whole number",
            "server_port: null" to "The server's server_port is \"null\"; the server accepts only a whole number",
            "world_number: one" to "The server's world_number is \"one\"; the server accepts only a whole number",
        )
        Fixtures.openRscCheckout(dir, emptyMap(), dbName = "preservation")
        refused.forEach { (line, message) ->
            Files.writeString(dir.resolve("server/local.conf"), "\t$line\n")
            assertEquals(message, assertFailsWith<IllegalArgumentException>(line) { server("local") }.message)
        }
    }

    @Test
    fun `the default log sits beside the world's`() {
        Fixtures.openRscCheckout(dir, emptyMap(), dbName = "preservation")
        assertEquals(dir.resolve("server/logs/default.log"), server("default").defaultLog)
        assertEquals(dir.resolve("server"), server("default").serverDir)
    }
}
