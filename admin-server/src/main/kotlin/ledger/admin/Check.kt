package ledger.admin

import ledger.core.config.LedgerConfig
import ledger.core.copy.DatabaseCopier
import ledger.core.game.SqliteGameData
import ledger.core.history.HistorySummary
import ledger.core.openrsc.OpenRscServer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * What `ledger-admin --check` prints: everything start-up would read, read once, without
 * serving or changing anything. The settings at [configPath] ([config] and the [server] they
 * name) are shown as the ledger would use them; the game database is copied once into a new
 * folder from [makeTempFolder], with the same copier and checks the ledger uses, and read from
 * there; the data folder is checked and its history file read without changing either.
 *
 * Counts and times only, never a player's name or anything about one player. The temporary
 * copy holds the whole game database, so its folder is deleted however the check ends: on
 * success, on any failure, and, by a shutdown hook, when the program is stopped mid-check.
 *
 * The first problem found is an [IllegalArgumentException] naming it, as start-up would refuse.
 */
fun check(
    configPath: Path,
    config: LedgerConfig,
    server: OpenRscServer,
    makeTempFolder: () -> Path = { Files.createTempDirectory("ledger-check") },
): String {
    val live = server.database()
    val folder = makeTempFolder()
    val cleanUp = Thread { deleteFolder(folder) }
    Runtime.getRuntime().addShutdownHook(cleanUp)
    try {
        val game = readGame(live, folder.resolve("server-copy.db"), server.equipmentTab)
        val history = readDataFolder(config.dataDir)
        return report(configPath, config, server, live, game, history)
    } finally {
        deleteFolder(folder)
        try {
            Runtime.getRuntime().removeShutdownHook(cleanUp)
        } catch (_: IllegalStateException) {
            // Already shutting down: the hook runs, and finds nothing left to delete.
        }
    }
}

/** What the check found in the copy of the game database. */
private data class GameCounts(
    val lastSaved: Instant,
    val players: Int,
    val staff: Int,
    val online: Int,
    val missingStats: Int,
    val itemSlots: Int,
    val itemKinds: Int,
)

private fun readGame(live: Path, copy: Path, equipmentTab: Boolean): GameCounts {
    val lastSaved = Files.getLastModifiedTime(live).toInstant().toKotlinInstant()
    try {
        DatabaseCopier(live, copy).copy()
    } catch (e: Exception) {
        // The copier's own messages name the live file.
        throw IllegalArgumentException("Couldn't copy the game database: ${e.message}", e)
    }

    try {
        val gameData = SqliteGameData(copy, equipmentTab = equipmentTab)
        val players = gameData.players()
        val held = gameData.allHeldItems()
        return GameCounts(
            lastSaved = lastSaved,
            players = players.size,
            staff = players.count { it.staff },
            online = players.count { it.online },
            missingStats = gameData.playersMissingStats().size,
            itemSlots = held.size,
            itemKinds = held.map { it.itemId }.distinct().size,
        )
    } catch (e: Exception) {
        throw IllegalArgumentException("Couldn't read the copy of the game database $live: ${e.message}", e)
    }
}

/**
 * Checks [dataDir] as start-up would use it, changing nothing: a folder that isn't one or
 * can't be written is refused. Returns its history, or null if there's no history file yet.
 */
private fun readDataFolder(dataDir: Path): HistorySummary? {
    if (!Files.exists(dataDir)) { return null }
    require(Files.isDirectory(dataDir)) { "The data folder $dataDir is a file, not a folder" }
    require(Files.isWritable(dataDir)) { "The data folder $dataDir can't be written" }
    return HistorySummary.read(dataDir.resolve("history.db"))
}

private fun report(configPath: Path, config: LedgerConfig, server: OpenRscServer, live: Path, game: GameCounts, history: HistorySummary?): String {
    val historyFile = config.dataDir.resolve("history.db")
    val historyLine = when {
        history == null && !Files.exists(config.dataDir) -> "None yet: the data folder doesn't exist, and the ledger will make it"
        history == null -> "None yet: no history file, and the ledger will start one"
        history.values == 0 -> "None recorded yet"
        else -> "${history.values} recorded values for ${history.players} players, from ${history.oldest} to ${history.newest}"
    }
    return """
        |Settings, from $configPath
        |  Game server folder: ${server.serverDir}
        |  World: ${config.openRsc.world}
        |  Game database: $live
        |  Equipment tab: ${if (server.equipmentTab) "on" else "off"}
        |  Serves on: http://$HOST:${config.port}
        |  Data folder: ${config.dataDir}
        |Game database, copied and checked
        |  Last saved: ${game.lastSaved}
        |  Players: ${game.players} (${game.staff} staff, ${game.online} online at the last save, ${game.missingStats} missing a stat row)
        |  Item slots held: ${game.itemSlots}, of ${game.itemKinds} kinds of item
        |History, in $historyFile
        |  $historyLine
        |All read; nothing was served or changed, and the temporary copy is deleted.
        |""".trimMargin()
}

@OptIn(ExperimentalPathApi::class)
private fun deleteFolder(folder: Path) {
    if (Files.exists(folder)) { folder.deleteRecursively() }
}
