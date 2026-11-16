package ledger.admin

import ledger.core.fixture.Fixtures
import ledger.core.game.PlayerSkills
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import ledger.core.history.HistoryStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * `ledger-admin --check`: everything start-up reads, read once, counts and times only, and
 * its temporary copy of the game database gone however the check ends. Invented players only.
 */
class CheckTest {
    private val dir = createTempDirectory("ledger-check-test")
    private val live: Path = dir.resolve("openrsc/server/inc/sqlite/preservation.db")
    private val dataDir: Path = dir.resolve("data")
    private val lastSaved = Instant.parse("2026-03-01T10:00:00Z")

    /** Every temporary folder the check was given, to look in after it ends. */
    private val tempFolders = mutableListOf<Path>()

    private fun site(settings: String = "", worldConf: String = ""): Path {
        Fixtures.openRscCheckout(dir.resolve("openrsc"), mapOf("local.conf" to worldConf), dbName = "preservation")
        Files.setLastModifiedTime(live, FileTime.fromMillis(lastSaved.toEpochMilliseconds()))
        return dir.resolve("ledger.conf").also { it.writeText("openrsc.dir = openrsc\nport = 9123\n$settings") }
    }

    private fun runCheck(conf: Path): String {
        val (config, server) = loadSettings(conf)
        return check(conf, config, server) { createTempDirectory("ledger-check").also(tempFolders::add) }
    }

    private fun refusal(conf: Path): String = assertFailsWith<IllegalArgumentException> { runCheck(conf) }.message!!

    private fun assertTempFoldersGone() {
        assertEquals(1, tempFolders.size)
        tempFolders.forEach { assertFalse(Files.exists(it), "the temporary folder $it is still there") }
    }

    private fun sql(file: Path, vararg statements: String) {
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement -> statements.forEach(statement::executeUpdate) }
        }
    }

    @Test
    fun `prints the settings, the game database's counts and save time, and that there's no history yet`() {
        val conf = site()
        assertEquals(
            """
            |Settings, from $conf
            |  Game server folder: ${dir.resolve("openrsc/server")}
            |  World: default
            |  Game database: $live
            |  Equipment tab: off
            |  Serves on: http://127.0.0.1:9123
            |  Data folder: $dataDir
            |Game database, copied and checked
            |  Last saved: 2026-03-01T10:00:00Z
            |  Players: 3 (0 staff, 1 online at the last save, 0 missing a stat row)
            |  Item slots held: 8, of 7 kinds of item
            |History, in ${dataDir.resolve("history.db")}
            |  None yet: the data folder doesn't exist, and the ledger will make it
            |All read; nothing was served or changed, and the temporary copy is deleted.
            |""".trimMargin(),
            runCheck(conf),
        )
        assertTempFoldersGone()
        assertFalse(Files.exists(dataDir), "the check made the data folder")
    }

    @Test
    fun `never prints a player's name, email or address`() {
        val output = runCheck(site())
        listOf("Bramblewick", "Tansy", "Oakhollow", "example.invalid", "192.0.2").forEach { assertFalse(it in output, "printed $it") }
    }

    @Test
    fun `counts staff, players missing a stat row, and worn items in the equipment tab`() {
        val conf = site(worldConf = "\twant_equipment_tab: true\n")
        sql(live, "UPDATE players SET group_id = 2 WHERE id = 3", "DELETE FROM curstats WHERE playerID = 2")
        Files.setLastModifiedTime(live, FileTime.fromMillis(lastSaved.toEpochMilliseconds()))

        val output = runCheck(conf)
        assertContains(output, "  Equipment tab: on\n")
        assertContains(output, "  Players: 3 (1 staff, 1 online at the last save, 1 missing a stat row)\n")
        assertContains(output, "  Item slots held: 10, of 9 kinds of item\n")
    }

    @Test
    fun `reads the history without changing it or the data folder's copy`() {
        val conf = site()
        val history = HistoryStore(dataDir.resolve("history.db"))
        val skills = Skill.entries.associateWith { SkillValue(0, 1) }
        history.record(Instant.parse("2026-02-01T08:00:00Z"), listOf(PlayerSkills(1, skills), PlayerSkills(2, skills)))
        history.record(Instant.parse("2026-02-02T08:00:00Z"), listOf(PlayerSkills(1, skills + (Skill.WOODCUT to SkillValue(500, 4)))))
        val copy = dataDir.resolve("server-copy.db").also { it.writeText("the ledger's own copy") }
        val historyBytes = dataDir.resolve("history.db").readBytes()
        val historyTime = dataDir.resolve("history.db").getLastModifiedTime()

        assertContains(
            runCheck(conf),
            "  ${2 * Skill.entries.size + 1} recorded values for 2 players, from 2026-02-01T08:00:00Z to 2026-02-02T08:00:00Z\n",
        )
        assertContentEquals(historyBytes, dataDir.resolve("history.db").readBytes())
        assertEquals(historyTime, dataDir.resolve("history.db").getLastModifiedTime())
        assertEquals("the ledger's own copy", Files.readString(copy))
        assertEquals(setOf("history.db", "server-copy.db"), Files.list(dataDir).use { files -> files.map { it.fileName.toString() }.toList().toSet() })
        assertTempFoldersGone()
    }

    @Test
    fun `a data folder with no history file yet says the ledger will start one`() {
        val conf = site()
        dataDir.createDirectories()
        assertContains(runCheck(conf), "  None yet: no history file, and the ledger will start one\n")
        assertFalse(Files.exists(dataDir.resolve("history.db")))
    }

    @Test
    fun `a game database that can't be copied is refused, and the temporary folder is gone`() {
        val conf = site()
        live.writeText("not a database")
        assertEquals("Couldn't copy the game database: Copying $live failed with SQLite result code 26", refusal(conf))
        assertTempFoldersGone()
    }

    @Test
    fun `a copy with no players table is refused, and the temporary folder, which held the copy, is gone`() {
        val conf = site()
        Files.delete(live)
        sql(live, "CREATE TABLE other (id INT)")
        assertContains(refusal(conf), "Couldn't read the copy of the game database $live")
        assertTempFoldersGone()
    }

    @Test
    fun `a history file that isn't a database is refused after the copy, and the temporary folder is gone`() {
        val conf = site()
        dataDir.createDirectories().resolve("history.db").writeText("not a database")
        assertEquals("The history file ${dataDir.resolve("history.db")} isn't a SQLite database", refusal(conf))
        assertTempFoldersGone()
    }

    @Test
    fun `a data folder that is a file is refused, and the temporary folder is gone`() {
        val conf = site()
        dataDir.writeText("a file")
        assertEquals("The data folder $dataDir is a file, not a folder", refusal(conf))
        assertTempFoldersGone()
    }

    @Test
    fun `a data folder that can't be written is refused, and the temporary folder is gone`() {
        val conf = site()
        dataDir.createDirectories().toFile().setWritable(false)
        try {
            assertEquals("The data folder $dataDir can't be written", refusal(conf))
        } finally {
            dataDir.toFile().setWritable(true)
        }
        assertTempFoldersGone()
    }

    @Test
    fun `a missing game database is refused before any temporary folder is made`() {
        val conf = site()
        Files.delete(live)
        assertEquals("The server's database $live doesn't exist", refusal(conf))
        assertTrue(tempFolders.isEmpty())
    }
}
