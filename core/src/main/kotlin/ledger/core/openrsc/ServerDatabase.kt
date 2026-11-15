package ledger.core.openrsc

import ledger.core.config.OpenRscConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds the game server's live SQLite database the way the server itself does when it
 * starts a world:
 *
 * 1. It reads `server/connections.conf`, which says whether the database is SQLite or MySQL.
 * 2. It reads `server/local.conf` if that exists, **whichever world it was launched with**,
 *    and only otherwise the launched world's file, such as `server/preservation.conf`.
 * 3. The database is `server/inc/sqlite/<db_name>.db`, with `db_name` defaulting to
 *    `preservation`.
 *
 * So the configured world only matters on a server without a `local.conf`.
 */
object ServerDatabase {
    const val DEFAULT_DB_NAME = "preservation"

    fun locate(openRsc: OpenRscConfig): Path {
        val serverDir = openRsc.dir.resolve("server")
        require(Files.isDirectory(serverDir)) {
            "No server/ folder in ${openRsc.dir}: openrsc.dir must name the OpenRSC checkout"
        }

        val settings = OpenRscSettings()
        val connections = serverDir.resolve("connections.conf")
        require(Files.isRegularFile(connections)) { "The server has no config at $connections" }
        settings.load(connections)
        require(isSqlite(settings["db_type"])) {
            "The server's database is MySQL (db_type in $connections); the ledger reads only SQLite so far"
        }

        listOf("local.conf", "${openRsc.world}.conf")
            .map(serverDir::resolve)
            .firstOrNull(Files::isRegularFile)
            ?.let(settings::load)

        val dbName = settings["db_name"] ?: DEFAULT_DB_NAME
        val database = serverDir.resolve("inc/sqlite/$dbName.db")
        require(Files.isRegularFile(database)) { "The server's database $database doesn't exist" }
        return database
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
