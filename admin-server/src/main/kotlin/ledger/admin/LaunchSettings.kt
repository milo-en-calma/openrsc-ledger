package ledger.admin

import ledger.core.config.LedgerConfig
import ledger.core.openrsc.OpenRscServer

/**
 * What the `ledger` launch script needs from `ledger.conf` and the server's settings, as
 * `key=value` lines that bash can read. The script reads it through
 * `ledger-admin --print-launch-settings`, so `ledger.conf` is parsed in one place only.
 *
 * A value can hold anything but a line break, which would end its line early; a setting
 * with one is refused with an [IllegalArgumentException] naming it. The script splits each
 * line at its first `=`, so any other character, `=` included, comes back as printed.
 */
fun launchSettings(config: LedgerConfig, server: OpenRscServer): String {
    // Each value, and the ledger.conf setting it comes from, to name in a refusal.
    val settings = listOf(
        Triple("server_dir", server.serverDir, "openrsc.dir"),
        Triple("world", config.openRsc.world, "openrsc.world"),
        Triple("game_port", server.gamePort, "server_port"),
        Triple("ledger_port", config.port, "port"),
        Triple("data_dir", config.dataDir, "data-dir"),
        Triple("world_log", server.worldLog, "openrsc.dir"),
        Triple("default_log", server.defaultLog, "openrsc.dir"),
    )
    for ((_, value, setting) in settings) {
        require('\n' !in value.toString()) { "$setting has a line break in it, which the ledger script can't read" }
    }
    return settings.joinToString("") { (key, value) -> "$key=$value\n" }
}
