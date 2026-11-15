package ledger.core.openrsc

import ledger.core.config.OpenRscConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * The OpenRSC game server's settings, read the way the server reads them when it starts a
 * world:
 *
 * 1. `server/connections.conf`, which says whether the database is SQLite or MySQL.
 * 2. `server/local.conf` if that exists, **whichever world the server was launched with**,
 *    and only otherwise the launched world's file, such as `server/preservation.conf`.
 *
 * So the configured world only matters on a server without a `local.conf`.
 */
class OpenRscServer private constructor(
    /** The checkout's `server/` folder, where the server runs. */
    val serverDir: Path,
    private val settings: OpenRscSettings,
) {
    /** The port the game listens on for players (`server_port`). */
    val gamePort: Int = settings["server_port"]?.toIntOrNull() ?: DEFAULT_GAME_PORT

    /** The live SQLite database: `server/inc/sqlite/<db_name>.db`. */
    fun database(): Path {
        require(isSqlite(settings["db_type"])) {
            "The server's database is MySQL (db_type in ${serverDir.resolve("connections.conf")}); the ledger reads only SQLite so far"
        }
        val dbName = settings["db_name"] ?: DEFAULT_DB_NAME
        val database = serverDir.resolve("inc/sqlite/$dbName.db")
        require(Files.isRegularFile(database)) { "The server's database $database doesn't exist" }
        return database
    }

    companion object {
        const val DEFAULT_DB_NAME = "preservation"
        const val DEFAULT_GAME_PORT = 43594

        fun load(openRsc: OpenRscConfig): OpenRscServer {
            val serverDir = openRsc.dir.resolve("server")
            require(Files.isDirectory(serverDir)) {
                "No server/ folder in ${openRsc.dir}: openrsc.dir must name the OpenRSC checkout"
            }
            val connections = serverDir.resolve("connections.conf")
            require(Files.isRegularFile(connections)) { "The server has no config at $connections" }

            val settings = OpenRscSettings()
            settings.load(connections)
            listOf("local.conf", "${openRsc.world}.conf")
                .map(serverDir::resolve)
                .firstOrNull(Files::isRegularFile)
                ?.let(settings::load)
            return OpenRscServer(serverDir, settings)
        }

        /**
         * The server's reading of `db_type`: `mysql` in any case, or a number other than 1, is
         * MySQL; anything else, a missing value included, is SQLite.
         */
        internal fun isSqlite(dbType: String?): Boolean = when {
            dbType == null -> true
            dbType.equals("mysql", ignoreCase = true) -> false
            dbType.equals("sqlite", ignoreCase = true) -> true
            else -> dbType.toIntOrNull()?.let { it == 1 } ?: true
        }
    }
}
