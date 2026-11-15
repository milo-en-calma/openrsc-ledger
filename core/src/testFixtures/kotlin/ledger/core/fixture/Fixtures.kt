package ledger.core.fixture

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** Builds test copies of an OpenRSC server and its database, with invented players only. */
object Fixtures {
    /** Creates the fixture game database (`fixture/openrsc.sql`) at [file]. */
    fun database(file: Path): Path {
        val script = requireNotNull(Fixtures::class.java.getResource("/fixture/openrsc.sql")).readText()
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                script.lineSequence()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .split(';')
                    .filter { it.isNotBlank() }
                    .forEach(statement::executeUpdate)
            }
        }
        return file
    }

    /**
     * Lays out a minimal OpenRSC checkout under [dir]: `server/connections.conf`, the
     * given world configs (file name to contents), and the fixture database at
     * `server/inc/sqlite/<dbName>.db`. Returns [dir].
     */
    fun openRscCheckout(
        dir: Path,
        worldConfigs: Map<String, String>,
        dbName: String,
        connections: String = "db_type: sqlite # mysql or sqlite\n",
    ): Path {
        val server = Files.createDirectories(dir.resolve("server"))
        Files.writeString(server.resolve("connections.conf"), connections)
        worldConfigs.forEach { (name, text) -> Files.writeString(server.resolve(name), text) }
        database(Files.createDirectories(server.resolve("inc/sqlite")).resolve("$dbName.db"))
        return dir
    }
}
