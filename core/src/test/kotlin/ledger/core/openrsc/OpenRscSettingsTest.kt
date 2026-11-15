package ledger.core.openrsc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenRscSettingsTest {
    private fun read(vararg files: String) = OpenRscSettings().apply { files.forEach { load(it.lines()) } }

    @Test
    fun `reads settings under section headers, dropping comments`() {
        val settings = read(
            """
            # World config
            database:
            	db_name: oakvale # the world's database
            world:
            	server_name: Oakvale
            """.trimIndent(),
        )
        assertEquals("oakvale", settings["db_name"])
        assertEquals("Oakvale", settings["server_name"])
    }

    @Test
    fun `skips commented-out settings`() {
        assertNull(read("# db_name: oakvale")["db_name"])
    }

    @Test
    fun `keeps the first value for a key, across files`() {
        assertEquals("first", read("db_name: first", "db_name: second")["db_name"])
    }

    @Test
    fun `a null value overwrites`() {
        assertEquals("null", read("webhook: https", "webhook: null")["webhook"])
    }

    @Test
    fun `keeps a value holding one colon`() {
        assertEquals("localhost:3306", read("db_host: localhost:3306")["db_host"])
    }

    @Test
    fun `keeps an empty value`() {
        assertEquals("", read("db_table_prefix:  # leave blank unless needed")["db_table_prefix"])
    }
}
