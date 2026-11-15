package ledger.core.config

import java.io.StringReader
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LedgerConfigTest {
    private fun parse(text: String) = LedgerConfig.parse(Properties().apply { load(StringReader(text)) })

    @Test
    fun `reads the port`() {
        assertEquals(9123, parse("port = 9123").port)
    }

    @Test
    fun `uses the default port when none is given`() {
        assertEquals(LedgerConfig.DEFAULT_PORT, parse("# nothing set").port)
    }

    @Test
    fun `rejects a port that isn't a number`() {
        assertFailsWith<IllegalArgumentException> { parse("port = eighty") }
    }
}
