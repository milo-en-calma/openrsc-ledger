package ledger.core.openrsc

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `a value holding one colon overwrites an earlier one`() {
        assertEquals("db.example:3307", read("db_host: localhost", "db_host: db.example:3307")["db_host"])
    }

    @Test
    fun `null in any capitals overwrites`() {
        assertEquals("NULL", read("webhook: https", "webhook: NULL")["webhook"])
        assertEquals("Null", read("webhook: https", "webhook: Null")["webhook"])
    }

    @Test
    fun `a real value after a null doesn't replace it`() {
        assertEquals("null", read("webhook: null", "webhook: https")["webhook"])
    }

    @Test
    fun `a value holding two colons is dropped`() {
        assertNull(read("webhook: http://example.invalid:80")["webhook"])
        // ...and doesn't touch a value read earlier.
        assertEquals("first", read("webhook: first", "webhook: http://example.invalid:80")["webhook"])
    }

    @Test
    fun `a value holding a hash is cut off there, as a comment`() {
        assertEquals("", read("color: #ff0000")["color"])
        assertEquals("oak", read("tree: oak#willow")["tree"])
    }

    @Test
    fun `a trailing colon is dropped, as Java's split drops it`() {
        assertEquals("oakvale", read("db_name: oakvale:")["db_name"])
        // A space after it isn't empty when split, so the line holds one colon in its value.
        assertEquals("oakvale:", read("db_name: oakvale: ")["db_name"])
    }

    @Test
    fun `skips a line ending in a bare hash, as the server does`() {
        assertNull(read("db_name: oakvale #")["db_name"])
        assertNull(read("db_name: oakvale ##")["db_name"])
        assertNull(read("color: #")["color"])
        // ...but not with anything after the hash, even a space.
        assertEquals("oakvale", read("db_name: oakvale # ")["db_name"])
        assertEquals("", read("color: #ff0000 #")["color"])
    }

    @Test
    fun `skips a line of hashes alone`() {
        assertNull(read("#", "####", "# :")[""])
    }

    @Test
    fun `reads a file`() {
        val file = createTempDirectory("ledger-test").resolve("local.conf")
        file.writeText("world:\n\tserver_port: 43597 # the game's port\n\tdb_name: oakvale\n")
        val settings = OpenRscSettings().apply { load(file) }
        assertEquals("43597", settings["server_port"])
        assertEquals("oakvale", settings["db_name"])
    }

    @Test
    fun `a file it can't read is a plain refusal naming the file`() {
        val file = createTempDirectory("ledger-test").resolve("local.conf")
        file.writeText("db_name: oakvale\n")
        file.toFile().setReadable(false)

        val error = assertFailsWith<IllegalArgumentException> { OpenRscSettings().load(file) }
        assertContains(error.message!!, "Can't read the server's settings file $file")
    }
}
