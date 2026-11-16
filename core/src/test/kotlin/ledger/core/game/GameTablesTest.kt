package ledger.core.game

import ledger.core.fixture.Fixtures
import org.jetbrains.exposed.v1.core.Table
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameTablesTest {
    private val tables: List<Table> = listOf(Players, Experience, MaxStats, CurStats, Quests, InvItems, BankItems, Equipped, ItemStatuses)

    /**
     * Every column of the tables the ledger reads, as the server's SQLite schema creates them
     * (`core.sqlite`, and `add_equipment_tab.sqlite` for `equipped`).
     */
    private val serverColumns: Map<String, List<String>> = mapOf(
        "players" to listOf(
            "id", "username", "group_id", "email", "pass", "salt", "combat", "skill_total", "x", "y", "fatigue",
            "combatstyle", "block_chat", "block_private", "block_trade", "block_duel", "cameraauto", "onemouse",
            "soundoff", "haircolour", "topcolour", "trousercolour", "skincolour", "headsprite", "bodysprite", "male",
            "creation_date", "creation_ip", "login_date", "login_ip", "banned", "offences", "muted", "kills",
            "npc_kills", "deaths", "online", "quest_points", "lastRecoveryTryId",
        ),
        "experience" to listOf("playerID") + Skill.entries.map { it.column },
        "maxstats" to listOf("playerID") + Skill.entries.map { it.column },
        "curstats" to listOf("playerID") + Skill.entries.map { it.column },
        "quests" to listOf("dbid", "playerID", "id", "stage"),
        "invitems" to listOf("playerID", "itemID", "slot"),
        "bank" to listOf("playerID", "itemID", "slot"),
        "equipped" to listOf("playerID", "itemID"),
        "itemstatuses" to listOf("itemID", "catalogID", "amount", "noted", "wielded", "durability"),
    )

    private fun Table.columnNames() = columns.map { it.name }

    @Test
    fun `players declares only the columns the ledger shows`() {
        assertEquals(
            listOf("id", "username", "combat", "skill_total", "login_date", "online", "quest_points", "group_id"),
            Players.columnNames(),
        )
        for (secret in listOf("email", "pass", "salt", "creation_ip", "login_ip")) {
            assertTrue(secret in serverColumns.getValue("players"), "$secret is a server column")
            assertTrue(secret !in Players.columnNames(), "$secret must never be declared")
        }
    }

    @Test
    fun `every declared column is one the server creates`() {
        assertEquals(serverColumns.keys, tables.map { it.tableName }.toSet())
        for (table in tables) {
            val missing = table.columnNames() - serverColumns.getValue(table.tableName).toSet()
            assertEquals(emptyList(), missing, table.tableName)
        }
    }

    @Test
    fun `every declared column is in the test database`() {
        val file = Fixtures.database(Files.createTempDirectory("tables").resolve("copy.db"))
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            for (table in tables) {
                val columns = connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(${table.tableName})").use { rows ->
                        buildSet { while (rows.next()) { add(rows.getString("name")) } }
                    }
                }
                assertEquals(emptyList(), table.columnNames() - columns, table.tableName)
            }
        }
    }

    @Test
    fun `the staff and finished-quest numbers match the server's`() {
        // Group.USER: every group below it is staff.
        assertEquals(10, FIRST_PLAYER_GROUP)
        // Quests.QUEST_STAGE_COMPLETED.
        assertEquals(-1, QUEST_COMPLETE)
    }
}
