package ledger.admin

import com.typesafe.config.ConfigFactory
import ledger.core.config.LedgerConfig
import ledger.core.fixture.Fixtures
import ledger.core.openrsc.OpenRscServer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchSettingsTest {
    @Test
    fun `prints what the launch script reads, one key=value a line`() {
        val dir = createTempDirectory("ledger-test")
        Fixtures.openRscCheckout(dir.resolve("openrsc"), mapOf("local.conf" to "\tserver_port: 43597\n"), dbName = "preservation")
        val config = LedgerConfig.parse(
            ConfigFactory.parseString("openrsc { dir = openrsc, world = fenmoor }\nport = 9123"),
            dir,
        )
        assertEquals(
            """
            server_dir=$dir/openrsc/server
            world=fenmoor
            game_port=43597
            ledger_port=9123
            data_dir=$dir/data
            world_log=$dir/openrsc/server/logs/runescape_1.log
            default_log=$dir/openrsc/server/logs/default.log

            """.trimIndent(),
            launchSettings(config, OpenRscServer.load(config.openRsc)),
        )
    }
}
