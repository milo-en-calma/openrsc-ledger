package ledger.admin

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** How `ledger-admin [--print-launch-settings] [config file]` reads its arguments. */
class MainTest {
    private val flag = "--print-launch-settings"

    @Test
    fun `with nothing given it serves, from ledger_conf in the working directory`() {
        assertEquals(Arguments(printLaunchSettings = false, configPath = Path.of("ledger.conf")), parseArguments(emptyList(), ledgerConf = null))
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
    fun `the flag alone prints the launch settings of the usual config`() {
        assertEquals(Arguments(printLaunchSettings = true, configPath = Path.of("ledger.conf")), parseArguments(listOf(flag), ledgerConf = null))
        assertEquals(Arguments(printLaunchSettings = true, configPath = Path.of("site.conf")), parseArguments(listOf(flag), ledgerConf = "site.conf"))
    }

    @Test
    fun `the flag may come before or after the file`() {
        val expected = Arguments(printLaunchSettings = true, configPath = Path.of("mine.conf"))
        assertEquals(expected, parseArguments(listOf(flag, "mine.conf"), ledgerConf = null))
        assertEquals(expected, parseArguments(listOf("mine.conf", flag), ledgerConf = null))
    }

    @Test
    fun `the first file given is the one read`() {
        assertEquals(Path.of("first.conf"), parseArguments(listOf("first.conf", "second.conf"), ledgerConf = null).configPath)
    }
}
