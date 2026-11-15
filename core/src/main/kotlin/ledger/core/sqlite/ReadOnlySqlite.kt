package ledger.core.sqlite

import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Opens the SQLite database [file] so that nothing done through the connection can change it. */
internal fun openReadOnly(file: Path): Connection {
    val config = SQLiteConfig().apply { setReadOnly(true) }
    return DriverManager.getConnection("jdbc:sqlite:$file", config.toProperties())
}
