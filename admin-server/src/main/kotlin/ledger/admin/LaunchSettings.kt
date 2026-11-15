package ledger.admin

import ledger.core.config.LedgerConfig
import ledger.core.openrsc.OpenRscServer

/**
 * What the `ledger` launch script needs from `ledger.conf` and the server's settings, as
 * `key=value` lines that bash can read. The script reads it through
 * `ledger-admin --print-launch-settings`, so `ledger.conf` is parsed in one place only.
 */
fun launchSettings(config: LedgerConfig, server: OpenRscServer): String = listOf(
    "server_dir" to server.serverDir,
    "world" to config.openRsc.world,
    "game_port" to server.gamePort,
    "ledger_port" to config.port,
    "data_dir" to config.dataDir,
).joinToString("") { (key, value) -> "$key=$value\n" }
